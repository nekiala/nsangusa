package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.nsangusa.news.eventprocessing.EventOperations.ReplayCommand;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({
  EventOperationsService.class,
  WorkflowHealthQuery.class,
  ReplayConfirmationIntegrationTests.Beans.class
})
class ReplayConfirmationIntegrationTests {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired EventOperationsService service;
  @Autowired WorkflowHealthQuery health;
  @Autowired FailedEventRepository failures;
  @Autowired EventReplayRequestRepository requests;
  @Autowired EventReplayItemRepository items;
  @Autowired EntityManager entityManager;

  @Test
  void confirmationIsPersistentAndCannotCreateAnotherReplayForTheSamePreview() {
    var failure = failure();
    UUID actor = UUID.randomUUID();
    var preview = service.requestReplay(command(failure.id, actor));
    assertThat(items.count()).isZero();
    var confirmed = service.confirmReplay(preview.id(), actor);
    entityManager.flush();
    entityManager.clear();
    assertThat(service.confirmReplay(preview.id(), actor).id()).isEqualTo(confirmed.id());
    assertThat(requests.count()).isEqualTo(2);
    assertThat(items.count()).isEqualTo(1);
    assertThat(service.failedPage("replay_pending", 0, 20).total()).isEqualTo(1);
    assertThat(health.snapshot().pendingReplays()).isEqualTo(1);
    assertThat(health.snapshot().failedEvents()).containsEntry("replay_pending", 1L);
  }

  @Test
  void previewsDiscloseComplianceBlocksWithoutQueuingThem() {
    var failure = failure();
    UUID actor = UUID.randomUUID();
    service.suppressAggregate(failure.aggregateId, "source removed", actor);
    var preview = service.requestReplay(command(failure.id, actor));
    assertThat(preview.blockedCount()).isEqualTo(1);
    assertThat(service.listReplayRecords(preview.id()))
        .singleElement()
        .satisfies(record -> assertThat(record.outcome()).isEqualTo("blocked"));
    assertThatThrownBy(() -> service.confirmReplay(preview.id(), actor))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no eligible");
    assertThat(items.count()).isZero();
  }

  @Test
  void confirmationRequiresThePreviewOwnerAndFreshReview() {
    var failure = failure();
    UUID actor = UUID.randomUUID();
    var preview = service.requestReplay(command(failure.id, actor));
    assertThatThrownBy(() -> service.confirmReplay(preview.id(), UUID.randomUUID()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    requests.findById(preview.id()).orElseThrow().requestedAt = Instant.now().minusSeconds(901);
    assertThatThrownBy(() -> service.confirmReplay(preview.id(), actor))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expired");
    assertThat(items.count()).isZero();
  }

  @Test
  void replicasCannotClaimAReplayBeforeItsSharedRateWindow() {
    var request =
        requests.save(
            new EventReplayRequest(
                UUID.randomUUID(), "rate test", false, false, 1, 0, null, null, "test"));
    request.nextReplayAt = Instant.now().plusSeconds(60);
    entityManager.flush();
    entityManager.clear();
    assertThat(
            requests.findByStatusInOrderByRequestedAtAsc(
                java.util.List.of("pending", "processing"),
                org.springframework.data.domain.PageRequest.of(0, 1)))
        .isEmpty();
    requests.findById(request.id).orElseThrow().nextReplayAt = Instant.now().minusSeconds(1);
    entityManager.flush();
    entityManager.clear();
    assertThat(
            requests.findByStatusInOrderByRequestedAtAsc(
                java.util.List.of("pending", "processing"),
                org.springframework.data.domain.PageRequest.of(0, 1)))
        .hasSize(1);
  }

  @Test
  void operationalCountsDoNotTruncateToTheLegacyTwoHundredRowLimit() {
    for (int index = 0; index < 305; index++) failure();
    entityManager.flush();
    assertThat(health.snapshot().failedEvents()).containsEntry("eligible", 305L);
    var secondPage = service.failedPage("eligible", 1, 100);
    assertThat(secondPage.total()).isEqualTo(305);
    assertThat(secondPage.items()).hasSize(100);
  }

  private FailedEvent failure() {
    return failures.save(
        new FailedEvent(
            UUID.randomUUID(),
            "StoryAnalysisRequested",
            UUID.randomUUID(),
            "news.editorial.v1.dlt",
            0,
            System.nanoTime(),
            "news.editorial.v1",
            0,
            10,
            "editorial",
            "{}",
            "SyntheticFailure",
            "Synthetic local error",
            3,
            false));
  }

  private ReplayCommand command(UUID id, UUID actor) {
    return new ReplayCommand(
        Set.of(id),
        null,
        null,
        10,
        1,
        true,
        false,
        actor,
        "Reviewed synthetic provider failure",
        "test");
  }

  @TestConfiguration
  static class Beans {
    @Bean
    KafkaTemplate<String, String> kafkaTemplate() {
      return mock(KafkaTemplate.class);
    }

    @Bean
    org.springframework.cache.CacheManager cacheManager() {
      return new org.springframework.cache.support.NoOpCacheManager();
    }
  }
}
