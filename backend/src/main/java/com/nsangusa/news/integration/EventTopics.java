package com.nsangusa.news.integration;

public final class EventTopics {
  public static final String INGESTION = "news.ingestion.v1";
  public static final String EDITORIAL = "news.editorial.v1";
  public static final String PUBLICATION = "news.publication.v1";
  public static final String NOTIFICATIONS = "news.notifications.v1";

  /** Delayed retries for one failed consumer group; see docs/events.md. */
  public static final String RETRY_SUFFIX = ".retry";

  public static final String DLT_SUFFIX = ".dlt";

  /** A listener's retry twin consumes {@code <topic>.retry} as {@code <group>-retry}. */
  public static final String RETRY_GROUP_SUFFIX = "-retry";

  public static final String INGESTION_RETRY = INGESTION + RETRY_SUFFIX;
  public static final String EDITORIAL_RETRY = EDITORIAL + RETRY_SUFFIX;
  public static final String PUBLICATION_RETRY = PUBLICATION + RETRY_SUFFIX;
  public static final String NOTIFICATIONS_RETRY = NOTIFICATIONS + RETRY_SUFFIX;

  private EventTopics() {}

  public static String forType(String eventType) {
    return EventCatalog.require(eventType).topic();
  }
}
