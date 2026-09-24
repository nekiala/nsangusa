package com.nsangusa.news.media.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.integration.NewsEvents.ArticleImageApproved;
import com.nsangusa.news.integration.NewsEvents.ArticleImageCandidateGenerated;
import com.nsangusa.news.media.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class NeutralEditorialFallbackTests {
  private final ImageGenerationRepository generations = mock(ImageGenerationRepository.class);
  private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
  private final ObjectStorage storage = mock(ObjectStorage.class);
  private final DurableEventPublisher events = mock(DurableEventPublisher.class);
  private final AuditService audit = mock(AuditService.class);
  private final MediaApplicationService service =
      new MediaApplicationService(
          generations, assets, storage, events, audit, new ImageVariantProcessor());

  @Test
  void originalPngHasStableProvenanceAndRequiresExplicitApproval() throws Exception {
    UUID articleId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    var stored = new HashMap<String, ObjectStorage.StoredObject>();
    doAnswer(
            invocation -> {
              stored.put(
                  invocation.getArgument(0),
                  new ObjectStorage.StoredObject(
                      invocation.getArgument(1), invocation.getArgument(2)));
              return null;
            })
        .when(storage)
        .put(anyString(), any(byte[].class), eq("image/png"));
    when(storage.read(anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
    when(events.enqueue(
            eq("ArticleImageCandidateGenerated"),
            eq(articleId),
            eq(articleId),
            isNull(),
            startsWith("article-image-fallback:"),
            any(ArticleImageCandidateGenerated.class)))
        .thenReturn(eventId);

    assertThat(
            service.requestFallback(
                articleId, " A neutral newspaper illustration ", " Provider unavailable ", actorId))
        .isEqualTo(eventId);

    var generationCaptor = ArgumentCaptor.forClass(ImageGeneration.class);
    verify(generations).save(generationCaptor.capture());
    var generation = generationCaptor.getValue();
    assertThat(generation.articleId).isEqualTo(articleId);
    assertThat(generation.altText).isEqualTo("A neutral newspaper illustration");
    assertThat(generation.provider).isEqualTo("nsangusa-editorial");
    assertThat(generation.model).isEqualTo("neutral-illustration-v1");
    assertThat(generation.prompt).isEqualTo(NeutralEditorialIllustration.PROMPT);
    assertThat(generation.renderedPrompt).isEqualTo(generation.prompt);
    assertThat(generation.promptVersion).isEqualTo(generation.model);
    assertThat(generation.providerRequestId).isNull();
    assertThat(generation.safetyStatus).isEqualTo("review_required");
    assertThat(generation.approvedAt).isNull();
    assertThat(generation.approvedBy).isNull();
    assertThat(generation.approvalEventExpected).isFalse();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<MediaAsset>> assetsCaptor = ArgumentCaptor.forClass(Iterable.class);
    verify(assets).saveAll(assetsCaptor.capture());
    var savedAssets = new ArrayList<MediaAsset>();
    assetsCaptor.getValue().forEach(savedAssets::add);
    assertThat(savedAssets)
        .extracting(asset -> asset.variantName)
        .containsExactly("hero", "thumbnail", "social");
    assertThat(savedAssets).extracting(asset -> asset.width).containsExactly(1600, 640, 1200);
    assertThat(savedAssets).extracting(asset -> asset.height).containsExactly(900, 360, 630);
    for (var asset : savedAssets) {
      var content = stored.get(asset.objectKey);
      assertThat(asset.articleId).isEqualTo(articleId);
      assertThat(asset.generationId).isEqualTo(generation.id);
      assertThat(asset.mediaType).isEqualTo("image/png");
      assertThat(asset.sha256).isEqualTo(StoredImages.sha256(content.bytes()));
      try (var input = new ByteArrayInputStream(content.bytes())) {
        var image = ImageIO.read(input);
        assertThat(image.getWidth()).isEqualTo(asset.width);
        assertThat(image.getHeight()).isEqualTo(asset.height);
        assertThat(image.getRGB(0, 0))
            .isNotEqualTo(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
      }
    }
    verify(events)
        .enqueue(
            eq("ArticleImageCandidateGenerated"),
            eq(articleId),
            eq(articleId),
            isNull(),
            eq("article-image-fallback:" + generation.id),
            eq(
                new ArticleImageCandidateGenerated(
                    articleId,
                    generation.id,
                    generation.objectKey,
                    generation.altText,
                    generation.provider,
                    generation.model,
                    generation.createdAt)));
    verify(audit)
        .record(
            eq(actorId),
            eq("IMAGE_FALLBACK_REQUESTED"),
            eq("article"),
            eq(articleId),
            argThat(
                metadata ->
                    "Provider unavailable".equals(metadata.get("reason"))
                        && generation.id.toString().equals(metadata.get("generationId"))
                        && NeutralEditorialIllustration.RIGHTS.equals(metadata.get("rights"))
                        && generation.altText.equals(metadata.get("altText"))));

    when(generations.findById(generation.id)).thenReturn(Optional.of(generation));
    when(generations.findLockedById(generation.id)).thenReturn(Optional.of(generation));
    when(generations.findFirstByObjectKeyOrderByCreatedAtDesc(generation.objectKey))
        .thenReturn(Optional.of(generation));
    when(assets.findByGenerationIdAndVariantNameAndDeletedAtIsNull(eq(generation.id), anyString()))
        .thenAnswer(
            invocation ->
                savedAssets.stream()
                    .filter(asset -> asset.variantName.equals(invocation.getArgument(1)))
                    .findFirst());
    assertThat(service.generationImage(generation.id, "hero").bytes())
        .containsExactly(stored.get(generation.objectKey).bytes());
    assertThatThrownBy(() -> service.image(generation.objectKey, "hero"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

    service.approve(generation.id, actorId);
    service.approve(generation.id, actorId);

    var approvedCaptor = ArgumentCaptor.forClass(ArticleImageApproved.class);
    verify(events)
        .enqueue(
            eq("ArticleImageApproved"),
            eq(articleId),
            eq(articleId),
            isNull(),
            eq("article-image-approved:" + generation.id),
            approvedCaptor.capture());
    assertThat(approvedCaptor.getValue().generatedImage()).isFalse();
    assertThat(approvedCaptor.getValue().approvedBy()).isEqualTo(actorId);
    assertThat(generation.safetyStatus).isEqualTo("approved");
    assertThat(generation.approvalEventExpected).isTrue();
    verify(audit)
        .record(
            eq(actorId), eq("IMAGE_APPROVED"), eq("image_generation"), eq(generation.id), any());
    assertThat(service.image(generation.objectKey, "social").contentType()).isEqualTo("image/png");
  }

  @Test
  void blankOrOversizedAltTextAndReasonsCannotStoreImages() {
    UUID articleId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    for (String altText : Arrays.asList(null, "", " \n ", "a".repeat(501))) {
      assertThatThrownBy(
              () -> service.requestFallback(articleId, altText, "Editorial choice", actorId))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("alternative text");
    }
    for (String reason : Arrays.asList(null, "", " \n ", "a".repeat(2001))) {
      assertThatThrownBy(
              () -> service.requestFallback(articleId, "A neutral illustration", reason, actorId))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reason");
    }
    assertThatThrownBy(() -> service.requestFallback(null, "Illustration", "Choice", actorId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.requestFallback(articleId, "Illustration", "Choice", null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(generations, assets, storage, events, audit);
  }

  @Test
  void storageFailureDoesNotCreateAnApprovedOrMissingCandidate() {
    var failure = new IllegalStateException("Object storage unavailable");
    doThrow(failure).when(storage).put(anyString(), any(byte[].class), eq("image/png"));

    assertThatThrownBy(
            () ->
                service.requestFallback(
                    UUID.randomUUID(),
                    "A neutral illustration",
                    "Editorial choice",
                    UUID.randomUUID()))
        .isSameAs(failure);

    verifyNoInteractions(generations, assets, events, audit);
  }

  @Test
  void legacyApprovalConstructorAndJsonRemainCompatible() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var legacy =
        new ArticleImageApproved(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "articles/example/hero.png",
            "An illustration",
            UUID.randomUUID(),
            Instant.now());
    assertThat(legacy.generatedImage()).isTrue();
    ObjectNode json = mapper.valueToTree(legacy);
    json.remove("generatedImage");
    assertThat(mapper.treeToValue(json, ArticleImageApproved.class).generatedImage()).isNull();
    json.put("generatedImage", false);
    assertThat(mapper.treeToValue(json, ArticleImageApproved.class).generatedImage()).isFalse();
  }
}
