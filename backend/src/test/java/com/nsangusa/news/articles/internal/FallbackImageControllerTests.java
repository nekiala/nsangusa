package com.nsangusa.news.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.media.MediaService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FallbackImageControllerTests {
  private final ArticleService articles = mock(ArticleService.class);
  private final MediaService media = mock(MediaService.class);
  private final DurableCommandExecutor commands = mock(DurableCommandExecutor.class);
  private final ArticleController controller = new ArticleController(articles, media, commands);
  private final UUID articleId = UUID.randomUUID();

  private void executeCommands() {
    when(commands.execute(any(), anyString(), anyString(), any(), any()))
        .thenAnswer(invocation -> invocation.<Supplier<String>>getArgument(4).get());
  }

  @Test
  void locksTheReviewedVersionAndCreatesAnUnapprovedCandidate() {
    executeCommands();
    var article = mock(ArticleService.ArticleView.class);
    when(article.version()).thenReturn(3L);
    when(article.state()).thenReturn(ArticleState.AWAITING_REVIEW);
    when(articles.getLocked(articleId)).thenReturn(article);
    UUID eventId = UUID.randomUUID();
    UUID actorId = UUID.nameUUIDFromBytes("editor".getBytes(StandardCharsets.UTF_8));
    when(media.requestFallback(articleId, "Neutral illustration", "Editorial choice", actorId))
        .thenReturn(eventId);
    var request =
        new ArticleController.FallbackImageRequest("Neutral illustration", "Editorial choice", 3L);

    var response = controller.fallbackImage(articleId, request, () -> "editor", "fallback-key");

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    assertThat(response.getBody().eventId()).isEqualTo(eventId);
    verify(articles).getLocked(articleId);
    verify(commands)
        .execute(
            org.mockito.ArgumentMatchers.eq(actorId),
            org.mockito.ArgumentMatchers.eq("fallback-key"),
            org.mockito.ArgumentMatchers.eq("article-image-fallback:" + articleId),
            org.mockito.ArgumentMatchers.eq(request),
            any());
    verify(media).requestFallback(articleId, "Neutral illustration", "Editorial choice", actorId);
  }

  @Test
  void rejectsStaleVersionsAndNoneditableStatesBeforeCreatingObjects() {
    executeCommands();
    var article = mock(ArticleService.ArticleView.class);
    when(articles.getLocked(articleId)).thenReturn(article);
    when(article.version()).thenReturn(4L);
    assertThatThrownBy(
            () ->
                controller.fallbackImage(
                    articleId,
                    new ArticleController.FallbackImageRequest("Alt", "Reason", 3L),
                    () -> "editor",
                    "fallback-key"))
        .isInstanceOf(OptimisticLockingFailureException.class);
    for (ArticleState state :
        new ArticleState[] {
          ArticleState.PUBLISHED,
          ArticleState.SCHEDULED,
          ArticleState.REJECTED,
          ArticleState.ARCHIVED,
          ArticleState.UNPUBLISHED
        }) {
      when(article.state()).thenReturn(state);
      assertThatThrownBy(
              () ->
                  controller.fallbackImage(
                      articleId,
                      new ArticleController.FallbackImageRequest("Alt", "Reason", 4L),
                      () -> "editor",
                      "fallback-key"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("editable draft");
    }
    verifyNoInteractions(media);
  }

  @Test
  void httpBoundaryRequiresVersionReasonAltTextAndIdempotencyKey() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    for (String body :
        new String[] {
          "{\"altText\":\"Alt\",\"reason\":\"Reason\"}",
          "{\"altText\":\"\",\"reason\":\"Reason\",\"expectedVersion\":1}",
          "{\"altText\":\"Alt\",\"reason\":\"\",\"expectedVersion\":1}",
          "{\"altText\":\"Alt\",\"reason\":\"Reason\",\"expectedVersion\":-1}"
        }) {
      mvc.perform(
              post("/api/v1/admin/articles/{articleId}/images/fallback", articleId)
                  .principal(() -> "editor")
                  .header("Idempotency-Key", "fallback-key")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post("/api/v1/admin/articles/{articleId}/images/fallback", articleId)
                .principal(() -> "editor")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"altText\":\"Alt\",\"reason\":\"Reason\",\"expectedVersion\":1}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(commands, media);
  }
}
