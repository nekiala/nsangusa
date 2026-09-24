package com.nsangusa.news.search.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.search.SearchService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SearchApplicationService implements SearchService {
  private final ArticleService articles;
  private final SearchDocumentStore documents;

  SearchApplicationService(ArticleService articles, SearchDocumentStore documents) {
    this.articles = articles;
    this.documents = documents;
  }

  @Override
  @Transactional(readOnly = true)
  public SearchPage search(String query, int page, int size) {
    String normalized = required(query, "Search query");
    if (normalized.length() > 200) {
      throw new IllegalArgumentException("Search query is too long");
    }
    return documents.search(normalized, page(page), size(size));
  }

  @Override
  @Transactional(readOnly = true)
  public SearchPage byTopic(String topic, int page, int size) {
    return documents.byTopic(facet(topic), page(page), size(size));
  }

  @Override
  @Transactional(readOnly = true)
  public SearchPage byTag(String tag, int page, int size) {
    return documents.byTag(facet(tag), page(page), size(size));
  }

  @Override
  @Transactional(readOnly = true)
  public List<Facet> topics() {
    return documents.topics();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Facet> tags() {
    return documents.tags();
  }

  @Override
  @Transactional
  public void indexPublished(UUID articleId) {
    var article = articles.getLocked(articleId);
    if (article.state() == ArticleState.PUBLISHED && article.publishedAt() != null) {
      documents.upsert(article);
    } else {
      documents.delete(articleId);
    }
  }

  @Override
  @Transactional
  public void removeUnpublished(UUID articleId) {
    indexPublished(articleId);
  }

  private static int page(int page) {
    if (page < 0 || page > 10_000) {
      throw new IllegalArgumentException("Invalid page");
    }
    return page;
  }

  private static int size(int size) {
    if (size < 1 || size > 100) {
      throw new IllegalArgumentException("Size must be between 1 and 100");
    }
    return size;
  }

  private static String facet(String value) {
    String normalized = required(value, "Facet").toLowerCase(Locale.ROOT);
    if (normalized.length() > 100) {
      throw new IllegalArgumentException("Facet is too long");
    }
    return normalized;
  }

  private static String required(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}
