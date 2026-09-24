package com.nsangusa.news.audit.internal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nsangusa.news.audit.AuditService;
import java.util.List;
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
    controllers = AuditController.class,
    excludeFilters =
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = ".*RedisRateLimitFilter"))
@Import(AuditControllerTests.Security.class)
class AuditControllerTests {
  @Autowired MockMvc mvc;
  @MockitoBean AuditService audit;

  @Test
  void onlyAdministratorsCanReadUncachedAuditInventory() throws Exception {
    String path = "/api/v1/admin/audit-records";
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    for (String role : List.of("READER", "MODERATOR", "EDITOR")) {
      mvc.perform(get(path).with(user("person").roles(role))).andExpect(status().isForbidden());
    }
    when(audit.list(any(), any(), any(), any(), any(), any(), eq(0), eq(20)))
        .thenReturn(new AuditService.AuditPage(List.of(), 0, 20, 0));
    mvc.perform(get(path).with(user("admin").roles("ADMINISTRATOR")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.total").value(0));
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
