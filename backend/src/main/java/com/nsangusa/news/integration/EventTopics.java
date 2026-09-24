package com.nsangusa.news.integration;

public final class EventTopics {
  public static final String INGESTION = "news.ingestion.v1";
  public static final String EDITORIAL = "news.editorial.v1";
  public static final String PUBLICATION = "news.publication.v1";
  public static final String NOTIFICATIONS = "news.notifications.v1";

  private EventTopics() {}

  public static String forType(String eventType) {
    return EventCatalog.require(eventType).topic();
  }
}
