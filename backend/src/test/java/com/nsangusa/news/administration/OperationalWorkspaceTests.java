package com.nsangusa.news.administration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.eventprocessing.EventOperations;
import com.nsangusa.news.eventprocessing.WorkflowHealthService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = OperationalHealthController.class,
    excludeFilters =
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = ".*RedisRateLimitFilter"))
@Import(OperationalWorkspaceTests.Security.class)
class OperationalWorkspaceTests {
  @Autowired MockMvc mvc;
  @MockitoBean EventOperations operations;
  @MockitoBean DurableCommandExecutor commands;
  @MockitoBean WorkflowHealthService health;
  @MockitoBean AuditService audit;

  @Test
  void onlyAdministratorsCanInspectExactWorkflowCountsAndPagedFailures() throws Exception {
    String root = "/api/v1/admin/operations";
    for (String path : List.of("/summary", "/failed-events/page", "/replays")) {
      mvc.perform(get(root + path)).andExpect(status().isUnauthorized());
      for (String role : List.of("READER", "MODERATOR", "EDITOR")) {
        mvc.perform(get(root + path).with(user("person").roles(role)))
            .andExpect(status().isForbidden());
      }
    }
    when(health.snapshot())
        .thenReturn(
            new WorkflowHealthService.WorkflowHealth(
                Instant.now(), 12, null, Map.of("eligible", 420L), 2));
    mvc.perform(get(root + "/summary").with(user("admin").roles("ADMINISTRATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.failedEvents.eligible").value(420))
        .andExpect(jsonPath("$.failedEvents.poison").value(0))
        .andExpect(jsonPath("$.workflow.pendingOutbox").value(12));
    when(operations.failedPage(null, 0, 20))
        .thenReturn(new EventOperations.FailedEventPage(List.of(), 0, 20, 420));
    mvc.perform(get(root + "/failed-events/page").with(user("admin").roles("ADMINISTRATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(420))
        .andExpect(jsonPath("$.items").isArray());
  }

  @Test
  void confirmationRequiresCsrfAndUsesAuthenticatedActorAndDurableRequestIdentity()
      throws Exception {
    UUID previewId = UUID.randomUUID(), replayId = UUID.randomUUID();
    UUID actor = UUID.nameUUIDFromBytes("admin".getBytes(StandardCharsets.UTF_8));
    var replay =
        new EventOperations.ReplayRequestView(
            replayId, actor, "review", false, false, 1, 1, 0, 0, "pending", Instant.now(), null);
    when(commands.execute(any(), any(), any(), any(), any()))
        .thenAnswer(call -> call.<Supplier<String>>getArgument(4).get());
    when(operations.confirmReplay(previewId, actor)).thenReturn(replay);
    when(operations.getReplay(replayId)).thenReturn(replay);
    String path = "/api/v1/admin/operations/replays/" + previewId + "/confirm";
    mvc.perform(post(path).with(user("admin").roles("ADMINISTRATOR")))
        .andExpect(status().isForbidden());
    mvc.perform(post(path).with(csrf()).with(user("editor").roles("EDITOR")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path)
                .with(csrf())
                .with(user("admin").roles("ADMINISTRATOR"))
                .header("Idempotency-Key", "reviewed-preview-key")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(replayId.toString()));
    verify(commands)
        .execute(
            eq(actor),
            eq("reviewed-preview-key"),
            eq("event-replay-confirm:" + previewId),
            eq(previewId),
            any());
    verify(audit)
        .record(
            eq(actor),
            eq("EVENT_REPLAY_CONFIRMED"),
            eq("event_replay"),
            eq(replayId),
            eq(Map.of("previewId", previewId.toString())));
  }

  @TestConfiguration
  @EnableMethodSecurity
  static class Security {
    @Bean
    org.springframework.cache.CacheManager cacheManager() {
      return new org.springframework.cache.support.NoOpCacheManager();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
          .httpBasic(Customizer.withDefaults())
          .build();
    }
  }
}
