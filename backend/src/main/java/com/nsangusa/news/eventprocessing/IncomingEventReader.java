package com.nsangusa.news.eventprocessing;

import com.nsangusa.news.integration.EventEnvelope;

public interface IncomingEventReader {
  String eventType(String json);

  <T> EventEnvelope<T> read(String json, Class<T> payloadType);
}
