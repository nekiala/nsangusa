package com.nsangusa.news.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SearchService {
  SearchPage search(String query, int page, int size);

  SearchPage byTopic(String topic, int page, int size);

  SearchPage byTag(String tag, int page, int size);

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
      double rank) {}

  record Facet(String value, long articleCount) {}
}
