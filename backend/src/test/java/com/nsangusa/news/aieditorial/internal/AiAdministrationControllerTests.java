package com.nsangusa.news.aieditorial.internal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nsangusa.news.aieditorial.AiAdministrationService;
import com.nsangusa.news.aieditorial.AiAdministrationService.ConfigurationView;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = AiAdministrationController.class,
    excludeFilters =
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = ".*RedisRateLimitFilter"))
@Import(AiRequestControllerTests.Security.class)
class AiAdministrationControllerTests {
  private static final String ROOT = "/api/v1/admin/ai-configuration";
  private static final String INPUT =
      """
      {"expectedVersion":0,"provider":"openai","model":"gpt-5-mini",
       "promptVersion":"editorial-v1","reason":"Approved deployment"}
      """;
  @Autowired MockMvc mvc;
  @MockitoBean AiAdministrationService service;
  @MockitoBean DurableCommandExecutor commands;

  @Test
  void onlyAdministratorsCanReadOrMutateAndCsrfAndDurableKeysAreRequired() throws Exception {
    mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
    for (String role : List.of("READER", "MODERATOR", "EDITOR")) {
      for (String suffix : List.of("", "/providers", "/prompts", "/history")) {
        mvc.perform(get(ROOT + suffix).with(user("actor").roles(role)))
            .andExpect(status().isForbidden());
      }
      mvc.perform(
              put(ROOT)
                  .with(user("actor").roles(role))
                  .with(csrf())
                  .header("Idempotency-Key", "admin-test-key")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(INPUT))
          .andExpect(status().isForbidden());
      mvc.perform(
              post(ROOT + "/prompts")
                  .with(user("actor").roles(role))
                  .with(csrf())
                  .header("Idempotency-Key", "prompt-test-key")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
              {"expectedRevision":1,"guidance":"Use concise, careful prose.","reason":"Style update"}
              """))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            put(ROOT)
                .with(user("admin").roles("ADMINISTRATOR"))
                .header("Idempotency-Key", "admin-test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(INPUT))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(ROOT)
                .with(user("admin").roles("ADMINISTRATOR"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(INPUT))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service, commands);
  }

  @Test
  void returnsVersionedSelectionThroughActorBoundCommandWithoutCredentialFields() throws Exception {
    UUID actor = UUID.nameUUIDFromBytes("admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(commands.execute(eq(actor), eq("admin-test-key"), eq("PUT " + ROOT), any(), any()))
        .thenAnswer(invocation -> ((Supplier<String>) invocation.getArgument(4)).get());
    when(service.select(
            eq(0L), eq("openai"), eq("gpt-5-mini"), eq("editorial-v1"), anyString(), eq(actor)))
        .thenReturn(1L);
    when(service.configuration(1))
        .thenReturn(
            new ConfigurationView(
                1,
                "openai",
                "gpt-5-mini",
                "editorial-v1",
                "env:AI_API_KEY",
                actor,
                Instant.now(),
                "Approved deployment"));
    mvc.perform(
            put(ROOT)
                .with(user("admin").roles("ADMINISTRATOR"))
                .with(csrf())
                .header("Idempotency-Key", "admin-test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(INPUT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.secretReference").value("env:AI_API_KEY"))
        .andExpect(jsonPath("$.apiKey").doesNotExist())
        .andExpect(jsonPath("$.baseUrl").doesNotExist());
  }

  @Test
  void rejectsUnknownCredentialAndNetworkControlsAndInvalidVersions() throws Exception {
    for (String bad :
        List.of(
            INPUT.replace("\"expectedVersion\":0", "\"expectedVersion\":-1"),
            INPUT.replace(
                "\"model\":\"gpt-5-mini\"", "\"model\":\"https://attacker.invalid/model\""),
            INPUT.replace("\"reason\":", "\"apiKey\":\"sk-supplied-secret\",\"reason\":"),
            INPUT.replace("\"reason\":", "\"baseUrl\":\"https://attacker.invalid\",\"reason\":"),
            INPUT.replace("\"reason\":", "\"secretReference\":\"env:OTHER_KEY\",\"reason\":"),
            INPUT.replace("\"reason\":", "\"tools\":[],\"reason\":"))) {
      mvc.perform(
              put(ROOT)
                  .with(user("admin").roles("ADMINISTRATOR"))
                  .with(csrf())
                  .header("Idempotency-Key", "admin-test-key")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(bad))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(commands, service);
  }

  @Test
  void staleSelectionsReturnConflictWithoutPretendingToSucceed() throws Exception {
    when(commands.execute(any(), any(), any(), any(), any()))
        .thenThrow(new OptimisticLockingFailureException("Stale configuration"));
    mvc.perform(
            put(ROOT)
                .with(user("admin").roles("ADMINISTRATOR"))
                .with(csrf())
                .header("Idempotency-Key", "admin-test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(INPUT))
        .andExpect(status().isConflict());
    verifyNoInteractions(service);
  }
}
