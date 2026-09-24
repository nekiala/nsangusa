package com.nsangusa.news.newsletter.internal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nsangusa.news.newsletter.NewsletterManagementService;
import com.nsangusa.news.newsletter.NewsletterService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {NewsletterController.class, NewsletterManagementController.class},
    excludeFilters =
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = ".*RedisRateLimitFilter"))
@Import(NewsletterManagementControllerTests.Security.class)
class NewsletterManagementControllerTests {
  @Autowired MockMvc mvc;
  @MockitoBean NewsletterManagementService management;
  @MockitoBean NewsletterService newsletter;
  @MockitoBean NewsletterWebhookService webhooks;

  @Test
  void preferenceRequestsHaveAnEnumerationSafeResponseAndRequireCsrf() throws Exception {
    String route = "/api/v1/newsletter/preferences/link";
    mvc.perform(
            post(route)
                .contentType("application/json")
                .content("{\"email\":\"reader@example.test\"}"))
        .andExpect(status().isForbidden());
    String known =
        mvc.perform(
                post(route)
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"email\":\"reader@example.test\"}"))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknown =
        mvc.perform(
                post(route)
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"email\":\"unknown@example.test\"}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.assertj.core.api.Assertions.assertThat(known)
        .isEqualTo(unknown)
        .doesNotContain("reader@", "unknown@");
  }

  @Test
  void anonymousReadIsAddressFreeAndNoGetRouteChangesConsent() throws Exception {
    UUID id = UUID.randomUUID();
    when(management.preferences(id, "private-token"))
        .thenReturn(
            new NewsletterManagementService.PreferenceView(
                "confirmed", "weekly", Instant.now().plusSeconds(1800)));
    mvc.perform(
            get("/api/v1/newsletter/preferences")
                .param("id", id.toString())
                .param("token", "private-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").doesNotExist())
        .andExpect(jsonPath("$.frequency").value("weekly"))
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            get("/api/v1/newsletter/confirm")
                .param("id", id.toString())
                .param("token", "private-token"))
        .andExpect(status().isMethodNotAllowed());
    mvc.perform(
            get("/api/v1/newsletter/unsubscribe")
                .param("id", id.toString())
                .param("token", "private-token"))
        .andExpect(status().isMethodNotAllowed());
    verifyNoInteractions(newsletter);
    verify(management, never()).updatePreferences(any(), any(), any(), any());
  }

  @Test
  void preservesExplicitConfirmationAndPublicOneClickPost() throws Exception {
    UUID id = UUID.randomUUID();
    mvc.perform(
            post("/api/v1/newsletter/confirm")
                .with(csrf())
                .param("id", id.toString())
                .param("token", "verify"))
        .andExpect(status().isNoContent());
    mvc.perform(
            post("/api/v1/newsletter/unsubscribe")
                .param("id", id.toString())
                .param("token", "remove")
                .contentType("application/x-www-form-urlencoded")
                .content("List-Unsubscribe=One-Click"))
        .andExpect(status().isNoContent());
    verify(newsletter).confirm(id, "verify");
    verify(newsletter).unsubscribe(id, "remove");
  }

  @Test
  void allManagementViewsRequireAdministratorAndReconciliationIsCsrfProtected() throws Exception {
    for (String path : List.of("subscriptions", "campaigns", "delivery-history", "suppressions")) {
      String url = "/api/v1/newsletter/admin/" + path;
      mvc.perform(get(url)).andExpect(status().isUnauthorized());
      for (String role : List.of("READER", "MODERATOR", "EDITOR")) {
        mvc.perform(get(url).with(user("user").roles(role))).andExpect(status().isForbidden());
      }
      mvc.perform(get(url).with(user("admin").roles("ADMINISTRATOR"))).andExpect(status().isOk());
    }
    UUID id = UUID.randomUUID();
    String route = "/api/v1/newsletter/admin/deliveries/" + id + "/reconciliation";
    String body = "{\"outcome\":\"abandoned\",\"evidenceReference\":\"ticket:123\"}";
    mvc.perform(
            post(route)
                .with(user("admin").roles("ADMINISTRATOR"))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(route)
                .with(csrf())
                .with(user("editor").roles("EDITOR"))
                .header("Idempotency-Key", "reconcile-key-123")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(route)
                .with(csrf())
                .with(user("admin").roles("ADMINISTRATOR"))
                .header("Idempotency-Key", "reconcile-key-123")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isNoContent());
    verify(management)
        .reconcile(
            eq(id),
            any(),
            eq(new NewsletterManagementService.Reconciliation("abandoned", "ticket:123", null)),
            eq("reconcile-key-123"));
  }

  @Test
  void preferenceFrequencyIsRequiredAndUsesDurableMutationKey() throws Exception {
    UUID id = UUID.randomUUID();
    var request =
        post("/api/v1/newsletter/preferences")
            .with(csrf())
            .param("id", id.toString())
            .param("token", "private")
            .contentType("application/json");
    mvc.perform(request.content("{}")).andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/newsletter/preferences")
                .with(csrf())
                .param("id", id.toString())
                .param("token", "private")
                .header("Idempotency-Key", "preferences-key")
                .contentType("application/json")
                .content("{\"frequency\":\"daily\"}"))
        .andExpect(status().isNoContent());
    verify(management).updatePreferences(id, "private", "daily", "preferences-key");
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
      return http.authorizeHttpRequests(
              auth ->
                  auth.requestMatchers("/api/v1/newsletter/admin/**")
                      .authenticated()
                      .anyRequest()
                      .permitAll())
          .httpBasic(Customizer.withDefaults())
          .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/newsletter/unsubscribe"))
          .build();
    }
  }
}
