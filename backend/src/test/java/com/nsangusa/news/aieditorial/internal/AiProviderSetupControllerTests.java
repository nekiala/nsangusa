package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import java.util.List;
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
    controllers = AiProviderSetupController.class,
    excludeFilters =
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = ".*RedisRateLimitFilter"))
@Import(AiRequestControllerTests.Security.class)
class AiProviderSetupControllerTests {
  private static final String ROOT = "/api/v1/admin/ai-configuration/setup";
  private static final String SECRET = "sk-test-only-not-an-external-credential";
  private static final String INPUT =
      "{\"expectedVersion\":0,\"apiKey\":\"" + SECRET + "\",\"reason\":\"Rotate project key\"}";
  @Autowired MockMvc mvc;
  @MockitoBean AiProviderSetupService setup;
  @MockitoBean DurableCommandExecutor commands;

  @Test
  void allSetupRoutesRequireAdministratorAndMutationsRequireCsrfAndIdempotency() throws Exception {
    mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
    for (String role : List.of("READER", "MODERATOR", "EDITOR")) {
      mvc.perform(get(ROOT).with(user("actor").roles(role))).andExpect(status().isForbidden());
      for (var request :
          List.of(
              java.util.Map.entry(
                  put(ROOT),
                  """
                  {"expectedVersion":0,"provider":"openai","model":"gpt-5-mini","promptVersion":"editorial-v1",
                   "timeoutSeconds":10,"maxOutputTokens":1024,"dailyTokenBudget":500000,"reason":"Reviewed draft"}
                  """),
              java.util.Map.entry(put(ROOT + "/credential"), INPUT),
              java.util.Map.entry(
                  delete(ROOT + "/credential"),
                  """
                  {"expectedVersion":0,"reason":"Remove project key"}
                  """),
              java.util.Map.entry(
                  post(ROOT + "/activate"),
                  """
                  {"expectedVersion":0,"expectedConfigurationVersion":0,
                   "acknowledgeOutboundDataAndCost":true,"reason":"Rights reviewed"}
                  """))) {
        mvc.perform(
                request
                    .getKey()
                    .with(user("actor").roles(role))
                    .with(csrf())
                    .header("Idempotency-Key", "credential-test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request.getValue()))
            .andExpect(status().isForbidden());
      }
    }
    mvc.perform(
            put(ROOT + "/credential")
                .with(user("admin").roles("ADMINISTRATOR"))
                .header("Idempotency-Key", "credential-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(INPUT))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(ROOT + "/credential")
                .with(user("admin").roles("ADMINISTRATOR"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(INPUT))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(setup, commands);
  }

  @Test
  void durableReceiptOnlySeesCredentialDigestAndResponsesNeverReturnCredential() throws Exception {
    var input = new AiProviderSetupController.CredentialRequest(0L, SECRET, "Rotate project key");
    assertThat(input.toString()).doesNotContain(SECRET);
    assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input))
        .doesNotContain(SECRET, "apiKey");
    when(commands.execute(any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              assertThat(call.getArgument(3).toString())
                  .contains("credentialDigest")
                  .doesNotContain(SECRET, "apiKey");
              return ((Supplier<String>) call.getArgument(4)).get();
            });
    when(setup.status()).thenReturn(statusView());
    mvc.perform(
            put(ROOT + "/credential")
                .with(user("admin").roles("ADMINISTRATOR"))
                .with(csrf())
                .header("Idempotency-Key", "credential-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(INPUT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.credentialStatus").value("CONFIGURED"))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));
    verify(setup).rotate(eq(0L), eq(SECRET), eq("Rotate project key"), any());
  }

  @Test
  void validationAndMalformedJsonNeverEchoSecretsAndUnknownEndpointsAreRejected() throws Exception {
    for (String body :
        List.of(
            INPUT.replace("\"expectedVersion\":0", "\"expectedVersion\":-1"),
            INPUT.replace("\"reason\":", "\"baseUrl\":\"https://127.0.0.1/\",\"reason\":"),
            INPUT.replace(SECRET, SECRET.repeat(20)),
            INPUT.replace("\"expectedVersion\":0", "\"expectedVersion\":\"" + SECRET + "\""))) {
      mvc.perform(
              put(ROOT + "/credential")
                  .with(user("admin").roles("ADMINISTRATOR"))
                  .with(csrf())
                  .header("Idempotency-Key", "credential-test")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(
              content()
                  .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));
    }
    verifyNoInteractions(setup, commands);
  }

  @Test
  void invalidCredentialPayloadIsRedactedEvenWhenActorCannotInvokeTheController() throws Exception {
    mvc.perform(
            put(ROOT + "/credential")
                .with(user("editor").roles("EDITOR"))
                .with(csrf())
                .header("Idempotency-Key", "credential-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(INPUT.replace("\"expectedVersion\":0", "\"expectedVersion\":-1")))
        .andExpect(status().isBadRequest())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));
    verifyNoInteractions(setup, commands);
  }

  @Test
  void staleVersionsReturnConflict() throws Exception {
    when(commands.execute(any(), any(), any(), any(), any()))
        .thenThrow(new OptimisticLockingFailureException("Changed"));
    mvc.perform(
            put(ROOT + "/credential")
                .with(user("admin").roles("ADMINISTRATOR"))
                .with(csrf())
                .header("Idempotency-Key", "credential-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(INPUT))
        .andExpect(status().isConflict());
    verifyNoInteractions(setup);
  }

  private AiProviderSetupService.Status statusView() {
    return new AiProviderSetupService.Status(
        1,
        null,
        null,
        false,
        true,
        true,
        "CONFIGURED",
        false,
        List.of("Save a provider draft"),
        new AiProviderSetupService.Limits(20, 4096, 1000000),
        "https://api.openai.com/v1/responses",
        "fake (simulated)",
        "fake (simulated)");
  }
}
