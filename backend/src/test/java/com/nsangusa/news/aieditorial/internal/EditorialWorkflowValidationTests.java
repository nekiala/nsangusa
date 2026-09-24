package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.aieditorial.EditorialProviders.ArticleDraftProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ContentSafetyProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.EditorialAnalysisProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.SafetyResult;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftRequested;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisBlocked;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisRequested;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EditorialWorkflowValidationTests {
  @Test
  void invalidStructuredProviderOutputIsRejectedBeforeOutboxAndIdempotencyMarker() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var events = mock(DurableEventPublisher.class);
    var analysis = mock(EditorialAnalysisProvider.class);
    var drafts = mock(ArticleDraftProvider.class);
    var safety = mock(ContentSafetyProvider.class);
    var requests = mock(AiRequestAuditService.class);
    var event = draftRequest();
    when(reader.eventType("event")).thenReturn("ArticleDraftRequested");
    when(reader.read("event", ArticleDraftRequested.class)).thenReturn(event);
    when(drafts.draft(any(), any())).thenReturn(invalidDraft(event.payload()));
    var validator = Validation.buildDefaultValidatorFactory().getValidator();
    var metrics = new AiUsageMetrics(new SimpleMeterRegistry(), BigDecimal.ZERO, BigDecimal.ZERO);
    var consumer =
        new EditorialWorkflowConsumer(
            reader,
            processed,
            events,
            analysis,
            drafts,
            safety,
            requests,
            metrics,
            validator,
            mock(EditorialSemanticValidator.class),
            executor());

    assertThatThrownBy(() -> consumer.draft("event"))
        .isInstanceOf(ConstraintViolationException.class)
        .hasMessageContaining("AI draft did not satisfy the contract");
    verify(events, never()).enqueue(any(), any(), any(), any(), any(), any());
    verify(processed, never()).markProcessed(any(), any());
    verify(requests).fail(any(), any(ConstraintViolationException.class), any());
  }

  @Test
  void draftCannotDropUpstreamWarningsOrSafetyFlags() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var events = mock(DurableEventPublisher.class);
    var analysis = mock(EditorialAnalysisProvider.class);
    var drafts = mock(ArticleDraftProvider.class);
    var safety = mock(ContentSafetyProvider.class);
    var requests = mock(AiRequestAuditService.class);
    var event = draftRequest();
    when(reader.eventType("event")).thenReturn("ArticleDraftRequested");
    when(reader.read("event", ArticleDraftRequested.class)).thenReturn(event);
    when(drafts.draft(any(), any()))
        .thenReturn(validDraft(event.payload(), List.of(), List.of("provider-safety-review")));
    when(safety.evaluate(any(), any()))
        .thenReturn(new SafetyResult(true, List.of("post-generation-review")));
    var consumer =
        new EditorialWorkflowConsumer(
            reader,
            processed,
            events,
            analysis,
            drafts,
            safety,
            requests,
            new AiUsageMetrics(new SimpleMeterRegistry(), BigDecimal.ZERO, BigDecimal.ZERO),
            Validation.buildDefaultValidatorFactory().getValidator(),
            mock(EditorialSemanticValidator.class),
            executor());

    consumer.draft("event");

    var generated = ArgumentCaptor.forClass(ArticleDraftGenerated.class);
    verify(events)
        .enqueue(
            org.mockito.ArgumentMatchers.eq("ArticleDraftGenerated"),
            any(),
            org.mockito.ArgumentMatchers.eq(event.correlationId()),
            org.mockito.ArgumentMatchers.eq(event.eventId()),
            org.mockito.ArgumentMatchers.eq(
                "article-draft-generated:" + event.payload().storyCandidateId()),
            generated.capture());
    assertThat(generated.getValue().uncertaintyNotes())
        .containsExactlyInAnyOrder(
            "single source", "provider-safety-review", "post-generation-review");
    assertThat(generated.getValue().safetyFlags())
        .containsExactlyInAnyOrder("provider-safety-review", "post-generation-review");
    assertThat(generated.getValue().humanReviewRequired()).isTrue();
    verify(requests)
        .reviewDraft(
            org.mockito.ArgumentMatchers.eq(event.eventId()),
            org.mockito.ArgumentMatchers.eq(generated.getValue()),
            org.mockito.ArgumentMatchers.eq(true));
  }

  @Test
  void unsafeGeneratedDraftIsAuditedAndBlockedBeforeArticleCreation() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var events = mock(DurableEventPublisher.class);
    var analysis = mock(EditorialAnalysisProvider.class);
    var drafts = mock(ArticleDraftProvider.class);
    var safety = mock(ContentSafetyProvider.class);
    var audit = mock(AiRequestAuditService.class);
    var event = draftRequest();
    when(reader.eventType("event")).thenReturn("ArticleDraftRequested");
    when(reader.read("event", ArticleDraftRequested.class)).thenReturn(event);
    when(drafts.draft(any(), any())).thenReturn(validDraft(event.payload(), List.of(), List.of()));
    when(safety.evaluate(any(), any()))
        .thenReturn(new SafetyResult(false, List.of("unsafe-generated-content")));
    var consumer =
        new EditorialWorkflowConsumer(
            reader,
            processed,
            events,
            analysis,
            drafts,
            safety,
            audit,
            new AiUsageMetrics(new SimpleMeterRegistry(), BigDecimal.ZERO, BigDecimal.ZERO),
            Validation.buildDefaultValidatorFactory().getValidator(),
            mock(EditorialSemanticValidator.class),
            executor());

    consumer.draft("event");

    var checked = ArgumentCaptor.forClass(String.class);
    verify(safety).evaluate(checked.capture(), any());
    assertThat(checked.getValue()).contains("Headline", "Body", "Illustration", "Preview");
    verify(audit)
        .reviewDraft(
            org.mockito.ArgumentMatchers.eq(event.eventId()),
            any(ArticleDraftGenerated.class),
            org.mockito.ArgumentMatchers.eq(false));
    var blocked = ArgumentCaptor.forClass(StoryAnalysisBlocked.class);
    verify(events)
        .enqueue(
            org.mockito.ArgumentMatchers.eq("StoryAnalysisBlocked"),
            any(),
            any(),
            any(),
            any(),
            blocked.capture());
    assertThat(blocked.getValue().safetyFlags()).containsExactly("unsafe-generated-content");
    verify(events, never())
        .enqueue(
            org.mockito.ArgumentMatchers.eq("ArticleDraftGenerated"),
            any(),
            any(),
            any(),
            any(),
            any());
    verify(processed).markProcessed(event.eventId(), "article-drafting-v1");
  }

  @Test
  void malformedGeneratedSafetyResponseFailsClosedWithoutMarkingEventProcessed() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var events = mock(DurableEventPublisher.class);
    var analysis = mock(EditorialAnalysisProvider.class);
    var drafts = mock(ArticleDraftProvider.class);
    var safety = mock(ContentSafetyProvider.class);
    var audit = mock(AiRequestAuditService.class);
    var event = draftRequest();
    when(reader.eventType("event")).thenReturn("ArticleDraftRequested");
    when(reader.read("event", ArticleDraftRequested.class)).thenReturn(event);
    when(drafts.draft(any(), any())).thenReturn(validDraft(event.payload(), List.of(), List.of()));
    when(safety.evaluate(any(), any())).thenThrow(new AiProviderException("provider_refusal"));
    var consumer =
        new EditorialWorkflowConsumer(
            reader,
            processed,
            events,
            analysis,
            drafts,
            safety,
            audit,
            new AiUsageMetrics(new SimpleMeterRegistry(), BigDecimal.ZERO, BigDecimal.ZERO),
            Validation.buildDefaultValidatorFactory().getValidator(),
            mock(EditorialSemanticValidator.class),
            executor());

    assertThatThrownBy(() -> consumer.draft("event")).hasMessageContaining("provider_refusal");
    verify(audit).fail(any(), any(AiProviderException.class), any());
    verify(events, never()).enqueue(any(), any(), any(), any(), any(), any());
    verify(processed, never()).markProcessed(any(), any());
  }

  @Test
  void unsafeSourceMaterialStopsBeforeEditorialAnalysis() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var events = mock(DurableEventPublisher.class);
    var analysis = mock(EditorialAnalysisProvider.class);
    var drafts = mock(ArticleDraftProvider.class);
    var safety = mock(ContentSafetyProvider.class);
    var requests = mock(AiRequestAuditService.class);
    var metrics = new AiUsageMetrics(new SimpleMeterRegistry(), BigDecimal.ZERO, BigDecimal.ZERO);
    UUID storyId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    var source =
        new SourceReference(
            sourceId,
            "publisher",
            "1900000000000000000",
            "https://x.com/publisher/status/1900000000000000000",
            Instant.now());
    var payload = new StoryAnalysisRequested(storyId, List.of(source), "unsafe source");
    var event =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "StoryAnalysisRequested",
            1,
            storyId,
            storyId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "analysis:" + storyId,
            payload);
    when(reader.eventType("event")).thenReturn("StoryAnalysisRequested");
    when(reader.read("event", StoryAnalysisRequested.class)).thenReturn(event);
    when(safety.evaluate(any(), any()))
        .thenReturn(new SafetyResult(false, List.of("unsafe-content")));
    var consumer =
        new EditorialWorkflowConsumer(
            reader,
            processed,
            events,
            analysis,
            drafts,
            safety,
            requests,
            metrics,
            Validation.buildDefaultValidatorFactory().getValidator(),
            mock(EditorialSemanticValidator.class),
            executor());

    consumer.analyze("event");

    verify(analysis, never()).analyze(any(), any());
    var blocked = ArgumentCaptor.forClass(StoryAnalysisBlocked.class);
    verify(events)
        .enqueue(
            org.mockito.ArgumentMatchers.eq("StoryAnalysisBlocked"),
            org.mockito.ArgumentMatchers.eq(storyId),
            org.mockito.ArgumentMatchers.eq(event.correlationId()),
            org.mockito.ArgumentMatchers.eq(event.eventId()),
            org.mockito.ArgumentMatchers.eq("story-analysis-blocked:" + storyId),
            blocked.capture());
    assertThat(blocked.getValue().safetyFlags()).containsExactly("unsafe-content");
    verify(requests).complete(any(), any(SafetyResult.class));
    verify(processed).markProcessed(event.eventId(), "story-analysis-v1");
  }

  private static AiProviderCallExecutor executor() {
    return new AiProviderCallExecutor(
        new SimpleMeterRegistry(),
        1,
        1,
        Duration.ZERO,
        Duration.ZERO,
        5,
        Duration.ofMinutes(1),
        ignored -> {});
  }

  private static EventEnvelope<ArticleDraftRequested> draftRequest() {
    UUID storyId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    var source =
        new SourceReference(
            sourceId, "account", "post", "https://example.test/post", Instant.now());
    var payload =
        new ArticleDraftRequested(
            storyId,
            List.of(source),
            "analysis",
            List.of(new Claim("claim", "REPORTED", List.of(sourceId))),
            new BigDecimal("0.7"),
            List.of("single source"));
    return new EventEnvelope<>(
        UUID.randomUUID(),
        "ArticleDraftRequested",
        1,
        storyId,
        UUID.randomUUID(),
        null,
        Instant.now(),
        "test",
        Map.of(),
        "draft:" + storyId,
        payload);
  }

  private static ArticleDraftGenerated invalidDraft(ArticleDraftRequested request) {
    return new ArticleDraftGenerated(
        request.storyCandidateId(),
        "",
        "Summary",
        "Body",
        "Context",
        "SEO title",
        "SEO description",
        "slug",
        Set.of("news"),
        "general",
        request.sources(),
        request.claims(),
        request.confidence(),
        request.warnings(),
        List.of(),
        true,
        "Illustration",
        "Alt text",
        "Preview",
        "provider",
        "model",
        "v1",
        Instant.now());
  }

  private static ArticleDraftGenerated validDraft(
      ArticleDraftRequested request, List<String> warnings, List<String> safetyFlags) {
    return new ArticleDraftGenerated(
        request.storyCandidateId(),
        "Headline",
        "Summary",
        "Body",
        "Context",
        "SEO title",
        "SEO description",
        "slug",
        Set.of("news"),
        "general",
        request.sources(),
        request.claims(),
        request.confidence(),
        warnings,
        safetyFlags,
        true,
        "Illustration",
        "Alt text",
        "Preview",
        "provider",
        "model",
        "v1",
        Instant.now());
  }
}
