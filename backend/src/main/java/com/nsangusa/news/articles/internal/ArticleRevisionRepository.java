package com.nsangusa.news.articles.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ArticleRevisionRepository extends JpaRepository<ArticleRevision, UUID> {
  Page<ArticleRevision> findByArticleId(UUID articleId, Pageable pageable);

  Optional<ArticleRevision> findByArticleIdAndRevisionNumber(UUID articleId, int revisionNumber);
}
