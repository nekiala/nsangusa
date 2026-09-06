package com.nsangusa.news.articles.internal;

import com.nsangusa.news.articles.ArticleState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface ArticleRepository extends JpaRepository<Article, UUID> {
  @EntityGraph(attributePaths = "sources")
  Optional<Article> findBySlugAndState(String slug, ArticleState state);

  @EntityGraph(attributePaths = "sources")
  List<Article> findByStateOrderByPublishedAtDesc(ArticleState state, Pageable pageable);
}
