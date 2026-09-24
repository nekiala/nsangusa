package com.nsangusa.news.media.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ImageGenerationRepository extends JpaRepository<ImageGeneration, UUID> {
  Optional<ImageGeneration> findFirstByArticleIdAndObjectKeyOrderByCreatedAtDesc(
      UUID articleId, String objectKey);

  List<ImageGeneration> findByArticleIdOrderByCreatedAtDesc(UUID articleId);

  Optional<ImageGeneration> findFirstByObjectKeyOrderByCreatedAtDesc(String objectKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select generation from ImageGeneration generation where generation.id = :id")
  Optional<ImageGeneration> findLockedById(UUID id);
}

interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {
  Optional<MediaAsset> findByGenerationIdAndVariantNameAndDeletedAtIsNull(
      UUID generationId, String variantName);
}
