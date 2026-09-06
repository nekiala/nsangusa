package com.nsangusa.news.aieditorial.internal;

import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisRequest;
import com.nsangusa.news.aieditorial.EditorialProviders.ArticleDraftProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ContentSafetyProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.DraftRequest;
import com.nsangusa.news.aieditorial.EditorialProviders.EditorialAnalysisProvider;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftRequested;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisRequested;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.UUID;
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
  private final AiRequestRepository requests;
  private final Validator validator;

  EditorialWorkflowConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      DurableEventPublisher events,
      EditorialAnalysisProvider analysisProvider,
      ArticleDraftProvider draftProvider,
      ContentSafetyProvider safetyProvider,
      AiRequestRepository requests,
      Validator validator) {
    this.reader = reader;
    this.processed = processed;
    this.events = events;
    this.analysisProvider = analysisProvider;
    this.draftProvider = draftProvider;
    this.safetyProvider = safetyProvider;
    this.requests = requests;
    this.validator = validator;
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
    var audit = requests.save(new AiRequestRecord(event.payload().storyCandidateId(), "analysis"));
    var safety = safetyProvider.evaluate(event.payload().sourceMaterial());
    var result =
        analysisProvider.analyze(
            new AnalysisRequest(
                event.payload().storyCandidateId(),
                event.payload().sources(),
                event.payload().sourceMaterial()));
    audit.complete(result.provider(), result.model(), result.inputTokens(), result.outputTokens());
    var warnings =
        java.util.stream.Stream.concat(result.warnings().stream(), safety.flags().stream())
            .distinct()
            .toList();
    var payload =
        new ArticleDraftRequested(
            event.payload().storyCandidateId(),
            event.payload().sources(),
            result.analysis(),
            result.claims(),
            result.confidence(),
            warnings);
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
    var audit = requests.save(new AiRequestRecord(event.payload().storyCandidateId(), "draft"));
    var draft =
        draftProvider.draft(
            new DraftRequest(
                event.payload().storyCandidateId(),
                event.payload().sources(),
                event.payload().analysis(),
                event.payload().claims(),
                event.payload().confidence(),
                event.payload().warnings()));
    var violations = validator.validate(draft);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException("AI draft did not satisfy the contract", violations);
    }
    audit.complete(draft.provider(), draft.model(), 0, 0);
    UUID articleId = UUID.randomUUID();
    events.enqueue(
        "ArticleDraftGenerated",
        articleId,
        event.correlationId(),
        event.eventId(),
        "article-draft-generated:" + event.payload().storyCandidateId(),
        draft);
    processed.markProcessed(event.eventId(), "article-drafting-v1");
  }
}
