package com.nsangusa.news.search.internal;

import com.nsangusa.news.articles.ArticleVisibilityChanged;
import com.nsangusa.news.search.SearchService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class SearchVisibilityListener {
  private final SearchService search;

  SearchVisibilityListener(SearchService search) {
    this.search = search;
  }

  @EventListener
  void changed(ArticleVisibilityChanged event) {
    search.indexPublished(event.articleId());
  }
}
