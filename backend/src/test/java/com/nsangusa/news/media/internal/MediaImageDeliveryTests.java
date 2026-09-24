package com.nsangusa.news.media.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.media.ObjectStorage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MediaImageDeliveryTests {
  private final ImageGenerationRepository generations = mock(ImageGenerationRepository.class);
  private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
  private final ObjectStorage storage = mock(ObjectStorage.class);
  private final DurableEventPublisher events = mock(DurableEventPublisher.class);
  private final AuditService audit = mock(AuditService.class);
  private final MediaApplicationService service =
      new MediaApplicationService(
          generations, assets, storage, events, audit, new ImageVariantProcessor());

  @Test
  void publicImageUsesTheSelectedGenerationNotTheNewestCandidate() {
    var selected = generation();
    selected.safetyStatus = "approved";
    var thumbnail = asset(selected, "thumbnail");
    when(generations.findFirstByObjectKeyOrderByCreatedAtDesc(selected.objectKey))
        .thenReturn(Optional.of(selected));
    when(assets.findByGenerationIdAndVariantNameAndDeletedAtIsNull(selected.id, "thumbnail"))
        .thenReturn(Optional.of(thumbnail));
    when(storage.read(thumbnail.objectKey))
        .thenReturn(Optional.of(new ObjectStorage.StoredObject(new byte[] {1, 2}, "image/png")));

    var content = service.image(selected.objectKey, "thumbnail");

    assertThat(content.bytes()).containsExactly((byte) 1, (byte) 2);
    assertThat(content.contentType()).isEqualTo("image/png");
    verify(assets).findByGenerationIdAndVariantNameAndDeletedAtIsNull(selected.id, "thumbnail");
    verify(storage).read(thumbnail.objectKey);
    verify(generations, org.mockito.Mockito.never())
        .findByArticleIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void editorialPreviewCanReadUnapprovedGenerationButPublicDeliveryCannot() {
    var generation = generation();
    var hero = asset(generation, "hero");
    when(generations.findById(generation.id)).thenReturn(Optional.of(generation));
    when(generations.findFirstByObjectKeyOrderByCreatedAtDesc(generation.objectKey))
        .thenReturn(Optional.of(generation));
    when(assets.findByGenerationIdAndVariantNameAndDeletedAtIsNull(generation.id, "hero"))
        .thenReturn(Optional.of(hero));
    when(storage.read(hero.objectKey))
        .thenReturn(Optional.of(new ObjectStorage.StoredObject(new byte[] {1}, "image/png")));

    assertThat(service.generationImage(generation.id, "hero").bytes()).containsExactly((byte) 1);
    assertStatus(() -> service.image(generation.objectKey, "hero"), HttpStatus.NOT_FOUND);
    assertThat(generation.safetyStatus).isEqualTo("review_required");
    assertThat(generation.approvedAt).isNull();
    verifyNoInteractions(events, audit);
  }

  @Test
  void noAssetAndMissingStoredObjectReturnNotFoundInsteadOfAFallback() {
    var generation = generation();
    when(generations.findById(generation.id)).thenReturn(Optional.of(generation));
    assertStatus(() -> service.generationImage(generation.id, "social"), HttpStatus.NOT_FOUND);
    verifyNoInteractions(storage);
    when(assets.findByGenerationIdAndVariantNameAndDeletedAtIsNull(generation.id, "hero"))
        .thenReturn(Optional.of(asset(generation, "hero")));
    assertStatus(() -> service.generationImage(generation.id, "hero"), HttpStatus.NOT_FOUND);
  }

  @Test
  void unrecognizedVariantAndMissingSelectionNeverReachStorage() {
    assertStatus(() -> service.image(null, "hero"), HttpStatus.NOT_FOUND);
    assertStatus(() -> service.image("articles/a/hero.png", "../social"), HttpStatus.BAD_REQUEST);
    assertStatus(
        () -> service.generationImage(UUID.randomUUID(), "file:///secret"), HttpStatus.BAD_REQUEST);
    verifyNoInteractions(generations, assets, storage);
  }

  @Test
  void anAssetForAnotherArticleCannotBeReturned() {
    var generation = generation();
    var unrelated = asset(generation(), "hero");
    when(generations.findById(generation.id)).thenReturn(Optional.of(generation));
    when(assets.findByGenerationIdAndVariantNameAndDeletedAtIsNull(generation.id, "hero"))
        .thenReturn(Optional.of(unrelated));

    assertStatus(() -> service.generationImage(generation.id, "hero"), HttpStatus.NOT_FOUND);
    verifyNoInteractions(storage);
  }

  @Test
  void anApprovedGenerationRemainsIdempotentForTheSameEditor() {
    var generation = generation();
    UUID editor = UUID.randomUUID();
    generation.approve(editor);
    when(generations.findLockedById(generation.id)).thenReturn(Optional.of(generation));

    service.approve(generation.id, editor);

    verifyNoInteractions(events, audit);
    assertThatThrownBy(() -> service.approve(generation.id, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void missingContentCannotBeApproved() {
    var generation = generation();
    when(generations.findLockedById(generation.id)).thenReturn(Optional.of(generation));

    assertStatus(() -> service.approve(generation.id, UUID.randomUUID()), HttpStatus.NOT_FOUND);

    assertThat(generation.safetyStatus).isEqualTo("review_required");
    verifyNoInteractions(storage, events, audit);
  }

  @Test
  void providerImageApprovalKeepsTheAiDisclosure() {
    var generation = generation();
    var hero = asset(generation, "hero");
    UUID actorId = UUID.randomUUID();
    when(generations.findLockedById(generation.id)).thenReturn(Optional.of(generation));
    when(assets.findByGenerationIdAndVariantNameAndDeletedAtIsNull(generation.id, "hero"))
        .thenReturn(Optional.of(hero));
    when(storage.read(hero.objectKey))
        .thenReturn(Optional.of(new ObjectStorage.StoredObject(new byte[] {1}, "image/png")));

    service.approve(generation.id, actorId);

    verify(events)
        .enqueue(
            "ArticleImageApproved",
            generation.articleId,
            generation.articleId,
            null,
            "article-image-approved:" + generation.id,
            new com.nsangusa.news.integration.NewsEvents.ArticleImageApproved(
                generation.articleId,
                generation.id,
                generation.objectKey,
                generation.altText,
                actorId,
                generation.approvedAt,
                true));
  }

  @Test
  void storedContentMustMatchThePersistedChecksum() {
    var generation = generation();
    var hero = asset(generation, "hero");
    hero.sha256 = StoredImages.sha256(new byte[] {1});
    when(generations.findById(generation.id)).thenReturn(Optional.of(generation));
    when(assets.findByGenerationIdAndVariantNameAndDeletedAtIsNull(generation.id, "hero"))
        .thenReturn(Optional.of(hero));
    when(storage.read(hero.objectKey))
        .thenReturn(Optional.of(new ObjectStorage.StoredObject(new byte[] {2}, "image/png")));

    assertThatThrownBy(() -> service.generationImage(generation.id, "hero"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("checksum");
  }

  private static ImageGeneration generation() {
    UUID articleId = UUID.randomUUID();
    return new ImageGeneration(
        articleId,
        "Editorial illustration",
        "An illustration",
        "articles/" + articleId + "/hero-selected.png",
        "fake",
        "fixture-v1");
  }

  private static MediaAsset asset(ImageGeneration generation, String variant) {
    return new MediaAsset(
        generation.articleId,
        generation.id,
        variant,
        generation.objectKey.replace("/hero-", "/" + variant + "-"),
        "image/png",
        1600,
        900,
        null);
  }

  private static void assertStatus(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, HttpStatus status) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
  }
}
