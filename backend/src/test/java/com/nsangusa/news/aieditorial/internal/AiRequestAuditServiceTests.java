package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisResult;
import com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftRequested;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import com.nsangusa.news.integration.NewsEvents.StoryAnalysisRequested;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "news.providers.ai.daily-token-budget=150000",
      "news.providers.mode=fake"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@Import({
  AiRequestAuditService.class,
  AiRequestAuditServiceTests.JsonConfiguration.class,
  EditorialWorkflowConsumer.class,
  FakeEditorialProvider.class,
  AiAdministrationApplicationService.class,
  DeployedEditorialCatalog.class,
  AiProviderCallExecutor.class,
  AiUsageMetrics.class,
  EditorialSemanticValidator.class
})
class AiRequestAuditServiceTests {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired AiRequestAuditService audit;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired EditorialWorkflowConsumer workflow;
  @MockitoBean IncomingEventReader reader;
  @MockitoBean ProcessedEventRegistry processed;
  @MockitoBean DurableEventPublisher events;
  @MockitoBean SourceIngestionService ingestion;
  @MockitoBean com.nsangusa.news.audit.AuditService administrationAudit;

  @Test
  void actualSpringWorkflowPersistsTypedFakeAnalysisAndDraftWithRequiredWarnings() {
    UUID storyId = story();
    UUID sourceId =
        jdbc.queryForObject(
            "select primary_source_post_id from story_candidates where id = ?",
            UUID.class,
            storyId);
    Instant published =
        jdbc.queryForObject(
                "select published_at from source_posts where id = ?",
                java.sql.Timestamp.class,
                sourceId)
            .toInstant();
    var source =
        new SourceReference(
            sourceId,
            "account",
            sourceId.toString(),
            "https://x.com/account/status/123",
            published);
    when(ingestion.getSource(sourceId))
        .thenReturn(
            new SourceIngestionService.SourceView(
                sourceId,
                UUID.randomUUID(),
                source.postId(),
                "account-id",
                source.account(),
                source.url(),
                "A source reports a public development.",
                published,
                Instant.now(),
                "active",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                0));
    var analysisEvent =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "StoryAnalysisRequested",
            1,
            storyId,
            storyId,
            null,
            Instant.now(),
            "test",
            Map.of(),
            "analysis:" + storyId,
            new StoryAnalysisRequested(
                storyId, List.of(source), "untrusted replaced event material"));
    when(reader.eventType("analysis")).thenReturn("StoryAnalysisRequested");
    when(reader.read("analysis", StoryAnalysisRequested.class)).thenReturn(analysisEvent);
    workflow.analyze("analysis");
    var requested = org.mockito.ArgumentCaptor.forClass(ArticleDraftRequested.class);
    verify(events)
        .enqueue(eq("ArticleDraftRequested"), any(), any(), any(), any(), requested.capture());
    var draftEvent =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "ArticleDraftRequested",
            1,
            storyId,
            storyId,
            analysisEvent.eventId(),
            Instant.now(),
            "test",
            Map.of(),
            "draft:" + storyId,
            requested.getValue());
    when(reader.eventType("draft")).thenReturn("ArticleDraftRequested");
    when(reader.read("draft", ArticleDraftRequested.class)).thenReturn(draftEvent);
    workflow.draft("draft");
    var generated = org.mockito.ArgumentCaptor.forClass(ArticleDraftGenerated.class);
    verify(events)
        .enqueue(eq("ArticleDraftGenerated"), any(), any(), any(), any(), generated.capture());
    assertThat(generated.getValue().humanReviewRequired()).isTrue();
    assertThat(generated.getValue().uncertaintyNotes())
        .contains("independent-confirmation-required");
    assertThat(generated.getValue().headline()).doesNotContain("untrusted replaced event material");
    assertThat(audit.list(storyId)).hasSize(4);
    assertThat(
            audit.list(storyId).stream()
                .filter(request -> request.operation().equals("draft"))
                .findFirst()
                .orElseThrow()
                .result())
        .isEqualTo(generated.getValue());
    assertThat(audit.list(storyId).getFirst().operation()).isEqualTo("draft-safety");
  }

  @Test
  void failedAndSuccessfulAttemptsSurviveOuterRollbackAndCompletedResultCanBeReused() {
    UUID storyId = story();
    UUID eventId = UUID.randomUUID();
    var configuration =
        new ProviderConfiguration("fake", "deterministic-editorial-v1", "prompt-v3");
    var transaction = new TransactionTemplate(transactions);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      UUID request = audit.begin(eventId, storyId, "analysis", configuration);
                      audit.fail(
                          request,
                          new AiProviderException("malformed_output")
                              .withUsage("deterministic-editorial-v1", 123, 45),
                          null);
                      throw new IllegalStateException("generation rolled back");
                    }))
        .hasMessageContaining("rolled back");
    var failed = audit.list(storyId).getFirst();
    assertThat(failed.status()).isEqualTo("failed");
    assertThat(failed.errorCode()).isEqualTo("malformed_output");
    assertThat(failed.promptVersion()).isEqualTo("prompt-v3");
    assertThat(failed.inputTokens()).isEqualTo(123);
    assertThat(failed.outputTokens()).isEqualTo(45);
    assertThat(failed.result()).isNull();

    var result =
        new AnalysisResult(
            "A reported development",
            List.of(new Claim("A claim", "REPORTED", List.of(UUID.randomUUID()))),
            new BigDecimal("0.6"),
            List.of("human-review-required"),
            "fake",
            "deterministic-editorial-v1",
            120,
            40,
            "prompt-v3",
            Instant.now());
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      UUID request = audit.begin(eventId, storyId, "analysis", configuration);
                      audit.complete(request, result);
                      throw new IllegalStateException("outbox rolled back");
                    }))
        .hasMessageContaining("outbox rolled back");
    assertThat(audit.completed(eventId, "analysis", AnalysisResult.class)).contains(result);
    var completed = audit.list(storyId).getFirst();
    assertThat(completed.status()).isEqualTo("completed");
    assertThat(completed.result()).isInstanceOf(AnalysisResult.class).isEqualTo(result);
    assertThat(audit.list(storyId)).hasSize(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ai_results where request_id = ?",
                Integer.class,
                completed.id()))
        .isEqualTo(1);
  }

  @Test
  void safetyAnalysisDraftAndRetriesPreserveCapturedConfigurationAcrossAdministrativeChanges() {
    UUID storyId = story();
    UUID analysisEvent = UUID.randomUUID();
    UUID draftEvent = UUID.randomUUID();
    var original =
        new ProviderConfiguration(
            "fake",
            "deterministic-editorial-v1",
            "editorial-guidance-v2",
            7,
            "Preserve original neutral guidance.",
            "none:local-fake");
    var changed =
        new ProviderConfiguration(
            "openai",
            "gpt-5-mini-2025-08-07",
            "editorial-guidance-v3",
            8,
            "New guidance for new workflows.",
            "encrypted:ai:4");
    audit.begin(analysisEvent, storyId, "safety", original);
    assertThat(audit.workflowSnapshot(analysisEvent, storyId, null, "analysis", () -> changed))
        .isEqualTo(original);
    audit.begin(analysisEvent, storyId, "analysis", original);
    assertThat(audit.workflowSnapshot(draftEvent, storyId, analysisEvent, "draft", () -> changed))
        .isEqualTo(original);
    audit.begin(draftEvent, storyId, "draft", original);
    for (String operation : List.of("draft", "draft-safety")) {
      assertThat(
              audit.workflowSnapshot(
                  draftEvent,
                  storyId,
                  analysisEvent,
                  operation,
                  () -> {
                    throw new AssertionError(
                        "Captured workflow must not read a newer provider selection");
                  }))
          .isEqualTo(original);
    }
    assertThatThrownBy(
            () ->
                audit.workflowSnapshot(
                    UUID.randomUUID(), storyId, UUID.randomUUID(), "draft", () -> changed))
        .isInstanceOf(AiProviderException.class)
        .hasMessageContaining("analysis_configuration_unavailable");
    assertThatThrownBy(
            () ->
                audit.workflowSnapshot(
                    UUID.randomUUID(), UUID.randomUUID(), analysisEvent, "draft", () -> changed))
        .isInstanceOf(AiProviderException.class)
        .hasMessageContaining("analysis_configuration_unavailable");
  }

  @Test
  void databaseTokenReservationsBoundConcurrentAndUnknownOutcomeSpending() {
    UUID story = story();
    var config = new ProviderConfiguration("openai", "gpt-5-mini", "editorial-v1");
    UUID first = audit.begin(UUID.randomUUID(), story, "analysis", config);
    assertThat(audit.budgetExhausted(first)).isFalse();
    audit.fail(first, new org.springframework.web.client.ResourceAccessException("timeout"), null);
    UUID second = audit.begin(UUID.randomUUID(), story, "draft", config);
    assertThat(audit.budgetExhausted(second)).isTrue();
    audit.fail(second, new AiProviderException("daily_token_budget"), null);
  }

  @Test
  void sourceChangesRemoveResultContentAndPreventInFlightResultsFromReappearing() {
    UUID story = story();
    var config = new ProviderConfiguration("fake", "deterministic-editorial-v1", "editorial-v1");
    UUID sourceId =
        jdbc.queryForObject(
            "select primary_source_post_id from story_candidates where id = ?", UUID.class, story);
    var result =
        new AnalysisResult(
            "Source-derived analysis",
            List.of(new Claim("Source-derived claim", "REPORTED", List.of(sourceId))),
            new BigDecimal("0.6"),
            List.of("human-review-required"),
            "fake",
            "deterministic-editorial-v1",
            120,
            40);
    UUID completed = audit.begin(UUID.randomUUID(), story, "analysis", config);
    audit.complete(completed, result);
    UUID inFlight = audit.begin(UUID.randomUUID(), story, "analysis", config);
    jdbc.update(
        "update source_posts set permitted_text = 'Corrected source' where id = ?", sourceId);
    assertThat(audit.list(story))
        .allSatisfy(
            request -> {
              assertThat(request.result()).isNull();
              assertThat(request.status()).isEqualTo("redacted");
              assertThat(request.provider()).isEqualTo("fake");
            });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ai_results where request_id = ?", Integer.class, completed))
        .isZero();
    assertThatThrownBy(() -> audit.complete(inFlight, result))
        .hasMessageContaining("source_content_changed");
  }

  @Test
  void blockedDraftSafetyReviewIsDurableAndReplayReusesTheSameGeneratedContent() {
    UUID storyId = story();
    UUID eventId = UUID.randomUUID();
    UUID sourceId =
        jdbc.queryForObject(
            "select primary_source_post_id from story_candidates where id = ?",
            UUID.class,
            storyId);
    var source =
        new SourceReference(
            sourceId,
            "account",
            sourceId.toString(),
            "https://x.com/account/status/123",
            Instant.now());
    var fake = new FakeEditorialProvider();
    var draft =
        fake.draft(
            new com.nsangusa.news.aieditorial.EditorialProviders.DraftRequest(
                storyId,
                List.of(source),
                "analysis",
                List.of(new Claim("A source report", "REPORTED", List.of(sourceId))),
                new BigDecimal("0.6"),
                List.of("human-review-required")));
    UUID requestId = audit.begin(eventId, storyId, "draft", fake.configuration());
    audit.complete(requestId, draft);
    audit.reviewDraft(eventId, draft, false);
    assertThat(audit.list(storyId).getFirst().status()).isEqualTo("blocked");
    assertThat(audit.list(storyId).getFirst().errorCode()).isEqualTo("generated_content_safety");
    assertThat(audit.list(storyId).getFirst().result()).isNull();
    assertThat(audit.completed(eventId, "draft", ArticleDraftGenerated.class)).contains(draft);
    audit.reviewDraft(eventId, draft, false);
    assertThat(audit.list(storyId)).hasSize(1);
    assertThat(audit.list(storyId).getFirst().status()).isEqualTo("blocked");
  }

  private UUID story() {
    UUID account = UUID.randomUUID();
    UUID source = UUID.randomUUID();
    UUID story = UUID.randomUUID();
    jdbc.update(
        """
        insert into monitored_x_accounts(id, account_id, handle, display_name, topics,
          relevance_threshold, monitoring_enabled, created_at)
        values (?, ?, ?, 'Account', 'world', 0, true, now())
        """,
        account,
        account.toString(),
        account.toString());
    jdbc.update(
        """
        insert into source_posts(id, monitored_account_id, post_id, account_id, handle,
          canonical_url, permitted_text, published_at, ingested_at, status, version)
        values (?, ?, ?, ?, 'account', 'https://x.com/account/status/123',
          'A source report', now(), now(), 'active', 0)
        """,
        source,
        account,
        source.toString(),
        account.toString());
    jdbc.update(
        """
        insert into story_candidates(id, primary_source_post_id, topic, status, created_at, version)
        values (?, ?, 'world', 'collecting', now(), 0)
        """,
        story,
        source);
    return story;
  }

  @TestConfiguration
  static class JsonConfiguration {
    @Bean
    io.micrometer.core.instrument.MeterRegistry meterRegistry() {
      return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    @Bean
    jakarta.validation.Validator validator() {
      return jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
    }

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
