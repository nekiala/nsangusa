package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.integration.NewsEvents.ArticleApproved;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@Import({
  JpaDurableCommandExecutor.class,
  JpaEventStore.class,
  DurableCommandExecutorIntegrationTests.Configuration.class
})
class DurableCommandExecutorIntegrationTests {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired DurableCommandExecutor executor;
  @Autowired DurableEventPublisher publisher;
  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcClient jdbcClient;
  @Autowired ObjectMapper objectMapper;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManager entityManager;

  private final UUID actorId = UUID.randomUUID();
  private final UUID aggregateId = UUID.randomUUID();
  private final AtomicInteger commandInvocations = new AtomicInteger();
  private final AtomicInteger publisherInvocations = new AtomicInteger();

  @BeforeEach
  void createDomainFixture() {
    jdbc.execute("create table if not exists idempotency_test_effects (id uuid primary key)");
  }

  @Test
  void commitsDomainOutboxAndSuccessfulReceiptAndReplaysAcrossExecutorInstances() {
    String key = UUID.randomUUID().toString();
    var request = Map.of("expectedVersion", 3, "headline", "Private editorial text");

    assertThat(executor.execute(actorId, key, operation(), request, this::writeCommand))
        .isEqualTo(aggregateId.toString());
    var restartedExecutor = new JpaDurableCommandExecutor(jdbcClient, objectMapper);
    var transaction = new TransactionTemplate(transactionManager);
    String replayed =
        transaction.execute(
            status ->
                restartedExecutor.execute(actorId, key, operation(), request, this::writeCommand));
    assertThat(replayed).isEqualTo(aggregateId.toString());

    assertCommitted(1);
    assertThat(commandInvocations).hasValue(1);
    assertThat(publisherInvocations).hasValue(1);
    var receipt =
        jdbc.queryForMap(
            "select * from request_idempotency where actor_id = ? and idempotency_key = ?",
            actorId,
            key);
    assertThat(receipt)
        .containsEntry("operation", operation())
        .containsEntry("status", "succeeded")
        .containsEntry("result", aggregateId.toString());
    assertThat(receipt.get("request_hash").toString()).matches("[0-9a-f]{64}");
    assertThat(receipt.get("created_at")).isNotNull();
    assertThat(receipt.get("completed_at")).isNotNull();
    assertThat(receipt.toString()).doesNotContain("Private editorial text", "expectedVersion");
  }

  @Test
  void recursivelySortsObjectKeysIncludingObjectsInsideArrays() throws Exception {
    String key = UUID.randomUUID().toString();
    var request =
        objectMapper.readTree(
            """
            {"z":[{"z":2,"a":{"y":1,"b":0}},{"b":false,"a":null}],"expectedVersion":3}
            """);
    var reordered =
        objectMapper.readTree(
            """
            {"expectedVersion":3,"z":[{"a":{"b":0,"y":1},"z":2},{"a":null,"b":false}]}
            """);

    executor.execute(actorId, key, operation(), request, this::writeCommand);
    assertThat(executor.execute(actorId, key, operation(), reordered, this::writeCommand))
        .isEqualTo(aggregateId.toString());

    String canonical =
        "{\"expectedVersion\":3,\"z\":[{\"a\":{\"b\":0,\"y\":1},\"z\":2},{\"a\":null,\"b\":false}]}";
    String expectedHash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    assertThat(
            jdbc.queryForObject(
                "select request_hash from request_idempotency where actor_id = ?",
                String.class,
                actorId))
        .isEqualTo(expectedHash);
    assertThat(commandInvocations).hasValue(1);
    assertThat(publisherInvocations).hasValue(1);
    assertCommitted(1);
  }

