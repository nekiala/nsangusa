package com.nsangusa.news.audit.internal;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({AuditApplicationService.class, AuditQueryIntegrationTests.Beans.class})
class AuditQueryIntegrationTests {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired AuditApplicationService service;
  @Autowired AuditRepository records;

  @Test
  void filtersAndPaginatesPersistedMetadataWithoutExposingEntities() {
    UUID actor = UUID.randomUUID(), target = UUID.randomUUID();
    for (int index = 0; index < 23; index++) {
      service.record(
          actor, "ARTICLE_EDITED", "article", target, Map.of("revision", Integer.toString(index)));
    }
    service.record(UUID.randomUUID(), "ARTICLE_EDITED", "article", target, Map.of());
    service.record(actor, "ARTICLE_APPROVED", "article", target, Map.of());
    records.flush();
    var page = service.list("ARTICLE_EDITED", "article", actor, target, null, null, 1, 20);
    assertThat(page.total()).isEqualTo(23);
    assertThat(page.items())
        .hasSize(3)
        .allSatisfy(
            row -> {
              assertThat(row.actorId()).isEqualTo(actor);
              assertThat(row.targetId()).isEqualTo(target);
              assertThat(row.metadata()).containsKey("revision");
            });
    assertThat(service.list("ARTICLE_EDITED", "article", actor, target, null, null, 2, 20).items())
        .isEmpty();
  }

  @Test
  void rejectsUnboundedHistoryAndInvalidPagination() {
    assertThatThrownBy(
            () ->
                service.list(
                    null,
                    null,
                    null,
                    null,
                    Instant.now().minusSeconds(91L * 86400),
                    Instant.now(),
                    0,
                    20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("90 days");
    assertThatThrownBy(() -> service.list(null, null, null, null, null, null, -1, 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.list("", null, null, null, null, null, 0, 20))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @TestConfiguration
  static class Beans {
    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    org.springframework.cache.CacheManager cacheManager() {
      return new org.springframework.cache.support.NoOpCacheManager();
    }
  }
}
