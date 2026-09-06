package com.nsangusa.news.eventprocessing.internal;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.integration.EventEnvelope;
import org.springframework.stereotype.Component;

@Component
class JacksonIncomingEventReader implements IncomingEventReader {
  private final ObjectMapper objectMapper;

  JacksonIncomingEventReader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String eventType(String json) {
    try {
      return objectMapper.readTree(json).required("eventType").asText();
    } catch (Exception exception) {
      throw new IllegalArgumentException("Invalid event envelope", exception);
    }
  }

  @Override
  public <T> EventEnvelope<T> read(String json, Class<T> payloadType) {
    try {
      JavaType type =
          objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
      return objectMapper.readValue(json, type);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Invalid event envelope", exception);
    }
  }
}