  @Test
  void changedOperationTargetVersionPayloadOrArrayOrderConflictsWithoutCommandInvocation()
      throws Exception {
    String key = UUID.randomUUID().toString();
    var request =
        objectMapper.readTree("{\"expectedVersion\":3,\"headline\":\"Original\",\"items\":[1,2]}");
    executor.execute(actorId, key, operation(), request, this::writeCommand);

    assertConflict(
        () ->
            executor.execute(
                actorId, key, "DELETE /articles/" + aggregateId, request, this::writeCommand));
    assertConflict(
        () ->
            executor.execute(
                actorId, key, "POST /articles/" + UUID.randomUUID(), request, this::writeCommand));
    for (String changed :
        new String[] {
          "{\"expectedVersion\":4,\"headline\":\"Original\",\"items\":[1,2]}",
          "{\"expectedVersion\":3,\"headline\":\"Changed\",\"items\":[1,2]}",
          "{\"expectedVersion\":3,\"headline\":\"Original\",\"items\":[2,1]}"
        }) {
      var changedRequest = objectMapper.readTree(changed);
      assertConflict(
          () -> executor.execute(actorId, key, operation(), changedRequest, this::writeCommand));
    }

    assertThat(commandInvocations).hasValue(1);
    assertThat(publisherInvocations).hasValue(1);
    assertCommitted(1);
  }

