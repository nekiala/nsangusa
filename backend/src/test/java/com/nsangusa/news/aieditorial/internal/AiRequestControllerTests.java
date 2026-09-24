package com.nsangusa.news.aieditorial.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nsangusa.news.aieditorial.EditorialRequestService;
import com.nsangusa.news.aieditorial.EditorialRequestService.AiRequestView;
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
    controllers = AiRequestController.class,
    excludeFilters =
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = ".*RedisRateLimitFilter"))
@Import(AiRequestControllerTests.Security.class)
class AiRequestControllerTests {
  @Autowired MockMvc mvc;
  @MockitoBean EditorialRequestService requests;

  @Test
  void requiresEditorOrAdminAndCandidateFilter() throws Exception {
    UUID storyId = UUID.randomUUID();
    String url = "/api/v1/admin/ai-requests?storyCandidateId=" + storyId;
    when(requests.list(any()))
        .thenReturn(
            List.of(
                new AiRequestView(
                    UUID.randomUUID(),
                    storyId,
                    "analysis",
                    "openai",
                    "gpt-5-mini",
                    "editorial-v1",
                    "failed",
                    "provider_refusal",
                    Instant.now(),
                    Instant.now(),
                    100,
                    10,
                    null)));
    mvc.perform(get(url)).andExpect(status().isUnauthorized());
    for (String role : List.of("READER", "MODERATOR")) {
      mvc.perform(get(url).with(user("user").roles(role))).andExpect(status().isForbidden());
    }
    for (String role : List.of("EDITOR", "ADMINISTRATOR")) {
      mvc.perform(get(url).with(user("user").roles(role)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].storyCandidateId").value(storyId.toString()))
          .andExpect(jsonPath("$[0].model").value("gpt-5-mini"))
          .andExpect(jsonPath("$[0].result").isEmpty());
    }
    mvc.perform(get("/api/v1/admin/ai-requests").with(user("editor").roles("EDITOR")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/admin/ai-requests?storyCandidateId=bad-id")
                .with(user("editor").roles("EDITOR")))
        .andExpect(status().isBadRequest());
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
