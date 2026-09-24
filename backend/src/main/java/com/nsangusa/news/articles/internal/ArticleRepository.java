package com.nsangusa.news.articles.internal;

import com.nsangusa.news.articles.ArticleState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ArticleRepository extends JpaRepository<Article, UUID> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select article from Article article where article.id = :id")
  Optional<Article> findLockedById(UUID id);

  @EntityGraph(attributePaths = "sources")
  Optional<Article> findBySlugAndState(String slug, ArticleState state);

  @EntityGraph(attributePaths = "sources")
  List<Article> findByStateOrderByPublishedAtDesc(ArticleState state, Pageable pageable);

  Page<Article> findByState(ArticleState state, Pageable pageable);

  @Query(
      value =
          """
          select id, slug, headline, summary, topic, tags,
                 published_at as publishedAt, updated_at as updatedAt
          from articles where state = 'PUBLISHED' and published_at is not null
          order by published_at desc, id asc
          """,
      countQuery =
          "select count(*) from articles where state = 'PUBLISHED' and published_at is not null",
      nativeQuery = true)
  Page<PublicEntry> findPublished(Pageable pageable);

  @Query(
      value =
          """
          select candidate.id, candidate.slug, candidate.headline, candidate.summary,
                 candidate.topic, candidate.tags,
                 candidate.published_at as publishedAt, candidate.updated_at as updatedAt
          from articles candidate
          join articles current on current.slug = :slug
          cross join lateral (
            select count(distinct lower(btrim(tag))) as matches
            from unnest(string_to_array(candidate.tags, ',')) tag
            where lower(btrim(tag)) <> '' and lower(btrim(tag)) in (
              select lower(btrim(value)) from unnest(string_to_array(current.tags, ',')) value
            )
          ) shared
          where candidate.state = 'PUBLISHED' and current.state = 'PUBLISHED'
            and candidate.published_at is not null and current.published_at is not null
            and candidate.id <> current.id
            and (lower(btrim(candidate.topic)) = lower(btrim(current.topic)) or shared.matches > 0)
          order by (lower(btrim(candidate.topic)) = lower(btrim(current.topic))) desc,
                   shared.matches desc, candidate.published_at desc, candidate.id asc
          limit :limit
          """,
      nativeQuery = true)
  List<PublicEntry> findRelated(String slug, int limit);

  interface PublicEntry {
    UUID getId();

    String getSlug();

    String getHeadline();

    String getSummary();

    String getTopic();

    String getTags();

    java.time.Instant getPublishedAt();

    java.time.Instant getUpdatedAt();
  }
}
