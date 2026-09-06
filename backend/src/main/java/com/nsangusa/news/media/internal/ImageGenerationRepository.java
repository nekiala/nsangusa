package com.nsangusa.news.media.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ImageGenerationRepository extends JpaRepository<ImageGeneration, UUID> {
  List<ImageGeneration> findByArticleIdOrderByCreatedAtDesc(UUID articleId);

  boolean existsByArticleIdAndSafetyStatus(UUID articleId, String safetyStatus);

  boolean existsByArticleId(UUID articleId);
}

interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {}
