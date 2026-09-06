package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.aieditorial.EditorialProviders.ArticleDraftProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ContentSafetyProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.EditorialAnalysisProvider;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftRequested;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EditorialWorkflowValidationTests {
  @Test
  void invalidStructuredProviderOutputIsRejectedBeforeOutboxAndIdempotencyMarker() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var events = mock(DurableEventPublisher.class);
    var analysis = mock(EditorialAnalysisProvider.class);
    var drafts = mock(ArticleDraftProvider.class);
    var safety = mock(ContentSafetyProvider.class);
    var requests = mock(AiRequestRepository.class);
    var event = draftRequest();
    when(reader.eventType("event")).thenReturn("ArticleDraftRequested");
    when(reader.read("event", ArticleDraftRequested.class)).thenReturn(event);
    when(requests.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(drafts.draft(any())).thenReturn(invalidDraft(event.payload()));
    var validator = Validation.buildDefaultValidatorFactory().getValidator();
    var consumer =
        new EditorialWorkflowConsumer(
            reader, processed, events, analysis, drafts, safety, requests, validator);

    assertThatThrownBy(() -> consumer.draft("event"))
        .isInstanceOf(ConstraintViolationException.class)
        .hasMessageContaining("AI draft did not satisfy the contract");
    verify(events, never()).enqueue(any(), any(), any(), any(), any(), any());
    verify(processed, never()).markProcessed(any(), any());
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
}
