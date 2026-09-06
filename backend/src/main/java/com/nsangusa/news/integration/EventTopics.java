package com.nsangusa.news.integration;

import static java.util.Map.entry;

import java.util.Map;

public final class EventTopics {
  public static final String INGESTION = "news.ingestion.v1";
  public static final String EDITORIAL = "news.editorial.v1";
  public static final String PUBLICATION = "news.publication.v1";
  public static final String NOTIFICATIONS = "news.notifications.v1";

  private static final Map<String, String> TOPICS =
      Map.ofEntries(
          entry("XPostDiscovered", INGESTION),
          entry("XPostNormalized", INGESTION),
          entry("StoryAnalysisRequested", EDITORIAL),
          entry("ArticleDraftRequested", EDITORIAL),
          entry("ArticleDraftGenerated", EDITORIAL),
          entry("ArticleImageRequested", EDITORIAL),
          entry("ArticleImageGenerated", EDITORIAL),
          entry("ArticleReadyForReview", EDITORIAL),
          entry("ArticleApproved", PUBLICATION),
          entry("ArticlePublished", PUBLICATION),
          entry("ArticleUnpublished", PUBLICATION),
          entry("NewsletterDispatchRequested", NOTIFICATIONS),
          entry("CommentSubmitted", NOTIFICATIONS));

  private EventTopics() {}

  public static String forType(String eventType) {
    var topic = TOPICS.get(eventType);
    if (topic == null) {
      throw new IllegalArgumentException("No Kafka topic configured for event type " + eventType);
    }
    return topic;
  }
}
