package com.nsangusa.news.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.search.SearchService;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@Import({ArticleApplicationService.class, ArticlePublicDiscoveryTests.SearchConfiguration.class})
class ArticlePublicDiscoveryTests {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired ArticleRepository repository;
  @Autowired ArticleService articles;
  @Autowired SearchService search;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired jakarta.persistence.EntityManager entityManager;
  @MockitoBean DurableEventPublisher events;
  @MockitoBean AuditService audit;
  @MockitoBean SourceIngestionService sources;

  @Test
  void jsonContentRoundTripsWhileLegacyReadsDoNotBackfillStoredContent() {
    var legacy = seed(201, "Ideas", "Legacy");
    var structured = seed(202, "Ideas", "Structured");
    var content =
        new com.nsangusa.news.articles.ArticleContent(
            1,
            List.of(
                new com.nsangusa.news.articles.ArticleContent.Block(
                    "heading", "Stored heading", null, null),
                new com.nsangusa.news.articles.ArticleContent.Block(
                    "ordered_list", null, List.of("One", "Two"), null)));
    structured.content = content;
    structured.body = content.plainText();
    repository.flush();
    entityManager.clear();

    var view = articles.getPublishedBySlug(structured.slug);
    assertThat(view.content()).isEqualTo(content);
    assertThat(view.body()).isEqualTo("Stored heading\n\nOne\nTwo");
    assertThat(articles.getPublishedBySlug(legacy.slug).content().blocks())
        .extracting(com.nsangusa.news.articles.ArticleContent.Block::type)
        .containsExactly("paragraph");
    assertThat(
            jdbc.queryForObject(
                "select content::text from articles where id = ?", String.class, legacy.id))
        .isNull();
  }

  @Test
  void publishedPagesTraverseMoreThanTwentyWithoutLoadingArticleInteriors() throws Exception {
    var expected =
        IntStream.rangeClosed(1, 27).mapToObj(i -> seed(i, "Science", "Shared")).toList();
    var hidden = seed(100, "Science", "Shared");
    hidden.state = ArticleState.DRAFTING;
    repository.flush();

    var first = articles.published(0, 20);
    var second = articles.published(1, 20);
    assertThat(first.total()).isEqualTo(27);
    assertThat(first.items()).hasSize(20);
    assertThat(second.items()).hasSize(7);
    assertThat(first.items())
        .extracting(ArticleService.ArticleSummary::id)
        .containsExactlyElementsOf(expected.subList(0, 20).stream().map(a -> a.id).toList());
    assertThat(second.items())
        .extracting(ArticleService.ArticleSummary::id)
        .containsExactlyElementsOf(expected.subList(20, 27).stream().map(a -> a.id).toList());
    assertThat(articles.published(2, 20).items()).isEmpty();
    assertThat(mapper.readTree(mapper.writeValueAsString(first)).path("items").get(0).has("body"))
        .isFalse();
    assertThat(first.items().getFirst().updatedAt()).isEqualTo(expected.getFirst().updatedAt);
  }

