package com.nsangusa.news.identity.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SecurityProbeController.class)
@Import({
  SecurityConfiguration.class,
  SecurityConfigurationTests.TestBeans.class,
  SecurityProbeController.class
})
@TestPropertySource(
    properties = {
      "server.port=8080",
      "management.server.port=8081",
      "news.operations.internal-metrics-enabled=true"
    })
class SecurityConfigurationTests {
  @Autowired MockMvc mvc;

  @MockitoBean UserAccountRepository users;
  @MockitoBean IdentityOidcUserService oidcUsers;
  @MockitoBean LoginSecurityService loginSecurity;

  @Test
  void publicArticleAndCsrfBootstrapRemainAnonymous() throws Exception {
    mvc.perform(get("/api/v1/articles/security-probe")).andExpect(status().isOk());

    mvc.perform(get("/api/v1/auth/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headerName").isNotEmpty())
        .andExpect(jsonPath("$.token").isNotEmpty());
  }

  @Test
  void unauthenticatedAndFailedBasicRequestsAreNotChallengedForBrowserCredentials()
      throws Exception {
    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("WWW-Authenticate"));
    mvc.perform(get("/api/v1/auth/me").with(httpBasic("reader@example.test", "wrong-password")))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("WWW-Authenticate"));
  }

  @Test
  void metricsAreAnonymousOnlyOnTheExplicitlyEnabledSeparateManagementListener() throws Exception {
    mvc.perform(
            get("/actuator/prometheus")
                .with(
                    request -> {
                      request.setLocalPort(8081);
                      return request;
                    }))
        .andExpect(status().isOk());
    mvc.perform(
            get("/actuator/prometheus")
                .header("X-Forwarded-Port", "8081")
                .with(
                    request -> {
                      request.setLocalPort(8080);
                      return request;
                    }))
        .andExpect(status().isUnauthorized());
    for (String path :
        java.util.List.of("/actuator/prometheus", "/actuator/metrics", "/actuator/info")) {
      mvc.perform(get(path).with(user("reader").roles("READER"))).andExpect(status().isForbidden());
      mvc.perform(get(path).with(user("administrator").roles("ADMINISTRATOR")))
          .andExpect(status().isOk());
    }
    mvc.perform(
            get("/actuator/metrics")
                .with(
                    request -> {
                      request.setLocalPort(8081);
                      return request;
                    }))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminMutationRequiresAuthenticationEditorRoleAndCsrf() throws Exception {
    mvc.perform(post("/api/v1/admin/security-probe").with(csrf()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/admin/security-probe").with(user("reader").roles("READER")).with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/admin/security-probe").with(user("editor").roles("EDITOR")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/security-probe").with(user("editor").roles("EDITOR")).with(csrf()))
        .andExpect(status().isNoContent());
  }

  @Test
  void newsletterBrowserMutationsRemainAnonymousButRequireCsrf() throws Exception {
    for (String path :
        java.util.List.of("subscriptions", "confirm", "preferences", "preferences/link")) {
      mvc.perform(post("/api/v1/newsletter/" + path)).andExpect(status().isForbidden());
      mvc.perform(post("/api/v1/newsletter/" + path).with(csrf()))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void providerWebhooksAndOnlyTheExactOneClickPostAreExemptFromCsrf() throws Exception {
    mvc.perform(post("/api/v1/newsletter/provider-webhooks/security-probe"))
        .andExpect(status().isNoContent());
    mvc.perform(post("/api/v1/newsletter/unsubscribe")).andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/newsletter/unsubscribe")).andExpect(status().isMethodNotAllowed());
    mvc.perform(put("/api/v1/newsletter/unsubscribe")).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/newsletter/unsubscribe/extra")).andExpect(status().isForbidden());
  }

  @TestConfiguration
  static class TestBeans {
    @Bean
    RedisRateLimitFilter redisRateLimitFilter(Environment environment) {
      var redis = mock(StringRedisTemplate.class);
      @SuppressWarnings("unchecked")
      ValueOperations<String, String> values = mock(ValueOperations.class);
      when(redis.opsForValue()).thenReturn(values);
      when(values.increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
      return new RedisRateLimitFilter(redis, 300, environment);
    }

    @Bean
    org.springframework.cache.CacheManager cacheManager() {
      return new org.springframework.cache.support.NoOpCacheManager();
    }
  }
}
