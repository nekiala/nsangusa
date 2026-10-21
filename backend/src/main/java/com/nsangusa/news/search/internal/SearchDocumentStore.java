package com.nsangusa.news.search.internal;

import com.nsangusa.news.articles.ArticleService.ArticleView;
import com.nsangusa.news.search.SearchService.Facet;
import com.nsangusa.news.search.SearchService.SearchPage;
import java.util.List;
import java.util.UUID;

interface SearchDocumentStore {
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

  void upsert(ArticleView article);

  void delete(UUID articleId);
}
