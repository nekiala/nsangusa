package com.nsangusa.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class InfrastructureContainersTests {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4")
          .withDatabaseName("news")
          .withUsername("news")
          .withPassword("news");

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.10.0").withExposedPorts(6379);

  @Test
  void supportedInfrastructureStarts() throws Exception {
    try (var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      assertThat(connection.isValid(2)).isTrue();
    }
    var kafkaBootstrap = java.net.URI.create("tcp://" + KAFKA.getBootstrapServers());
    assertThat(kafkaBootstrap.getHost()).isNotBlank();
    assertThat(kafkaBootstrap.getPort()).isPositive();
    assertThat(REDIS.execInContainer("redis-cli", "ping").getStdout()).contains("PONG");
  }
}
