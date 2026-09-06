package com.nsangusa.news.search.internal;

import com.nsangusa.news.articles.ArticleService.ArticleView;
import com.nsangusa.news.search.SearchService.Facet;
import com.nsangusa.news.search.SearchService.SearchPage;
import java.util.List;
import java.util.UUID;

interface SearchDocumentStore {
  SearchPage search(String query, int page, int size);

  SearchPage byTopic(String topic, int page, int size);

  SearchPage byTag(String tag, int page, int size);

  List<Facet> topics();

  List<Facet> tags();

  void upsert(ArticleView article);

  void delete(UUID articleId);
}
