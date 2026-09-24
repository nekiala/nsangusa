package com.nsangusa.news.integration;

import static java.util.Map.entry;

import com.nsangusa.news.integration.NewsEvents.ArticleApproved;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftRequested;
import com.nsangusa.news.integration.NewsEvents.ArticleImageApproved;
import com.nsangusa.news.integration.NewsEvents.ArticleImageCandidateGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleImageGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleImageRequested;
import com.nsangusa.news.integration.NewsEvents.ArticlePublished;
import com.nsangusa.news.integration.NewsEvents.ArticleReadyForReview;
import com.nsangusa.news.integration.NewsEvents.ArticleUnpublished;
import com.nsangusa.news.integration.NewsEvents.CommentSubmitted;
import com.nsangusa.news.integration.NewsEvents.NewsletterDispatchRequested;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisBlocked;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisRequested;
import com.nsangusa.news.integration.NewsEvents.XPostDiscovered;
import com.nsangusa.news.integration.NewsEvents.XPostNormalized;
import java.util.Map;

public final class EventCatalog {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  private static final Map<String, Definition> EVENTS =
      Map.ofEntries(
          entry("XPostDiscovered", definition(EventTopics.INGESTION, XPostDiscovered.class)),
          entry("XPostNormalized", definition(EventTopics.INGESTION, XPostNormalized.class)),
          entry(
              "StoryAnalysisRequested",
              definition(EventTopics.EDITORIAL, StoryAnalysisRequested.class)),
          entry(
              "StoryAnalysisBlocked",
              definition(EventTopics.EDITORIAL, StoryAnalysisBlocked.class)),
          entry(
              "ArticleDraftRequested",
              definition(EventTopics.EDITORIAL, ArticleDraftRequested.class)),
          entry(
              "ArticleDraftGenerated",
              definition(EventTopics.EDITORIAL, ArticleDraftGenerated.class)),
          entry(
              "ArticleImageRequested",
              definition(EventTopics.EDITORIAL, ArticleImageRequested.class)),
          entry(
              "ArticleImageGenerated",
              definition(EventTopics.EDITORIAL, ArticleImageGenerated.class)),
          entry(
              "ArticleImageCandidateGenerated",
              definition(EventTopics.EDITORIAL, ArticleImageCandidateGenerated.class)),
          entry(
              "ArticleImageApproved",
              definition(EventTopics.EDITORIAL, ArticleImageApproved.class)),
          entry(
              "ArticleReadyForReview",
              definition(EventTopics.EDITORIAL, ArticleReadyForReview.class)),
          entry("ArticleApproved", definition(EventTopics.PUBLICATION, ArticleApproved.class)),
          entry("ArticlePublished", definition(EventTopics.PUBLICATION, ArticlePublished.class)),
          entry(
              "ArticleUnpublished", definition(EventTopics.PUBLICATION, ArticleUnpublished.class)),
          entry(
              "NewsletterDispatchRequested",
              definition(EventTopics.NOTIFICATIONS, NewsletterDispatchRequested.class)),
          entry("CommentSubmitted", definition(EventTopics.NOTIFICATIONS, CommentSubmitted.class)));

  private EventCatalog() {}

  public static Definition require(String eventType) {
    Definition definition = EVENTS.get(eventType);
    if (definition == null) {
      throw new IllegalArgumentException("Unsupported event type " + eventType);
    }
    return definition;
  }

  public static void requirePayload(String eventType, Class<?> payloadType) {
    Definition definition = require(eventType);
    if (!definition.payloadType().equals(payloadType)) {
      throw new IllegalArgumentException(
          "Event type "
              + eventType
              + " requires payload "
              + definition.payloadType().getSimpleName());
    }
  }

  private static Definition definition(String topic, Class<?> payloadType) {
    return new Definition(topic, payloadType, CURRENT_SCHEMA_VERSION);
  }

  public record Definition(String topic, Class<?> payloadType, int schemaVersion) {}
}
