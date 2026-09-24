package com.nsangusa.news.aieditorial.internal;

import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisRequest;
import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisResult;
import com.nsangusa.news.aieditorial.EditorialProviders.ArticleDraftProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ContentSafetyProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.DraftRequest;
import com.nsangusa.news.aieditorial.EditorialProviders.EditorialAnalysisProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration;
import com.nsangusa.news.aieditorial.EditorialProviders.ProviderIdentity;
import com.nsangusa.news.aieditorial.EditorialProviders.SafetyResult;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftRequested;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisBlocked;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisRequested;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class EditorialWorkflowConsumer {
  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final DurableEventPublisher events;
  private final EditorialAnalysisProvider analysisProvider;
  private final ArticleDraftProvider draftProvider;
  private final ContentSafetyProvider safetyProvider;
  private final AiRequestAuditService audit;
  private final AiUsageMetrics metrics;
  private final Validator validator;
  private final EditorialSemanticValidator semantics;
  private final AiProviderCallExecutor calls;

  EditorialWorkflowConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      DurableEventPublisher events,
      EditorialAnalysisProvider analysisProvider,
      ArticleDraftProvider draftProvider,
      ContentSafetyProvider safetyProvider,
      AiRequestAuditService audit,
      AiUsageMetrics metrics,
      Validator validator,
      EditorialSemanticValidator semantics,
      AiProviderCallExecutor calls) {
    this.reader = reader;
    this.processed = processed;
    this.events = events;
    this.analysisProvider = analysisProvider;
    this.draftProvider = draftProvider;
    this.safetyProvider = safetyProvider;
    this.audit = audit;
    this.metrics = metrics;
    this.validator = validator;
    this.semantics = semantics;
    this.calls = calls;
  }

  @KafkaListener(topics = EventTopics.EDITORIAL, groupId = "story-analysis-v1")
  @Transactional
  void analyze(String json) {
    if (!"StoryAnalysisRequested".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, StoryAnalysisRequested.class);
    if (processed.wasProcessed(event.eventId(), "story-analysis-v1")) {
      return;
    }
    var safety =
        audited(
            event.eventId(),
            event.causationId(),
            event.payload().storyCandidateId(),
            "safety",
            safetyProvider,
            SafetyResult.class,
            configuration -> {
              semantics.validate(event.payload());
              var material = semantics.authorizedSources(event.payload().sources());
              return safetyProvider.evaluate(
                  semantics.sourceMaterial(event.payload().sources(), material), configuration);
            },
            result -> {
              semantics.validate(result);
              return result;
            });
    if (!safety.allowed()) {
      var flags =
          safety.flags() == null || safety.flags().isEmpty()
              ? java.util.List.of("unspecified-safety-rejection")
              : safety.flags().stream().distinct().toList();
      metrics.recordSafetyBlock();
      events.enqueue(
          "StoryAnalysisBlocked",
          event.payload().storyCandidateId(),
          event.correlationId(),
          event.eventId(),
          "story-analysis-blocked:" + event.payload().storyCandidateId(),
          new StoryAnalysisBlocked(
              event.payload().storyCandidateId(),
              flags,
              "Source material failed content-safety policy",
              java.time.Instant.now()));
      processed.markProcessed(event.eventId(), "story-analysis-v1");
      return;
    }
    var result =
        audited(
            event.eventId(),
            event.causationId(),
            event.payload().storyCandidateId(),
            "analysis",
            analysisProvider,
            AnalysisResult.class,
            configuration -> {
              var material = semantics.authorizedSources(event.payload().sources());
              return analysisProvider.analyze(
                  new AnalysisRequest(
                      event.payload().storyCandidateId(),
                      event.payload().sources(),
                      semantics.sourceMaterial(event.payload().sources(), material)),
                  configuration);
            },
            analysis -> {
              semantics.analysis(analysis, semantics.authorizedSources(event.payload().sources()));
              var required =
                  event.payload().sources().size() == 1
                      ? List.of(
                          "single-source",
                          "independent-confirmation-required",
                          "human-review-required")
                      : List.of("independent-confirmation-required", "human-review-required");
              var warnings =
                  java.util.stream.Stream.of(analysis.warnings(), safety.flags(), required)
                      .flatMap(java.util.Collection::stream)
                      .distinct()
                      .toList();
              var normalized =
                  new AnalysisResult(
                      analysis.analysis(),
                      analysis.claims(),
                      analysis.confidence(),
                      warnings,
                      analysis.provider(),
                      analysis.model(),
                      analysis.inputTokens(),
                      analysis.outputTokens(),
                      analysis.promptVersion(),
                      analysis.generatedAt());
              semantics.validate(normalized);
              return normalized;
            });
    var payload =
        new ArticleDraftRequested(
            event.payload().storyCandidateId(),
            event.payload().sources(),
            result.analysis(),
            result.claims(),
            result.confidence(),
            result.warnings());
    events.enqueue(
        "ArticleDraftRequested",
        event.payload().storyCandidateId(),
        event.correlationId(),
        event.eventId(),
        "article-draft-requested:" + event.payload().storyCandidateId(),
        payload);
    processed.markProcessed(event.eventId(), "story-analysis-v1");
  }

  @KafkaListener(topics = EventTopics.EDITORIAL, groupId = "article-drafting-v1")
  @Transactional
  void draft(String json) {
    if (!"ArticleDraftRequested".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, ArticleDraftRequested.class);
    if (processed.wasProcessed(event.eventId(), "article-drafting-v1")) {
      return;
    }
    var draft =
        audited(
            event.eventId(),
            event.causationId(),
            event.payload().storyCandidateId(),
            "draft",
            draftProvider,
            ArticleDraftGenerated.class,
            configuration -> {
              semantics.validate(event.payload());
              var material = semantics.authorizedSources(event.payload().sources());
              semantics.claims(event.payload().claims(), material);
              var providerDraft =
                  draftProvider.draft(
                      new DraftRequest(
                          event.payload().storyCandidateId(),
                          event.payload().sources(),
                          event.payload().analysis(),
                          event.payload().claims(),
                          event.payload().confidence(),
                          event.payload().warnings()),
                      configuration);
              return providerDraft;
            },
            providerDraft -> {
              var violations = validator.validate(providerDraft);
              if (!violations.isEmpty()) {
                throw new ConstraintViolationException(
                    "AI draft did not satisfy the contract", violations);
              }
              semantics.draft(
                  providerDraft,
                  event.payload().storyCandidateId(),
                  event.payload().sources(),
                  event.payload().claims(),
                  semantics.authorizedSources(event.payload().sources()));
              if (providerDraft.confidence().compareTo(event.payload().confidence()) > 0) {
                throw new AiProviderException("unsupported_confidence");
              }
              var reviewedDraft = mergeWarnings(providerDraft, event.payload().warnings());
              semantics.validate(reviewedDraft);
              return reviewedDraft;
            });
    var generatedSafety =
        audited(
            event.eventId(),
            event.causationId(),
            event.payload().storyCandidateId(),
            "draft-safety",
            safetyProvider,
            SafetyResult.class,
            configuration -> safetyProvider.evaluate(generatedContent(draft), configuration),
            result -> {
              semantics.validate(result);
              return result;
            });
    var reviewedDraft = mergeGeneratedSafety(draft, generatedSafety.flags());
    semantics.validate(reviewedDraft);
    audit.reviewDraft(event.eventId(), reviewedDraft, generatedSafety.allowed());
    if (!generatedSafety.allowed()) {
      metrics.recordSafetyBlock();
      var flags =
          generatedSafety.flags().isEmpty()
              ? List.of("unspecified-safety-rejection")
              : generatedSafety.flags().stream().distinct().toList();
      events.enqueue(
          "StoryAnalysisBlocked",
          event.payload().storyCandidateId(),
          event.correlationId(),
          event.eventId(),
          "story-analysis-blocked:" + event.payload().storyCandidateId(),
          new StoryAnalysisBlocked(
              event.payload().storyCandidateId(),
              flags,
              "Generated draft failed content-safety policy",
              java.time.Instant.now()));
      processed.markProcessed(event.eventId(), "article-drafting-v1");
      return;
    }
    UUID articleId = UUID.randomUUID();
    events.enqueue(
        "ArticleDraftGenerated",
        articleId,
        event.correlationId(),
        event.eventId(),
        "article-draft-generated:" + event.payload().storyCandidateId(),
        reviewedDraft);
    processed.markProcessed(event.eventId(), "article-drafting-v1");
  }

  private static ArticleDraftGenerated mergeWarnings(
      ArticleDraftGenerated draft, java.util.List<String> requiredWarnings) {
    var warnings =
        java.util.stream.Stream.of(requiredWarnings, draft.uncertaintyNotes(), draft.safetyFlags())
            .flatMap(java.util.Collection::stream)
            .distinct()
            .toList();
    return new ArticleDraftGenerated(
        draft.storyCandidateId(),
        draft.headline(),
        draft.summary(),
        draft.body(),
        draft.editorialContext(),
        draft.seoTitle(),
        draft.seoDescription(),
        draft.slugSuggestion(),
        draft.tags(),
        draft.topic(),
        draft.sources(),
        draft.claims(),
        draft.confidence(),
        warnings,
        draft.safetyFlags(),
        true,
        draft.imagePrompt(),
        draft.imageAltText(),
        draft.socialPreviewText(),
        draft.provider(),
        draft.model(),
        draft.promptVersion(),
        draft.inputTokens(),
        draft.outputTokens(),
        draft.generatedAt());
  }

  private static String generatedContent(ArticleDraftGenerated draft) {
    return java.util.stream.Stream.concat(
            java.util.stream.Stream.of(
                draft.headline(),
                draft.summary(),
                draft.body(),
                draft.editorialContext(),
                draft.seoTitle(),
                draft.seoDescription(),
                draft.topic(),
                draft.slugSuggestion(),
                String.join(", ", draft.tags()),
                String.join("\n", draft.uncertaintyNotes()),
                String.join("\n", draft.safetyFlags()),
                draft.imagePrompt(),
                draft.imageAltText(),
                draft.socialPreviewText()),
            draft.claims().stream().map(claim -> claim.text()))
        .filter(java.util.Objects::nonNull)
        .collect(java.util.stream.Collectors.joining("\n\n"));
  }

  private static ArticleDraftGenerated mergeGeneratedSafety(
      ArticleDraftGenerated draft, List<String> generatedFlags) {
    var flags =
        java.util.stream.Stream.concat(draft.safetyFlags().stream(), generatedFlags.stream())
            .distinct()
            .toList();
    var warnings =
        java.util.stream.Stream.concat(draft.uncertaintyNotes().stream(), flags.stream())
            .distinct()
            .toList();
    return new ArticleDraftGenerated(
        draft.storyCandidateId(),
        draft.headline(),
        draft.summary(),
        draft.body(),
        draft.editorialContext(),
        draft.seoTitle(),
        draft.seoDescription(),
        draft.slugSuggestion(),
        draft.tags(),
        draft.topic(),
        draft.sources(),
        draft.claims(),
        draft.confidence(),
        warnings,
        flags,
        true,
        draft.imagePrompt(),
        draft.imageAltText(),
        draft.socialPreviewText(),
        draft.provider(),
        draft.model(),
        draft.promptVersion(),
        draft.inputTokens(),
        draft.outputTokens(),
        draft.generatedAt());
  }

  private <T> T audited(
      UUID eventId,
      UUID causationId,
      UUID storyId,
      String operation,
      ProviderIdentity provider,
      Class<T> type,
      Function<ProviderConfiguration, T> generate,
      UnaryOperator<T> validate) {
    var existing = audit.completed(eventId, operation, type);
    if (existing.isPresent()) {
      return validate.apply(existing.get());
    }
    var configuration =
        audit.workflowSnapshot(eventId, storyId, causationId, operation, provider::configuration);
    return calls.execute(
        operation,
        () -> {
          UUID attemptId = audit.begin(eventId, storyId, operation, configuration);
          T result = null;
          try {
            if (audit.budgetExhausted(attemptId)) {
              throw new AiProviderException("daily_token_budget");
            }
            result = generate.apply(configuration);
            result = validate.apply(result);
            audit.complete(attemptId, result);
            var usage = AiRequestAuditService.usage(result);
            metrics.recordUsage(
                operation,
                usage.configuration().provider(),
                usage.configuration().model(),
                usage.inputTokens(),
                usage.outputTokens());
            return result;
          } catch (RuntimeException failure) {
            audit.fail(attemptId, failure, result);
            if (result != null) {
              var usage = AiRequestAuditService.usage(result);
              metrics.recordUsage(
                  operation,
                  usage.configuration().provider(),
                  usage.configuration().model(),
                  Math.max(0, usage.inputTokens()),
                  Math.max(0, usage.outputTokens()));
            } else if (failure instanceof AiProviderException rejected
                && rejected.model() != null) {
              metrics.recordUsage(
                  operation,
                  configuration.provider(),
                  rejected.model(),
                  rejected.inputTokens(),
                  rejected.outputTokens());
            }
            throw failure;
          }
        });
  }
}
