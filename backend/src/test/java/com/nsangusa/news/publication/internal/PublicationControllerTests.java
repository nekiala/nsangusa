package com.nsangusa.news.publication.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.publication.PublicationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicationControllerTests {
  private final PublicationService publication = mock(PublicationService.class);
  private final DurableCommandExecutor commands = mock(DurableCommandExecutor.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new PublicationController(publication, commands)).build();
  private final UUID actor = UUID.nameUUIDFromBytes("editor".getBytes(StandardCharsets.UTF_8));

  @BeforeEach
  void executeLegacyCommands() {
    when(commands.execute(any(), any(), any(), any(), any()))
        .thenAnswer(call -> call.<Supplier<String>>getArgument(4).get());
  }

  @Test
  void existingScheduleEndpointRetainsResponseAndRejectsMissingTime() throws Exception {
    UUID article = UUID.randomUUID();
    UUID schedule = UUID.randomUUID();
    when(publication.schedule(eq(article), any(), eq(actor))).thenReturn(schedule);
    mvc.perform(
            post("/api/v1/admin/articles/{article}/schedule", article)
                .principal(() -> "editor")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"publishAt\":\"" + Instant.now().plusSeconds(600) + "\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.scheduleId").value(schedule.toString()));
    for (String content : List.of("{}", "{\"publishAt\":null}")) {
      mvc.perform(
              post("/api/v1/admin/articles/{article}/schedule", article)
                  .principal(() -> "editor")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(content))
          .andExpect(status().isBadRequest());
    }
    verify(publication, times(1)).schedule(eq(article), any(), eq(actor));
  }

  @Test
  void aSuccessfulCreationCanReplayEvenAfterItsScheduledDate() throws Exception {
    UUID article = UUID.randomUUID();
    UUID schedule = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    Instant past = Instant.parse("2020-01-01T00:00:00Z");
    String operation = "POST /api/v1/admin/articles/" + article + "/schedule";
    doReturn(schedule.toString())
        .when(commands)
        .execute(eq(actor), eq(key), eq(operation), any(), any());
    mvc.perform(
            post("/api/v1/admin/articles/{article}/schedule", article)
                .principal(() -> "editor")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"publishAt\":\"" + past + "\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.scheduleId").value(schedule.toString()));
    verify(commands)
        .execute(
            eq(actor),
            eq(key),
            eq(operation),
            eq(new PublicationController.ScheduleRequest(past)),
            any());
    verifyNoInteractions(publication);
  }

  @Test
  void inventoryAndPolicyExposeTheDocumentedShapes() throws Exception {
    UUID article = UUID.randomUUID();
    when(publication.list("failed", article, 2, 10))
        .thenReturn(new PublicationService.SchedulePage(List.of(), 2, 10, 21));
    mvc.perform(
            get("/api/v1/admin/publication-schedules")
                .param("status", "failed")
                .param("articleId", article.toString())
                .param("page", "2")
                .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.page").value(2))
        .andExpect(jsonPath("$.size").value(10))
        .andExpect(jsonPath("$.total").value(21));
    when(publication.policy())
        .thenReturn(
            new PublicationService.PolicyView(
                "DRAFT_GENERATION_ONLY",
                new BigDecimal("0.95"),
                Map.of(),
                Set.of("publisher"),
                true,
                "Only automatic publication is disabled"));
    mvc.perform(get("/api/v1/admin/publication-policy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policy").value("DRAFT_GENERATION_ONLY"))
        .andExpect(jsonPath("$.humanPublicationAllowed").value(true));
  }

  @Test
  void changesAndCancellationRequireExplicitNonnegativeVersions() throws Exception {
    UUID schedule = UUID.randomUUID();
    String changeKey = UUID.randomUUID().toString();
    String cancelKey = UUID.randomUUID().toString();
    Instant future = Instant.now().plusSeconds(600);
    mvc.perform(
            patch("/api/v1/admin/publication-schedules/{id}", schedule)
                .principal(() -> "editor")
                .header("Idempotency-Key", changeKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3,\"publishAt\":\"" + future + "\"}"))
        .andExpect(status().isOk());
    verify(publication).reschedule(schedule, 3, future, actor);
    verify(commands)
        .execute(
            eq(actor),
            eq(changeKey),
            eq("PATCH /api/v1/admin/publication-schedules/" + schedule),
            eq(new PublicationController.RescheduleRequest(3L, future)),
            any());
    mvc.perform(
            post("/api/v1/admin/publication-schedules/{id}/cancel", schedule)
                .principal(() -> "editor")
                .header("Idempotency-Key", cancelKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":4}"))
        .andExpect(status().isOk());
    verify(publication).cancel(schedule, 4, actor);
    verify(commands)
        .execute(
            eq(actor),
            eq(cancelKey),
            eq("POST /api/v1/admin/publication-schedules/" + schedule + "/cancel"),
            eq(new PublicationController.CancelRequest(4L)),
            any());
    verify(publication, times(2)).get(schedule);
    for (String version : List.of("null", "-1")) {
      mvc.perform(
              patch("/api/v1/admin/publication-schedules/{id}", schedule)
                  .principal(() -> "editor")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"expectedVersion\":" + version + ",\"publishAt\":\"" + future + "\"}"))
          .andExpect(status().isBadRequest());
      mvc.perform(
              post("/api/v1/admin/publication-schedules/{id}/cancel", schedule)
                  .principal(() -> "editor")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"expectedVersion\":" + version + "}"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            patch("/api/v1/admin/publication-schedules/{id}", schedule)
                .principal(() -> "editor")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3}"))
        .andExpect(status().isBadRequest());
    verifyNoMoreInteractions(publication);
  }
}
