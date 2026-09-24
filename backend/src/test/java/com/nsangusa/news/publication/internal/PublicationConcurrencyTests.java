package com.nsangusa.news.publication.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.publication.PublicationService;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@Import({
  PublicationApplicationService.class,
  ScheduledPublicationExecutor.class,
  PublicationPolicyEvaluator.class,
  PublicationConcurrencyTests.Services.class
})
class PublicationConcurrencyTests {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired PublicationService publication;
  @Autowired ScheduledPublicationRepository schedules;
  @Autowired ScheduledPublicationExecutor executor;
  @Autowired ArticleService articles;
  @Autowired JdbcTemplate jdbc;
  @Autowired DurableCommandExecutor commands;
  @MockitoBean SourceIngestionService sources;
  @MockitoBean DurableEventPublisher events;

  @BeforeEach
  void transactionalOutbox() {
    when(events.enqueue(anyString(), any(), any(), any(), anyString(), any()))
        .thenAnswer(
            call -> {
              UUID id = UUID.randomUUID();
              jdbc.update(
                  """
              insert into outbox_events
                (id,event_type,aggregate_id,correlation_id,causation_id,idempotency_key,envelope_json,created_at)
              values (?,?,?,?,?,?,'{}',now())
              """,
                  id,
                  call.getArgument(0),
                  call.getArgument(1),
                  call.getArgument(2),
                  call.getArgument(3),
                  call.getArgument(4));
              return id;
            });
  }

