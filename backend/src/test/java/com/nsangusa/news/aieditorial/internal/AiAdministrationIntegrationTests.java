package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.aieditorial.EditorialProviders.DraftRequest;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.Claim;
import com.nsangusa.news.integration.NewsEvents.SourceReference;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
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

@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "news.providers.mode=production",
      "news.providers.ai.authorized-models=gpt-5-mini,gpt-5-mini-2025-08-07"
    },
    showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@WithMockUser(roles = "ADMINISTRATOR")
@Import({
  AiAdministrationApplicationService.class,
  DeployedEditorialCatalog.class,
  AiRequestAuditService.class,
  AiAdministrationIntegrationTests.Configuration.class
})
class AiAdministrationIntegrationTests {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired AiAdministrationApplicationService administration;
  @Autowired AiRequestAuditService requests;
  @Autowired DurableCommandExecutor commands;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired PlatformTransactionManager transactions;

  @Test
  void selectedModelAndImmutableGuidanceReachRealResponsesRequestAndPersistedDraftProvenance()
      throws Exception {
    UUID actor = UUID.randomUUID();
    var baselinePrompt = administration.prompt("editorial-v1");
    long revision = administration.prompts(0, 1).getFirst().revision();
    String guidance = "Use short paragraphs, neutral language, and explicit uncertainty.";
    var promptInput =
        new AiAdministrationController.PromptRequest(revision, guidance, "Reviewed house style");
    String promptKey = UUID.randomUUID().toString();
    String prompt =
        commands.execute(
            actor,
            promptKey,
            "POST /api/v1/admin/ai-configuration/prompts",
            promptInput,
            () -> administration.createPrompt(revision, guidance, promptInput.reason(), actor));
    long version = administration.current().version();
    var input =
        new AiAdministrationController.SelectionRequest(
            version, "openai", "gpt-5-mini-2025-08-07", prompt, "Pin reviewed model and prompt");
    String selectionKey = UUID.randomUUID().toString();
    String selectedVersion =
        commands.execute(
            actor,
            selectionKey,
            "PUT /api/v1/admin/ai-configuration",
            input,
            () ->
                Long.toString(
                    administration.select(
                        version, input.provider(), input.model(), prompt, input.reason(), actor)));
    var snapshot = administration.snapshot();
    assertThat(snapshot.configurationVersion()).isEqualTo(Long.parseLong(selectedVersion));
    assertThat(snapshot.secretReference()).isEqualTo("encrypted:ai");

    UUID story = story();
    UUID source =
        jdbc.queryForObject(
            "select primary_source_post_id from story_candidates where id = ?", UUID.class, story);
    UUID event = UUID.randomUUID();
    UUID requestId = requests.begin(event, story, "draft", snapshot);
    var sent = new ArrayList<HttpRequest>();
    var provider = provider(source, sent);
    provider.useAdministration(administration);

    administration.select(
        snapshot.configurationVersion(),
        "openai",
        "gpt-5-mini",
        baselinePrompt.version(),
        "New requests use the built-in guidance",
        actor);
    assertThat(provider.configuration().promptVersion()).isEqualTo("editorial-v1");
    var result =
        provider.draft(
            new DraftRequest(
                story,
                List.of(
                    new SourceReference(
                        source,
                        "account",
                        "123",
                        "https://x.com/account/status/123",
                        Instant.now())),
                "A source reports a development",
                List.of(new Claim("A reported development", "REPORTED", List.of(source))),
                new BigDecimal("0.6"),
                List.of("single-source")),
            snapshot);
    requests.complete(requestId, result);

    JsonNode body = mapper.readTree(body(sent.getFirst()));
    assertThat(body.path("model").asText()).isEqualTo("gpt-5-mini-2025-08-07");
    assertThat(body.path("input").get(0).path("content").asText())
        .startsWith(PromptBoundary.SYSTEM_RULES)
        .contains("Always require human review")
        .doesNotContain(guidance);
    assertThat(body.path("input").get(1).path("content").asText())
        .contains(guidance, "editorialStyleGuidance");
    assertThat(body.has("tools")).isFalse();
    assertThat(sent.getFirst().uri()).isEqualTo(URI.create("https://api.openai.com/v1/responses"));
    assertThat(result.model()).isEqualTo(snapshot.model());
    assertThat(result.promptVersion()).isEqualTo(prompt);
    assertThat(result.humanReviewRequired()).isTrue();
    var stored = requests.list(story).getFirst();
    assertThat(stored.configuration()).isEqualTo(snapshot);
    assertThat(stored.result()).isEqualTo(result);
    assertThat(stored.inputTokens()).isEqualTo(120);
    assertThat(requests.snapshot(event, "draft", administration.snapshot())).isEqualTo(snapshot);
    assertThat(
            requests.snapshot(
                event,
                "draft",
                () -> {
                  throw new AssertionError(
                      "A durable request snapshot must not load newer configuration");
                }))
        .isEqualTo(snapshot);
    assertThat(requests.completed(event, "draft", ArticleDraftGenerated.class)).contains(result);
    assertThat(administration.prompt("editorial-v1")).isEqualTo(baselinePrompt);

    assertThat(
            commands.execute(
                actor,
                selectionKey,
                "PUT /api/v1/admin/ai-configuration",
                input,
                () -> {
                  throw new AssertionError("The exact successful selection must replay");
                }))
        .isEqualTo(selectedVersion);
    assertThatThrownBy(
            () ->
                commands.execute(
                    actor,
                    selectionKey,
                    "PUT /api/v1/admin/ai-configuration",
                    Map.of("expectedVersion", version, "model", "a different request"),
                    () -> {
                      throw new AssertionError("A reused key must reject a changed request");
                    }))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode().value())
                    .isEqualTo(409));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from request_idempotency where actor_id = ?",
                Integer.class,
                actor))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where actor_id = ? and action = 'AI_CONFIGURATION_SELECTED'",
                Integer.class,
                actor))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select guidance from ai_prompt_versions where version = ?", String.class, prompt))
        .isEqualTo(guidance);
  }

  @Test
  void staleUnsafeAndChangedKeyRequestsCannotMutateHistoryOrExistingApprovedArticles() {
    UUID actor = UUID.randomUUID();
    var before = administration.current();
    long next =
        administration.select(
            before.version(), "openai", "gpt-5-mini", "editorial-v1", "Reviewed baseline", actor);
    assertThatThrownBy(
            () ->
                administration.select(
                    before.version(), "openai", "gpt-5-mini", "editorial-v1", "Stale", actor))
        .isInstanceOf(OptimisticLockingFailureException.class);
    for (String model : List.of("unapproved", "https://127.0.0.1/model", "gpt-4o")) {
      assertThatThrownBy(
              () -> administration.select(next, "openai", model, "editorial-v1", "Unsafe", actor))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                administration.select(next, "fake", "gpt-5-mini", "editorial-v1", "Unsafe", actor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                administration.select(
                    next, "openai", "gpt-5-mini", "missing-prompt", "Unsafe", actor))
        .isInstanceOf(ResponseStatusException.class);
    long revision = administration.prompts(0, 1).getFirst().revision();
    assertThatThrownBy(
            () ->
                administration.createPrompt(
                    revision - 1, "Use neutral prose.", "Stale prompt", actor))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThatThrownBy(
            () ->
                administration.createPrompt(
                    revision, "Use key sk-secretsupplied123", "Unsafe prompt", actor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update ai_prompt_versions set guidance = 'Edited original guidance' where version = 'editorial-v1'"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbc.update("delete from ai_configuration_versions where version = ?", next))
        .isInstanceOf(DataAccessException.class);
    assertThat(administration.current().version()).isEqualTo(next);

    UUID article = UUID.randomUUID();
    jdbc.update(
        """
        insert into articles(id, slug, headline, summary, body, seo_title, seo_description, topic, tags,
          state, confidence, warnings, created_at, updated_at)
        values (?, ?, 'Reviewed headline', 'Summary', 'Reviewed body', 'Title', 'Description', 'world',
          '', 'APPROVED', 0.6, '', now(), now())
        """,
        article,
        "ai-config-test-" + article);
    var original =
        jdbc.queryForMap(
            "select state, headline, body, version, updated_at from articles where id = ?",
            article);
    administration.select(
        next, "openai", "gpt-5-mini-2025-08-07", "editorial-v1", "New requests only", actor);
    assertThat(
            jdbc.queryForMap(
                "select state, headline, body, version, updated_at from articles where id = ?",
                article))
        .isEqualTo(original);
  }

  @Test
  void configPromptAuditAndReceiptRollBackTogetherAndAuthorizationPrecedesAdministration() {
    UUID actor = UUID.randomUUID();
    var before = administration.current();
    String key = UUID.randomUUID().toString();
    var input = Map.of("expectedVersion", before.version(), "model", "gpt-5-mini");
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        status ->
                            commands.execute(
                                actor,
                                key,
                                "PUT /api/v1/admin/ai-configuration",
                                input,
                                () -> {
                                  administration.select(
                                      before.version(),
                                      "openai",
                                      "gpt-5-mini",
                                      "editorial-v1",
                                      "Must roll back",
                                      actor);
                                  throw new IllegalStateException("Simulated failed command");
                                })))
        .hasMessageContaining("Simulated failed command");
    assertThat(administration.current()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where actor_id = ?", Integer.class, actor))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from request_idempotency where actor_id = ?",
                Integer.class,
                actor))
        .isZero();
  }

  private ProductionEditorialProvider provider(UUID source, List<HttpRequest> sent)
      throws Exception {
    var draft = mapper.createObjectNode();
    for (String name :
        List.of(
            "headline",
            "summary",
            "body",
            "seoTitle",
            "seoDescription",
            "imagePrompt",
            "imageAltText",
            "socialPreviewText")) draft.put(name, "A reported development");
    draft.putNull("editorialContext");
    draft.put("slugSuggestion", "reported-development");
    draft.put("topic", "world");
    draft.putArray("tags").add("news");
    draft.putArray("sourceIds").add(source.toString());
    draft.set(
        "claims",
        mapper.valueToTree(
            List.of(new Claim("A reported development", "REPORTED", List.of(source)))));
    draft.put("confidence", 0.6);
    draft.putArray("uncertaintyNotes").add("single-source");
    draft.putArray("safetyFlags");
    draft.put("humanReviewRequired", false);
    byte[] responseBody =
        mapper.writeValueAsBytes(
            Map.of(
                "model",
                "gpt-5-mini-2025-08-07",
                "status",
                "completed",
                "usage",
                Map.of("input_tokens", 120, "output_tokens", 90),
                "output",
                List.of(
                    Map.of(
                        "type",
                        "message",
                        "role",
                        "assistant",
                        "status",
                        "completed",
                        "content",
                        List.of(
                            Map.of(
                                "type",
                                "output_text",
                                "text",
                                mapper.writeValueAsString(draft)))))));
    @SuppressWarnings("unchecked")
    HttpResponse<byte[]> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.headers())
        .thenReturn(
            HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true));
    when(response.body()).thenReturn(responseBody);
    return new ProductionEditorialProvider(
        mapper,
        URI.create("https://api.openai.com"),
        "test-only-key",
        "gpt-5-mini",
        "editorial-v1",
        Duration.ofSeconds(20),
        131072,
        262144,
        4096,
        (request, limit, timeout) -> {
          sent.add(request);
          return response;
        });
  }

  private static byte[] body(HttpRequest request) {
    var bytes = new ByteArrayOutputStream();
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<>() {
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              public void onNext(ByteBuffer value) {
                var chunk = new byte[value.remaining()];
                value.get(chunk);
                bytes.writeBytes(chunk);
              }

              public void onError(Throwable error) {
                throw new AssertionError(error);
              }

              public void onComplete() {}
            });
    return bytes.toByteArray();
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
        insert into source_posts(id, monitored_account_id, post_id, account_id, handle, canonical_url,
          permitted_text, published_at, ingested_at, status, version)
        values (?, ?, ?, ?, 'account', 'https://x.com/account/status/123', 'A source report', now(), now(), 'active', 0)
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
  @EnableMethodSecurity
  @ComponentScan(
      basePackages = {"com.nsangusa.news.audit", "com.nsangusa.news.eventprocessing"},
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {AuditService.class, DurableCommandExecutor.class}))
  static class Configuration {
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
