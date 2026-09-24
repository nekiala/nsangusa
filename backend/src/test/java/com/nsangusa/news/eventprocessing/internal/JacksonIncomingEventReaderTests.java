package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.ArticleApproved;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JacksonIncomingEventReaderTests {
  private final ObjectMapper mapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final JacksonIncomingEventReader reader =
      new JacksonIncomingEventReader(
          mapper, Validation.buildDefaultValidatorFactory().getValidator());

  @Test
  void validatesKnownEventTypeVersionAndPayload() throws Exception {
    UUID articleId = UUID.randomUUID();
    var envelope =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "ArticleApproved",
            1,
            articleId,
            articleId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "approval:" + articleId,
            new ArticleApproved(articleId, UUID.randomUUID()));

    var decoded = reader.read(mapper.writeValueAsString(envelope), ArticleApproved.class);

    assertThat(decoded).isEqualTo(envelope);
  }

  @Test
  void rejectsUnsupportedSchemaVersionBeforeDispatch() throws Exception {
    UUID articleId = UUID.randomUUID();
    var envelope =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "ArticleApproved",
            2,
            articleId,
            articleId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "approval:" + articleId,
            new ArticleApproved(articleId, UUID.randomUUID()));

    assertThatThrownBy(() -> reader.eventType(mapper.writeValueAsString(envelope)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported schema version");
  }

  @Test
  void rejectsPayloadConstraintViolations() throws Exception {
    UUID articleId = UUID.randomUUID();
    var envelope =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "ArticleApproved",
            1,
            articleId,
            articleId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "approval:" + articleId,
            new ArticleApproved(null, null));

    assertThatThrownBy(
            () -> reader.read(mapper.writeValueAsString(envelope), ArticleApproved.class))
        .isInstanceOf(ConstraintViolationException.class);
  }

  @Test
  void rejectsPayloadTypeMismatch() throws Exception {
    UUID articleId = UUID.randomUUID();
    var envelope =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "ArticleApproved",
            1,
            articleId,
            articleId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "approval:" + articleId,
            new ArticleApproved(articleId, UUID.randomUUID()));

    assertThatThrownBy(() -> reader.read(mapper.writeValueAsString(envelope), Map.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires payload ArticleApproved");
  }

  @Test
  void doesNotCoerceInvalidSchemaVersionTypesIntoTheSupportedVersion() {
    for (String version : java.util.List.of("\"1\"", "1.9", "true", "null", "4294967297")) {
      assertThatThrownBy(
              () ->
                  reader.eventType(
                      "{\"eventType\":\"ArticleApproved\",\"schemaVersion\":" + version + "}"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsNoncanonicalUuidInvalidCalendarDateAndScalarCoercion() throws Exception {
    var envelope = publishedEnvelope();
    for (var invalid :
        Map.of(
                "eventId", "1-1-1-1-1",
                "timestamp", "2026-02-30T00:00:00Z")
            .entrySet()) {
      var mutated = envelope.deepCopy();
      mutated.put(invalid.getKey(), invalid.getValue());
      assertThatThrownBy(() -> reader.read(mutated.toString(), Object.class))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var trace = envelope.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) trace.required("traceContext"))
        .put("traceId", 42);
    assertThatThrownBy(() -> reader.read(trace.toString(), Object.class))
        .isInstanceOf(IllegalArgumentException.class);
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) envelope.required("payload");
    payload.put("headline", 42);
    assertThatThrownBy(() -> reader.read(envelope.toString(), Object.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiredPrimitiveMustBePresentAndBooleanWhileNullableLegacyFieldsRemainOptional()
      throws Exception {
    var envelope = publishedEnvelope();
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) envelope.required("payload");
    payload.remove("publishedBy");
    payload.remove("publisherType");
    assertThat(reader.read(envelope.toString(), Object.class).payload())
        .isInstanceOf(com.nsangusa.news.integration.NewsEvents.ArticlePublished.class);
    payload.remove("newsletterEligible");
    assertThatThrownBy(() -> reader.read(envelope.toString(), Object.class))
        .isInstanceOf(IllegalArgumentException.class);
    payload.put("newsletterEligible", "false");
    assertThatThrownBy(() -> reader.read(envelope.toString(), Object.class))
        .isInstanceOf(IllegalArgumentException.class);
    payload.putNull("newsletterEligible");
    assertThatThrownBy(() -> reader.read(envelope.toString(), Object.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicatePropertiesAndTrailingJsonEvenBeforeDispatch() {
    assertThatThrownBy(
            () ->
                reader.eventType(
                    "{\"eventType\":\"ArticleApproved\",\"schemaVersion\":1,\"schemaVersion\":2}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> reader.eventType("{\"eventType\":\"ArticleApproved\",\"schemaVersion\":1} {}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private com.fasterxml.jackson.databind.node.ObjectNode publishedEnvelope() throws Exception {
    UUID articleId = UUID.randomUUID();
    var event =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "ArticlePublished",
            1,
            articleId,
            articleId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "publish:" + articleId,
            new com.nsangusa.news.integration.NewsEvents.ArticlePublished(
                articleId, "synthetic", "Synthetic headline", Instant.now(), false));
    return (com.fasterxml.jackson.databind.node.ObjectNode)
        mapper.readTree(mapper.writeValueAsString(event));
  }
}
