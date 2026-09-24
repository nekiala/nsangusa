package com.nsangusa.news.eventprocessing.internal;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.integration.EventCatalog;
import com.nsangusa.news.integration.EventEnvelope;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

@Component
class JacksonIncomingEventReader implements IncomingEventReader {
  private final ObjectMapper objectMapper;
  private final Validator validator;

  JacksonIncomingEventReader(ObjectMapper objectMapper, Validator validator) {
    this.objectMapper =
        objectMapper
            .copy()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            // Raw shape validation rejects null primitives without rejecting absent legacy counts.
            .disable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    this.validator = validator;
  }

  @Override
  public String eventType(String json) {
    try {
      var root = objectMapper.readTree(json);
      if (root == null
          || !root.isObject()
          || !root.path("eventType").isTextual()
          || !root.path("schemaVersion").isIntegralNumber()
          || !root.path("schemaVersion").canConvertToInt()) {
        throw new IllegalArgumentException("Invalid event envelope header");
      }
      String eventType = root.required("eventType").asText();
      int schemaVersion = root.required("schemaVersion").asInt();
      var definition = EventCatalog.require(eventType);
      if (schemaVersion != definition.schemaVersion()) {
        throw new IllegalArgumentException(
            "Unsupported schema version " + schemaVersion + " for event type " + eventType);
      }
      return eventType;
    } catch (Exception exception) {
      if (exception instanceof IllegalArgumentException illegalArgumentException) {
        throw illegalArgumentException;
      }
      throw new IllegalArgumentException("Invalid event envelope", exception);
    }
  }

  @Override
  public <T> EventEnvelope<T> read(String json, Class<T> payloadType) {
    try {
      String eventType = eventType(json);
      if (!Object.class.equals(payloadType)) {
        EventCatalog.requirePayload(eventType, payloadType);
      }
      JavaType type =
          objectMapper
              .getTypeFactory()
              .constructParametricType(
                  EventEnvelope.class, EventCatalog.require(eventType).payloadType());
      EventJsonShape.validate(objectMapper.readTree(json), type, objectMapper.getTypeFactory());
      EventEnvelope<T> envelope = objectMapper.readValue(json, type);
      var violations = validator.validate(envelope);
      if (!violations.isEmpty()) {
        throw new ConstraintViolationException("Event envelope violates its contract", violations);
      }
      return envelope;
    } catch (Exception exception) {
      if (exception instanceof ConstraintViolationException constraintViolationException) {
        throw constraintViolationException;
      }
      if (exception instanceof IllegalArgumentException illegalArgumentException) {
        throw illegalArgumentException;
      }
      throw new IllegalArgumentException("Invalid event envelope", exception);
    }
  }
}
