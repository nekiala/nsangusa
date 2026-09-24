package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(OperationalMetricsIntegrationTests.Beans.class)
class OperationalMetricsIntegrationTests {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
              .asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired DataSource dataSource;
  @Autowired JdbcClient jdbc;

  @Test
  void inventoriesAreExactAndConsistentAcrossReplicaCollectors() {
    jdbc.sql(
            """
            insert into outbox_events(id,event_type,aggregate_id,correlation_id,idempotency_key,
              envelope_json,created_at)
            select gen_random_uuid(),'Synthetic',gen_random_uuid(),gen_random_uuid(),
              :prefix || n,'{}',now()-interval '2 minutes' from generate_series(1,305) n
            """)
        .param("prefix", UUID.randomUUID().toString())
        .update();
    var first = new SimpleMeterRegistry();
    var second = new SimpleMeterRegistry();
    new OperationalMetrics(dataSource, first).refresh();
    new OperationalMetrics(dataSource, second).refresh();
    assertThat(first.get("news.operations.collection.success").gauge().value()).isEqualTo(1);
    assertThat(first.get("news.operations.outbox.pending").gauge().value()).isEqualTo(305);
    assertThat(second.get("news.operations.outbox.pending").gauge().value()).isEqualTo(305);
    assertThat(first.get("news.operations.outbox.oldest.age").gauge().value())
        .isGreaterThanOrEqualTo(120);
  }

  @TestConfiguration
  static class Beans {
    @Bean
    CacheManager cacheManager() {
      return new NoOpCacheManager();
    }
  }
}