  @Test
  void keyedControllerMutationsReplayWithoutRepeatedArticleRevisionsOrScheduleChanges() {
    var fixture = article();
    var controller = new PublicationController(publication, commands);
    java.security.Principal principal = () -> "publication-editor";
    String createKey = UUID.randomUUID().toString();
    var createRequest = new PublicationController.ScheduleRequest(Instant.now().plusSeconds(300));
    UUID id =
        controller
            .schedule(fixture.articleId(), createRequest, principal, createKey)
            .getBody()
            .scheduleId();
    assertThat(
            controller
                .schedule(fixture.articleId(), createRequest, principal, createKey)
                .getBody()
                .scheduleId())
        .isEqualTo(id);
    assertThat(articles.get(fixture.articleId()).version()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select result from request_idempotency where idempotency_key = ?",
                String.class,
                createKey))
        .isEqualTo(id.toString());
    String changeKey = UUID.randomUUID().toString();
    var changeRequest =
        new PublicationController.RescheduleRequest(
            publication.get(id).version(), Instant.now().plusSeconds(600));
    var changed = controller.reschedule(id, changeRequest, principal, changeKey);
    assertThat(controller.reschedule(id, changeRequest, principal, changeKey)).isEqualTo(changed);
    assertThatThrownBy(
            () ->
                controller.reschedule(
                    id,
                    new PublicationController.RescheduleRequest(
                        changed.version(), changeRequest.publishAt()),
                    principal,
                    changeKey))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("409");
    String cancelKey = UUID.randomUUID().toString();
    var cancelRequest = new PublicationController.CancelRequest(changed.version());
    var cancelled = controller.cancel(id, cancelRequest, principal, cancelKey);
    assertThat(controller.cancel(id, cancelRequest, principal, cancelKey)).isEqualTo(cancelled);
    assertThat(controller.reschedule(id, changeRequest, principal, changeKey)).isEqualTo(cancelled);
    assertThat(articles.get(fixture.articleId()).version()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from article_revisions where article_id = ?",
                Integer.class,
                fixture.articleId()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where target_id = ?", Integer.class, id))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select result from request_idempotency where idempotency_key = ?",
                String.class,
                cancelKey))
        .isNull();
  }

  @Test
  void failedInitialRequestLeavesNoReceiptAndFutureValidationStillApplies() {
    var fixture = article();
    var controller = new PublicationController(publication, commands);
    java.security.Principal principal = () -> "publication-editor";
    String key = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                controller.schedule(
                    fixture.articleId(),
                    new PublicationController.ScheduleRequest(Instant.now().minusSeconds(1)),
                    principal,
                    key))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("future");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from request_idempotency where idempotency_key = ?",
                Integer.class,
                key))
        .isZero();
    assertThat(articles.get(fixture.articleId()).state()).isEqualTo(ArticleState.APPROVED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from article_revisions where article_id = ?",
                Integer.class,
                fixture.articleId()))
        .isZero();
    var result =
        controller.schedule(
            fixture.articleId(),
            new PublicationController.ScheduleRequest(Instant.now().plusSeconds(300)),
            principal,
            key);
    assertThat(result.getBody().scheduleId()).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from request_idempotency where idempotency_key = ?",
                Integer.class,
                key))
        .isEqualTo(1);
  }

  @Test
  void realScheduleChangesCaptureArticleVersionRetainActorAndRejectStaleWrites() {
    var fixture = article();
    UUID actor = UUID.randomUUID();
    UUID otherEditor = UUID.randomUUID();
    UUID id = publication.schedule(fixture.articleId(), Instant.now().plusSeconds(300), actor);
    var initial = publication.list(null, fixture.articleId(), 0, 10).items().getFirst();
    assertThat(initial.articleVersion()).isEqualTo(articles.get(fixture.articleId()).version());
    assertThat(initial.articleVersion()).isEqualTo(1);
    assertThat(initial.scheduledBy()).isEqualTo(actor);
    assertThatThrownBy(
            () ->
                publication.schedule(
                    fixture.articleId(), Instant.now().plusSeconds(600), otherEditor))
        .isInstanceOf(IllegalStateException.class);
    var changed =
        publication.reschedule(id, initial.version(), Instant.now().plusSeconds(600), otherEditor);
    assertThat(changed.version()).isGreaterThan(initial.version());
    assertThat(changed.scheduledBy()).isEqualTo(actor);
    assertThat(changed.updatedBy()).isEqualTo(otherEditor);
    assertThatThrownBy(() -> publication.cancel(id, initial.version(), otherEditor))
        .isInstanceOf(OptimisticLockingFailureException.class);
    var cancelled = publication.cancel(id, changed.version(), otherEditor);
    assertThat(cancelled.status()).isEqualTo("cancelled");
    assertThat(articles.get(fixture.articleId()).state()).isEqualTo(ArticleState.APPROVED);
    assertThat(articles.get(fixture.articleId()).version()).isGreaterThan(initial.articleVersion());
    assertThat(publication.list("scheduled", fixture.articleId(), 0, 10).total()).isZero();
    assertThat(publication.list(null, null, 0, 1).items()).hasSize(1);
    assertThat(
            jdbc.queryForList(
                "select action from audit_records where target_id = ?", String.class, id))
        .containsExactlyInAnyOrder(
            "PUBLICATION_SCHEDULE_CREATED",
            "PUBLICATION_SCHEDULE_CHANGED",
            "PUBLICATION_SCHEDULE_CANCELLED");
  }

  @Test
  void replicasSkipLockedSchedulesAndCommitOnlyOnePublicationAndOutboxEvent() throws Exception {
    var fixture = article();
    UUID actor = UUID.randomUUID();
    UUID id = due(fixture, actor);
    var holding = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            ignored -> {
              holding.countDown();
              await(release);
              return null;
            })
        .when(sources)
        .assertSourcesPublishable(List.of(fixture.sourceId()));
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> executor.execute(id, Instant.now()));
      try {
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        var second = workers.submit(() -> executor.execute(id, Instant.now()));
        second.get(5, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
      first.get(10, TimeUnit.SECONDS);
    }
    executor.execute(id, Instant.now());
    assertThat(schedule(id).status()).isEqualTo("published");
    assertThat(schedule(id).attemptCount()).isEqualTo(1);
    assertThat(articles.get(fixture.articleId()).state()).isEqualTo(ArticleState.PUBLISHED);
    assertThat(publicationEvents(fixture.articleId())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select actor_id from audit_records where target_id = ? and action = 'ARTICLE_PUBLISHED'",
                UUID.class,
                fixture.articleId()))
        .isEqualTo(actor);
  }

  @Test
  void invalidSourceDoesNotRollBackAnotherDueArticleAndFailedScheduleCanBeRetried() {
    var invalid = article();
    var valid = article();
    UUID badSchedule = due(invalid, UUID.randomUUID());
    UUID goodSchedule = due(valid, UUID.randomUUID());
    doAnswer(
            call -> {
              if (((List<?>) call.getArgument(0)).contains(invalid.sourceId())) {
                throw new IllegalStateException("Source is no longer eligible");
              }
              return null;
            })
        .when(sources)
        .assertSourcesPublishable(any());

    new PublicationScheduler(schedules, executor, 100).publishDue();

    var failed = schedule(badSchedule);
    assertThat(failed.status()).isEqualTo("failed");
    assertThat(failed.lastError()).contains("Source is no longer eligible");
    assertThat(failed.lastAttemptAt()).isNotNull();
    assertThat(failed.attemptCount()).isEqualTo(1);
    assertThat(publicationEvents(invalid.articleId())).isZero();
    assertThat(schedule(goodSchedule).status()).isEqualTo("published");
    assertThat(publicationEvents(valid.articleId())).isEqualTo(1);
    var retry =
        publication.reschedule(
            badSchedule, failed.version(), Instant.now().plusSeconds(300), UUID.randomUUID());
    assertThat(retry.status()).isEqualTo("scheduled");
    assertThat(retry.lastError()).isEqualTo(failed.lastError());
    assertThat(retry.scheduledBy()).isEqualTo(failed.scheduledBy());
  }

  @Test
  void changedApprovedArticleVersionIsNeverPublishedByAnOldSchedule() {
    var fixture = article();
    UUID id = due(fixture, UUID.randomUUID());
    jdbc.update(
        "update articles set body = 'Changed after approval', version = version + 1 where id = ?",
        fixture.articleId());
    executor.execute(id, Instant.now());
    assertThat(schedule(id).status()).isEqualTo("failed");
    assertThat(schedule(id).lastError()).contains("article version changed");
    assertThat(publicationEvents(fixture.articleId())).isZero();
  }

  @Test
  void aFailureAfterPublicationRollsBackArticleRevisionAuditAndOutboxBeforeRecordingTheError() {
    var fixture = article();
    UUID id = due(fixture, UUID.randomUUID());
    doAnswer(
            call -> {
              jdbc.update(
                  """
          insert into outbox_events
            (id,event_type,aggregate_id,correlation_id,causation_id,idempotency_key,envelope_json,created_at)
          values (?,?,?,?,?,?,'{}',now())
          """,
                  UUID.randomUUID(),
                  call.getArgument(0),
                  call.getArgument(1),
                  call.getArgument(2),
                  call.getArgument(3),
                  call.getArgument(4));
              throw new IllegalStateException("Publication event persistence failed");
            })
        .when(events)
        .enqueue(anyString(), any(), any(), any(), anyString(), any());

    executor.execute(id, Instant.now());

    assertThat(schedule(id).status()).isEqualTo("failed");
    assertThat(schedule(id).lastError()).contains("Publication event persistence failed");
    assertThat(articles.get(fixture.articleId()).state()).isEqualTo(ArticleState.SCHEDULED);
    assertThat(articles.get(fixture.articleId()).version())
        .isEqualTo(schedule(id).articleVersion());
    assertThat(publicationEvents(fixture.articleId())).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from article_revisions where article_id = ? and reason = 'PUBLISHED'",
                Integer.class,
                fixture.articleId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where target_id = ? and action = 'ARTICLE_PUBLISHED'",
                Integer.class,
                fixture.articleId()))
        .isZero();
  }

  @Test
  void articleChangeRacingPublicationWaitsForTheLockedApprovedVersion() throws Exception {
    var fixture = article();
    UUID id = due(fixture, UUID.randomUUID());
    long expectedArticleVersion = schedule(id).articleVersion();
    var checked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            ignored -> {
              checked.countDown();
              await(release);
              return null;
            })
        .when(sources)
        .assertSourcesPublishable(List.of(fixture.sourceId()));
    try (var workers = Executors.newFixedThreadPool(2)) {
      var publicationTask = workers.submit(() -> executor.execute(id, Instant.now()));
      java.util.concurrent.Future<Integer> update = null;
      try {
        assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
        update =
            workers.submit(
                () ->
                    jdbc.update(
                        "update articles set body = 'Concurrent editorial change', version = version + 1 where id = ? and version = ?",
                        fixture.articleId(),
                        expectedArticleVersion));
        var blockedUpdate = update;
        assertThatThrownBy(() -> blockedUpdate.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      publicationTask.get(10, TimeUnit.SECONDS);
      assertThat(update.get(10, TimeUnit.SECONDS)).isZero();
    }
    assertThat(schedule(id).status()).isEqualTo("published");
    assertThat(schedule(id).lastError()).isNull();
    assertThat(articles.get(fixture.articleId()).body()).isEqualTo("Body");
    assertThat(articles.get(fixture.articleId()).state()).isEqualTo(ArticleState.PUBLISHED);
    assertThat(publicationEvents(fixture.articleId())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where target_id = ? and action = 'ARTICLE_PUBLISHED'",
                Integer.class,
                fixture.articleId()))
        .isEqualTo(1);
  }

  private UUID due(Fixture fixture, UUID actor) {
    UUID id = publication.schedule(fixture.articleId(), Instant.now().plusSeconds(300), actor);
    jdbc.update(
        "update scheduled_publications set scheduled_for = now() - interval '1 minute' where id = ?",
        id);
    return id;
  }

  private PublicationService.ScheduleView schedule(UUID id) {
    return schedules.findById(id).orElseThrow().view();
  }

  private int publicationEvents(UUID articleId) {
    return jdbc.queryForObject(
        "select count(*) from outbox_events where aggregate_id = ? and event_type = 'ArticlePublished'",
        Integer.class,
        articleId);
  }

  private Fixture article() {
    UUID account = UUID.randomUUID();
    UUID source = UUID.randomUUID();
    UUID article = UUID.randomUUID();
    String handle = "pub" + account.toString().replace("-", "").substring(0, 12);
    jdbc.update(
        """
        insert into monitored_x_accounts
          (id,account_id,handle,display_name,topics,relevance_threshold,monitoring_enabled,created_at)
        values (?,?,?,'Publisher','world',0.5,true,now())
        """,
        account,
        account.toString(),
        handle);
    jdbc.update(
        """
        insert into source_posts
          (id,monitored_account_id,post_id,account_id,handle,canonical_url,permitted_text,published_at,ingested_at,status)
        values (?,?,?,?,?,?,'A source reports news',now(),now(),'active')
        """,
        source,
        account,
        source.toString(),
        account.toString(),
        handle,
        "https://x.com/" + handle + "/status/" + source);
    jdbc.update(
        """
        insert into articles
          (id,slug,headline,summary,body,seo_title,seo_description,topic,tags,state,confidence,warnings,created_at,updated_at)
        values (?,?,'Headline','Summary','Body','Headline','Summary','world','','APPROVED',1,'',now(),now())
        """,
        article,
        "publication-" + article);
    jdbc.update(
        """
        insert into article_sources(id,article_id,source_post_id,account,post_id,url,published_at)
        select ?,?,id,handle,post_id,canonical_url,published_at from source_posts where id = ?
        """,
        UUID.randomUUID(),
        article,
        source);
    return new Fixture(article, source);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(15, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting for concurrent operation");
    }
  }

  record Fixture(UUID articleId, UUID sourceId) {}

  @TestConfiguration(proxyBeanMethods = false)
  @ComponentScan(
      basePackageClasses = {ArticleService.class, AuditService.class, DurableCommandExecutor.class},
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {ArticleService.class, AuditService.class, DurableCommandExecutor.class}))
  static class Services {
    @Bean
    org.springframework.cache.CacheManager cacheManager() {
      return new org.springframework.cache.support.NoOpCacheManager();
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }
  }
}
