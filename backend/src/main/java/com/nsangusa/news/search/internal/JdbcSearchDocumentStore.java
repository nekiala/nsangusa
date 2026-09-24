package com.nsangusa.news.search.internal;

import com.nsangusa.news.articles.ArticleService.ArticleView;
import com.nsangusa.news.search.SearchService.Facet;
import com.nsangusa.news.search.SearchService.SearchPage;
import com.nsangusa.news.search.SearchService.SearchResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSearchDocumentStore implements SearchDocumentStore {
  private static final String RESULT_COLUMNS =
      """
      select d.article_id, d.slug, d.headline, d.summary, d.topic, d.tags,
             d.published_at, d.updated_at, %s as rank
      from article_search_documents d
      join articles a on a.id = d.article_id and a.state = 'PUBLISHED'
        and a.published_at is not null
      %s
      order by %s
      limit :limit offset :offset
      """;

  private final JdbcClient jdbc;
  private static final String VISIBLE_DOCUMENTS =
      """
      from article_search_documents d
      join articles a on a.id = d.article_id and a.state = 'PUBLISHED'
        and a.published_at is not null
      """;

  JdbcSearchDocumentStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public SearchPage search(String query, int page, int size) {
    String predicate = "where search_vector @@ websearch_to_tsquery('english', :query)";
    long total =
        jdbc.sql("select count(*) " + VISIBLE_DOCUMENTS + predicate)
            .param("query", query)
            .query(Long.class)
            .single();
    String sql =
        RESULT_COLUMNS.formatted(
            "ts_rank_cd(search_vector, websearch_to_tsquery('english', :query))",
            predicate,
            "rank desc, d.published_at desc, d.article_id asc");
    List<SearchResult> items =
        jdbc.sql(sql)
            .param("query", query)
            .param("limit", size)
            .param("offset", page * size)
            .query(this::result)
            .list();
    return new SearchPage(items, page, size, total);
  }

  @Override
  public SearchPage byTopic(String topic, int page, int size) {
    return facetPage("d.topic = :value", topic, page, size);
  }

  @Override
  public SearchPage byTag(String tag, int page, int size) {
    return facetPage(":value = any(string_to_array(d.tags, ','))", tag, page, size);
  }

  @Override
  public List<Facet> topics() {
    return jdbc.sql(
            """
            select d.topic as value, count(*) as article_count
            %s
            group by d.topic
            order by article_count desc, value
            """
                .formatted(VISIBLE_DOCUMENTS))
        .query((rs, row) -> new Facet(rs.getString("value"), rs.getLong("article_count")))
        .list();
  }

  @Override
  public List<Facet> tags() {
    return jdbc.sql(
            """
            select tag as value, count(distinct d.article_id) as article_count
            %s
            cross join lateral unnest(string_to_array(d.tags, ',')) as tag
            where tag <> ''
            group by tag
            order by article_count desc, value
            """
                .formatted(VISIBLE_DOCUMENTS))
        .query((rs, row) -> new Facet(rs.getString("value"), rs.getLong("article_count")))
        .list();
  }

  @Override
  public void upsert(ArticleView article) {
    jdbc.sql(
            """
            insert into article_search_documents
              (article_id, slug, headline, summary, body, topic, tags, published_at, updated_at)
            values
              (:articleId, :slug, :headline, :summary, :body, :topic, :tags, :publishedAt, :updatedAt)
            on conflict (article_id) do update set
              slug = excluded.slug,
              headline = excluded.headline,
              summary = excluded.summary,
              body = excluded.body,
              topic = excluded.topic,
              tags = excluded.tags,
              published_at = excluded.published_at,
              updated_at = excluded.updated_at
            """)
        .param("articleId", article.id())
        .param("slug", article.slug())
        .param("headline", article.headline())
        .param("summary", article.summary())
        .param("body", article.body())
        .param("topic", normalize(article.topic()))
        .param(
            "tags",
            article.tags().stream()
                .map(JdbcSearchDocumentStore::normalize)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(",")))
        .param("publishedAt", java.sql.Timestamp.from(article.publishedAt()))
        .param("updatedAt", java.sql.Timestamp.from(article.updatedAt()))
        .update();
  }

  @Override
  public void delete(UUID articleId) {
    jdbc.sql("delete from article_search_documents where article_id = :articleId")
        .param("articleId", articleId)
        .update();
  }

  private SearchPage facetPage(String predicate, String value, int page, int size) {
    long total =
        jdbc.sql("select count(*) " + VISIBLE_DOCUMENTS + "where " + predicate)
            .param("value", value)
            .query(Long.class)
            .single();
    String sql =
        RESULT_COLUMNS.formatted(
            "0.0", "where " + predicate, "d.published_at desc, d.article_id asc");
    List<SearchResult> items =
        jdbc.sql(sql)
            .param("value", value)
            .param("limit", size)
            .param("offset", page * size)
            .query(this::result)
            .list();
    return new SearchPage(items, page, size, total);
  }

  private SearchResult result(ResultSet rs, int row) throws SQLException {
    String tags = rs.getString("tags");
    return new SearchResult(
        rs.getObject("article_id", UUID.class),
        rs.getString("slug"),
        rs.getString("headline"),
        rs.getString("summary"),
        rs.getString("topic"),
        tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(",")),
        rs.getTimestamp("published_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getDouble("rank"));
  }

  private static String normalize(String value) {
    return value.trim().toLowerCase(java.util.Locale.ROOT);
  }
}