  @Test
  void actorScopesAndBoundaryKeysAreIndependentAndNullResultsReplay() {
    var invocations = new AtomicInteger();
    Supplier<String> command =
        () -> {
          invocations.incrementAndGet();
          return null;
        };
    for (String key : new String[] {"!~-_.:01", "x".repeat(200)}) {
      assertThat(executor.execute(actorId, key, operation(), null, command)).isNull();
      assertThat(executor.execute(actorId, key, operation(), null, command)).isNull();
      assertThat(executor.execute(UUID.randomUUID(), key, operation(), null, command)).isNull();
    }

    assertThat(invocations).hasValue(4);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from request_idempotency where actor_id = ? and result is null",
                Integer.class,
                actorId))
        .isEqualTo(2);
    assertConflict(() -> executor.execute(actorId, "!~-_.:01", operation(), Map.of(), command));
    assertThat(invocations).hasValue(4);
  }

  @Test
  void maximumLengthOperationAndResultAreStoredAndReplayed() {
    String key = UUID.randomUUID().toString();
    String operation = "POST /" + "x".repeat(494);
    String result = "r".repeat(200);
    Supplier<String> command =
        () -> {
          writeCommand();
          return result;
        };

    assertThat(executor.execute(actorId, key, operation, null, command)).isEqualTo(result);
    assertThat(executor.execute(actorId, key, operation, null, command)).isEqualTo(result);
    assertThat(commandInvocations).hasValue(1);
    assertThat(publisherInvocations).hasValue(1);
    assertCommitted(1);
  }

  @Test
  void oversizedResultRollsBackCommandAndOutboxAndAllowsRetry() {
    String key = UUID.randomUUID().toString();

    assertThatThrownBy(
            () ->
                executor.execute(
                    actorId,
                    key,
                    operation(),
                    null,
                    () -> {
                      writeCommand();
                      return "r".repeat(201);
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Command result must not exceed 200 characters");
    assertCommitted(0);

    assertThat(executor.execute(actorId, key, operation(), null, this::writeCommand))
        .isEqualTo(aggregateId.toString());
    assertThat(commandInvocations).hasValue(2);
    assertThat(publisherInvocations).hasValue(2);
    assertCommitted(1);
  }

  @Test
  void failedCommandRollsBackDomainOutboxAndReceiptAndCanBeRetried() {
    String key = UUID.randomUUID().toString();
    var failure = new IllegalStateException("command failed after publishing");

    assertThatThrownBy(
            () ->
                executor.execute(
                    actorId,
                    key,
                    operation(),
                    null,
                    () -> {
                      writeCommand();
                      throw failure;
                    }))
        .isSameAs(failure);
    assertCommitted(0);

    assertThat(executor.execute(actorId, key, operation(), null, this::writeCommand))
        .isEqualTo(aggregateId.toString());
    assertCommitted(1);
    assertThat(commandInvocations).hasValue(2);
    assertThat(publisherInvocations).hasValue(2);
  }

  @Test
  void legacyCommandsStillCommitOrRollBackDomainAndOutboxTogetherWithoutReceipts() {
    assertThatThrownBy(
            () ->
                executor.execute(
                    actorId,
                    null,
                    operation(),
                    null,
                    () -> {
                      writeCommand();
                      throw new IllegalStateException("legacy command failed");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertCommitted(0);

    assertThat(executor.execute(actorId, null, operation(), null, this::writeCommand))
        .isEqualTo(aggregateId.toString());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_test_effects where id = ?",
                Integer.class,
                aggregateId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?",
                Integer.class,
                aggregateId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from request_idempotency where actor_id = ?",
                Integer.class,
                actorId))
        .isZero();
  }

  @Test
  void joinsOuterTransactionAndRollsBackReceiptWhenCallerFails() {
    String key = UUID.randomUUID().toString();
    var transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      executor.execute(actorId, key, operation(), null, this::writeCommand);
                      entityManager.flush();
                      assertCommitted(1);
                      throw new IllegalStateException("caller failed");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("caller failed");
    assertCommitted(0);

    executor.execute(actorId, key, operation(), null, this::writeCommand);
    assertCommitted(1);
  }

  @Test
  void commitTimeOutboxConstraintFailureRollsBackTheSuccessfulCommandAndReceipt() {
    String key = UUID.randomUUID().toString();

    assertThatThrownBy(
            () ->
                executor.execute(
                    actorId,
                    key,
                    operation(),
                    null,
                    () -> {
                      String result = writeCommand();
                      enqueueEvent();
                      return result;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(commandInvocations).hasValue(1);
    assertThat(publisherInvocations).hasValue(2);
    assertCommitted(0);

    executor.execute(actorId, key, operation(), null, this::writeCommand);
    assertCommitted(1);
  }

  @Test
  void concurrentDuplicatesWaitForCommitThenReplayWithoutPublishingAgain() throws Exception {
    assertConcurrentDuplicate(false);
  }

  @Test
  void concurrentDuplicateExecutesAfterTheFirstTransactionRollsBack() throws Exception {
    assertConcurrentDuplicate(true);
  }

  private void assertConcurrentDuplicate(boolean failFirst) throws Exception {
    String key = UUID.randomUUID().toString();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var duplicateStarted = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      try {
        var first =
            threads.submit(
                () ->
                    executor.execute(
                        actorId,
                        key,
                        operation(),
                        null,
                        () -> {
                          String result = writeCommand();
                          entered.countDown();
                          await(release);
                          if (failFirst) {
                            throw new IllegalStateException("first attempt failed");
                          }
                          return result;
                        }));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        var duplicate =
            threads.submit(
                () -> {
                  duplicateStarted.countDown();
                  return executor.execute(actorId, key, operation(), null, this::writeCommand);
                });
        assertThat(duplicateStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> duplicate.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        assertThat(commandInvocations).hasValue(1);
        assertCommitted(0);
        release.countDown();
        if (failFirst) {
          assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS))
              .hasRootCauseMessage("first attempt failed");
        } else {
          assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(aggregateId.toString());
        }
        assertThat(duplicate.get(10, TimeUnit.SECONDS)).isEqualTo(aggregateId.toString());
        assertThat(commandInvocations).hasValue(failFirst ? 2 : 1);
        assertThat(publisherInvocations).hasValue(failFirst ? 2 : 1);
        assertCommitted(1);
      } finally {
        release.countDown();
      }
    }
  }

  private String operation() {
    return "POST /articles/" + aggregateId + "/approve";
  }

  private String writeCommand() {
    commandInvocations.incrementAndGet();
    jdbc.update("insert into idempotency_test_effects (id) values (?)", aggregateId);
    enqueueEvent();
    return aggregateId.toString();
  }

  private void enqueueEvent() {
    publisherInvocations.incrementAndGet();
    publisher.enqueue(
        "ArticleApproved",
        aggregateId,
        aggregateId,
        null,
        "idempotency-test:" + aggregateId,
        new ArticleApproved(aggregateId, actorId));
  }

  private void assertCommitted(int count) {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_test_effects where id = ?",
                Integer.class,
                aggregateId))
        .isEqualTo(count);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?",
                Integer.class,
                aggregateId))
        .isEqualTo(count);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from request_idempotency where actor_id = ?",
                Integer.class,
                actorId))
        .isEqualTo(count);
  }

  private void assertConflict(Supplier<String> command) {
    assertThatThrownBy(command::get)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent request");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  @TestConfiguration
  static class Configuration {
    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager();
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    Validator validator() {
      return Validation.buildDefaultValidatorFactory().getValidator();
    }
  }
}
