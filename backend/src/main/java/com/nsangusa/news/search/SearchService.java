package com.nsangusa.news.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SearchService {
  /** Results carrying {@code language}'s headline and summary where a translation exists. */
  SearchPage search(String query, int page, int size, String language);

  default SearchPage search(String query, int page, int size) {
    return search(query, page, size, null);
  }

  /** Results carrying {@code language}'s headline and summary where a translation exists. */
  SearchPage byTopic(String topic, int page, int size, String language);

  default SearchPage byTopic(String topic, int page, int size) {
    return byTopic(topic, page, size, null);
  }

  /** Results carrying {@code language}'s headline and summary where a translation exists. */
  SearchPage byTag(String tag, int page, int size, String language);

  default SearchPage byTag(String tag, int page, int size) {
    return byTag(tag, page, size, null);
  }

  List<Facet> topics();

  List<Facet> tags();

  void indexPublished(UUID articleId);

  void removeUnpublished(UUID articleId);

  record SearchPage(List<SearchResult> items, int page, int size, long total) {}

  record SearchResult(
      UUID articleId,
      String slug,
      String headline,
      String summary,
      String topic,
      List<String> tags,
      Instant publishedAt,
      Instant updatedAt,
      double rank,
      boolean hasImage) {}

  record Facet(String value, long articleCount) {}
}