  @Test
  void searchFacetsAndAllPublicSurfacesWithdrawImmediatelyAndIgnoreStaleDocuments() {
    var seeded = IntStream.rangeClosed(1, 27).mapToObj(i -> seed(i, "Science", "Shared")).toList();
    repository.flush();
    seeded.forEach(article -> search.indexPublished(article.id));
    var first = search.search("archive", 0, 20);
    var second = search.search("archive", 1, 20);
    assertThat(first.total()).isEqualTo(27);
    assertThat(first.items()).hasSize(20);
    assertThat(second.items()).hasSize(7);
    assertThat(second.items())
        .extracting(SearchService.SearchResult::articleId)
        .containsExactlyElementsOf(seeded.subList(20, 27).stream().map(a -> a.id).toList());
    assertThat(first.items().getFirst().updatedAt()).isEqualTo(seeded.getFirst().updatedAt);

    articles.unpublish(seeded.getFirst().id, UUID.randomUUID());
    assertThat(articles.published(0, 20).total()).isEqualTo(26);
    assertThat(search.search("archive", 0, 20).total()).isEqualTo(26);
    assertThatThrownBy(() -> articles.getPublishedBySlug(seeded.getFirst().slug))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(articles.related(seeded.get(1).slug, 20))
        .extracting(ArticleService.ArticleSummary::id)
        .doesNotContain(seeded.getFirst().id);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from article_search_documents where article_id = ?",
                Long.class,
                seeded.getFirst().id))
        .isZero();

    jdbc.update("update articles set state = 'UNPUBLISHED' where id = ?", seeded.getLast().id);
    assertThat(search.search("archive", 0, 100).total()).isEqualTo(25);
    assertThat(search.byTopic(" SCIENCE ", 1, 20).items()).hasSize(5);
    assertThat(search.byTag("shared", 1, 20).items()).hasSize(5);
    assertThat(search.topics()).containsExactly(new SearchService.Facet("science", 25));
    assertThat(search.tags()).containsExactly(new SearchService.Facet("shared", 25));
  }

  @Test
  void relatedArticlesUseTopicAndWholeNormalizedTagsAndExcludeSelfAndNonpublicStates() {
    var current = seed(1, "Science", "Trust, Shared");
    var sameTopic = seed(2, "science", "Other");
    var sharedTag = seed(3, "Culture", " shared ");
    seed(4, "Cities", "Unrelated");
    seed(5, "Culture", "Sharedness");
    for (var state :
        List.of(ArticleState.DRAFTING, ArticleState.ARCHIVED, ArticleState.UNPUBLISHED)) {
      var hidden = seed(10 + state.ordinal(), "Science", "Shared");
      hidden.state = state;
    }
    repository.flush();
    assertThat(articles.related(current.slug, 20))
        .extracting(ArticleService.ArticleSummary::id)
        .containsExactly(sameTopic.id, sharedTag.id);
    assertThat(articles.related(current.slug, 1))
        .extracting(ArticleService.ArticleSummary::id)
        .containsExactly(sameTopic.id);
    articles.unpublish(current.id, UUID.randomUUID());
    assertThatThrownBy(() -> articles.related(current.slug, 3))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void validatesEveryPublicPageAndPreservesNoStoreResponses() {
    for (int page : List.of(-1, 50_000)) {
      assertThatThrownBy(() -> articles.published(page, 20))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (int size : List.of(0, 101)) {
      assertThatThrownBy(() -> articles.published(0, size))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> articles.related("unknown", 21))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> search.byTag("shared", 10_001, 20))
        .isInstanceOf(IllegalArgumentException.class);
    var controller = new ArticleController(articles, null, null);
    assertThat(controller.published(0, 20, null).getHeaders().getCacheControl())
        .isEqualTo("no-store");
  }

  private Article seed(int sequence, String topic, String tags) {
    var article = new Article();
    article.id = new UUID(0, sequence);
    article.slug = "archive-" + sequence;
    article.headline = "Archive reporting";
    article.summary = "Published archive summary.";
    article.body = "Article interiors are never returned by discovery.";
    article.seoTitle = article.headline;
    article.seoDescription = article.summary;
    article.topic = topic;
    article.tags = tags;
    article.state = ArticleState.PUBLISHED;
    article.confidence = BigDecimal.ONE;
    article.warnings = "";
    article.createdAt = Instant.parse("2026-09-01T12:00:00Z");
    article.publishedAt = article.createdAt;
    article.updatedAt = article.createdAt.plusSeconds(3600);
    return repository.saveAndFlush(article);
  }

  @TestConfiguration
  @ComponentScan(
      basePackages = "com.nsangusa.news.search.internal",
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.REGEX,
              pattern =
                  ".*(SearchApplicationService|JdbcSearchDocumentStore|SearchVisibilityListener)"))
  static class SearchConfiguration {
    @Bean
    org.springframework.cache.CacheManager cacheManager() {
      return new org.springframework.cache.concurrent.ConcurrentMapCacheManager();
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }
  }
}
