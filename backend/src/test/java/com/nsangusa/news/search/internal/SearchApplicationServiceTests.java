package com.nsangusa.news.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.search.SearchService.SearchPage;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchApplicationServiceTests {
  @Mock ArticleService articles;
  @Mock SearchDocumentStore documents;

  @Test
  void normalizesFacetsAndBoundsPagination() {
    var service = new SearchApplicationService(articles, documents);
    var page = new SearchPage(List.of(), 0, 20, 0);
    when(documents.byTag("breaking news", 0, 20)).thenReturn(page);

    assertThat(service.byTag("  Breaking News ", 0, 20)).isSameAs(page);
    assertThatThrownBy(() -> service.search("query", -1, 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.search("query", 0, 101))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void indexesPublishedArticlesThroughThePublicArticleApi() {
    UUID articleId = UUID.randomUUID();
    var article = article(articleId, ArticleState.PUBLISHED, Instant.now());
    when(articles.get(articleId)).thenReturn(article);

    new SearchApplicationService(articles, documents).indexPublished(articleId);

    verify(documents).upsert(article);
  }

  @Test
  void removesAnArticleWhenAVisibilityEventFindsItNoLongerPublished() {
    UUID articleId = UUID.randomUUID();
    when(articles.get(articleId))
        .thenReturn(article(articleId, ArticleState.UNPUBLISHED, Instant.now()));

    new SearchApplicationService(articles, documents).indexPublished(articleId);

    verify(documents).delete(articleId);
  }

  private static ArticleService.ArticleView article(
      UUID id, ArticleState state, Instant publishedAt) {
    return new ArticleService.ArticleView(
        id,
        "headline-" + id.toString().substring(0, 8),
        "Headline",
        "Summary",
        "Body",
        null,
        "World",
        Set.of("Breaking", "News"),
        state,
        null,
        null,
        false,
        true,
        publishedAt,
        Instant.now(),
        1,
        List.of(),
        List.of(),
        0.9);
  }
}
