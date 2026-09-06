package com.nsangusa.news.newsletter.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import com.nsangusa.news.integration.NewsEvents.ArticlePublished;
import com.nsangusa.news.integration.NewsEvents.NewsletterDispatchRequested;
import com.nsangusa.news.integration.NewsEvents.XPostDiscovered;
import com.nsangusa.news.media.MediaService;
import com.nsangusa.news.media.ObjectStorage;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EditorialPublicationWorkflowIntegrationTests {
  private static final String COMPONENT_PATTERN =
      "com\\.nsangusa\\.news\\."
          + "(eventprocessing\\.internal\\.(JpaEventStore|JacksonIncomingEventReader)"
          + "|sourceingestion\\.internal\\.(SourceIngestionApplicationService|SourceNormalizationConsumer)"
          + "|storyprocessing\\.internal\\.StoryCandidateConsumer"
          + "|aieditorial\\.internal\\.(EditorialWorkflowConsumer|FakeEditorialProvider)"
          + "|articles\\.internal\\.(ArticleEventConsumer|ArticleApplicationService)"
          + "|media\\.internal\\.(ImageWorkflowConsumer|ImageVariantProcessor|MediaApplicationService|FakeImageGenerationProvider)"
          + "|audit\\.internal\\.AuditApplicationService"
          + "|publication\\.internal\\.PublicationConsumer"
          + "|newsletter\\.internal\\.(NewsletterWorkflowConsumer|NewsletterDeliveryReservationService|UnsubscribeTokenService))";

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4")
          .withDatabaseName("news")
          .withUsername("news")
          .withPassword("news");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("news.providers.mode", () -> "fake");
    registry.add("news.publication.policy", () -> "HUMAN_REVIEW_ALWAYS");
    registry.add(
        "news.newsletter.token-secret",
        () -> "workflow-test-secret-that-is-at-least-32-characters");
    registry.add("news.public-base-url", () -> "https://news.example.test");
  }

  @Autowired ApplicationContext context;
  @Autowired SourceIngestionService ingestion;
  @Autowired ArticleService articles;
  @Autowired MediaService media;
  @Autowired DurableEventPublisher events;
  @Autowired IncomingEventReader reader;
  @Autowired JdbcTemplate jdbc;
  @Autowired RecordingEmailDeliveryProvider email;
  @Autowired InMemoryObjectStorage storage;

  @Test
  void runsDiscoveryThroughDuplicateSafeNewsletterDelivery() {
    UUID accountId =
        ingestion.addAccount("2244994945", "NsangusaNews", "Nsangusa", Set.of("world"), 0);
    Instant publishedAt = Instant.parse("2026-09-03T08:00:00Z");

    assertThatThrownBy(
            () ->
                ingestion.discoverPost(
                    accountId,
                    "1963526500000000001",
                    "https://example.test/NsangusaNews/status/1963526500000000001",
                    "World update #World",
                    publishedAt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Source URL must be the official canonical X post URL");
    assertThat(count("source_posts")).isZero();
    assertThat(count("outbox_events")).isZero();

    UUID sourceId =
        ingestion.discoverPost(
            accountId,
            "1963526500000000001",
            "https://x.com/NsangusaNews/status/1963526500000000001",
            "  World\u0000 update from a monitored account #World  ",
            publishedAt);
    var discovered = envelope("XPostDiscovered", XPostDiscovered.class);
    assertThat(discovered.aggregateId()).isEqualTo(sourceId);
    assertThat(discovered.correlationId()).isEqualTo(sourceId);
    assertThat(discovered.causationId()).isNull();
    assertThat(discovered.idempotencyKey())
        .isEqualTo("x-post-discovered:2244994945:1963526500000000001");

    consume(
        "com.nsangusa.news.sourceingestion.internal.SourceNormalizationConsumer",
        "consume",
        json("XPostDiscovered"));
    var normalizedJson = json("XPostNormalized");
    var normalized = envelope("XPostNormalized", Object.class);
    assertThat(normalized.correlationId()).isEqualTo(sourceId);
    assertThat(normalized.causationId()).isEqualTo(discovered.eventId());
    assertThat(normalizedJson).contains("World update from a monitored account #World");
    assertThat(count("processed_events")).isEqualTo(1);

    consume(
        "com.nsangusa.news.sourceingestion.internal.SourceNormalizationConsumer",
        "consume",
        json("XPostDiscovered"));
    assertThat(countEvents("XPostNormalized")).isEqualTo(1);

    consume(
        "com.nsangusa.news.storyprocessing.internal.StoryCandidateConsumer",
        "consume",
        normalizedJson);
    var analysisRequestedJson = json("StoryAnalysisRequested");
    var analysisRequested = envelope("StoryAnalysisRequested", Object.class);
    assertThat(analysisRequested.causationId()).isEqualTo(normalized.eventId());
    assertThat(count("story_candidates")).isEqualTo(1);

    consume(
        "com.nsangusa.news.aieditorial.internal.EditorialWorkflowConsumer",
        "analyze",
        analysisRequestedJson);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ai_requests where operation = 'analysis' and status = 'completed'",
                Integer.class))
        .isEqualTo(1);
    var draftRequestedJson = json("ArticleDraftRequested");

    consume(
        "com.nsangusa.news.aieditorial.internal.EditorialWorkflowConsumer",
        "draft",
        draftRequestedJson);
    var draftGeneratedJson = json("ArticleDraftGenerated");
    var draftGenerated = envelope("ArticleDraftGenerated", ArticleDraftGenerated.class);
    assertThat(draftGenerated.payload().provider()).isEqualTo("fake");
    assertThat(draftGenerated.payload().model()).isEqualTo("deterministic-editorial-v1");
    assertThat(draftGenerated.payload().humanReviewRequired()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ai_requests where operation = 'draft' and status = 'completed'",
                Integer.class))
        .isEqualTo(1);

    consume(
        "com.nsangusa.news.articles.internal.ArticleEventConsumer", "drafts", draftGeneratedJson);
    UUID articleId = draftGenerated.aggregateId();
    assertThat(articles.get(articleId).state()).isEqualTo(ArticleState.DRAFTING);

    consume(
        "com.nsangusa.news.media.internal.ImageWorkflowConsumer",
        "consume",
        json("ArticleImageRequested"));
    assertThat(storage.keys()).hasSize(3);
    assertThat(media.generations(articleId))
        .singleElement()
        .satisfies(
            generation -> {
              assertThat(generation.provider()).isEqualTo("fake");
              assertThat(generation.safetyStatus()).isEqualTo("review_required");
            });

    consume(
        "com.nsangusa.news.articles.internal.ArticleEventConsumer",
        "images",
        json("ArticleImageGenerated"));
    assertThat(articles.get(articleId).state()).isEqualTo(ArticleState.AWAITING_REVIEW);
    assertThat(countEvents("ArticleReadyForReview")).isEqualTo(1);

    UUID editorId = UUID.fromString("07d50f10-f9d9-44de-86dd-b4037658d1e8");
    media.approve(media.generations(articleId).getFirst().id(), editorId);
    assertThatThrownBy(() -> articles.publish(articleId, editorId, articleId, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be approved or scheduled");
    assertThat(articles.get(articleId).state()).isEqualTo(ArticleState.AWAITING_REVIEW);
    assertThat(countEvents("ArticlePublished")).isZero();

    articles.approve(articleId, editorId);
    assertThat(articles.get(articleId).state()).isEqualTo(ArticleState.APPROVED);
    consume(
        "com.nsangusa.news.publication.internal.PublicationConsumer",
        "consume",
        json("ArticleApproved"));
    assertThat(articles.get(articleId).state()).isEqualTo(ArticleState.APPROVED);

    articles.publish(articleId, editorId, articleId, null);
    assertThat(articles.get(articleId).state()).isEqualTo(ArticleState.PUBLISHED);
    var published = envelope("ArticlePublished", ArticlePublished.class);
    assertThat(published.payload().newsletterEligible()).isTrue();
    assertThat(isUnpublishedOutboxEvent(published.eventId())).isTrue();

    UUID subscriptionId = UUID.fromString("aac42202-abbd-4455-b9f4-f65665a4499f");
    jdbc.update(
        """
        insert into newsletter_subscriptions
          (id, email, status, frequency, consent_source, consent_at,
           verification_token_hash, unsubscribe_token_hash, version)
        values (?, ?, 'confirmed', 'immediate', 'workflow-test', now(), ?, ?, 0)
        """,
        subscriptionId,
        "reader@example.test",
        "verification-hash",
        "unsubscribe-hash");

    consume(
        "com.nsangusa.news.newsletter.internal.NewsletterWorkflowConsumer",
        "publication",
        json("ArticlePublished"));
    var dispatch = envelope("NewsletterDispatchRequested", NewsletterDispatchRequested.class);
    assertThat(dispatch.causationId()).isEqualTo(published.eventId());
    assertThat(dispatch.payload().campaignKey()).isEqualTo("article:" + articleId + ":initial");

    String dispatchJson = json("NewsletterDispatchRequested");
    consume(
        "com.nsangusa.news.newsletter.internal.NewsletterWorkflowConsumer",
        "dispatch",
        dispatchJson);
    consume(
        "com.nsangusa.news.newsletter.internal.NewsletterWorkflowConsumer",
        "dispatch",
        dispatchJson);

    assertThat(email.recipients()).containsExactly("reader@example.test");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from newsletter_deliveries
                 where subscription_id = ? and campaign_key = ?
                """,
                Integer.class,
                subscriptionId,
                dispatch.payload().campaignKey()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select status from newsletter_deliveries where subscription_id = ?",
                String.class,
                subscriptionId))
        .isEqualTo("delivered");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from processed_events
                 where event_id = ? and consumer_name = 'newsletter-delivery-v1'
                """,
                Integer.class,
                dispatch.eventId()))
        .isEqualTo(1);

    String responseLossCampaign = "article:" + articleId + ":response-loss";
    UUID responseLossEventId =
        events.enqueue(
            "NewsletterDispatchRequested",
            articleId,
            articleId,
            published.eventId(),
            "newsletter-dispatch:" + responseLossCampaign,
            new NewsletterDispatchRequested(
                articleId,
                responseLossCampaign,
                published.payload().slug(),
                published.payload().headline()));
    String responseLossJson = json("NewsletterDispatchRequested");
    email.failNextAfterAcceptance();

    assertThatThrownBy(
            () ->
                consume(
                    "com.nsangusa.news.newsletter.internal.NewsletterWorkflowConsumer",
                    "dispatch",
                    responseLossJson))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("response lost after provider acceptance");
    assertThat(
            jdbc.queryForObject(
                "select status from newsletter_deliveries where campaign_key = ?",
                String.class,
                responseLossCampaign))
        .isEqualTo("failed");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from processed_events where event_id = ?",
                Integer.class,
                responseLossEventId))
        .isZero();

    consume(
        "com.nsangusa.news.newsletter.internal.NewsletterWorkflowConsumer",
        "dispatch",
        responseLossJson);
    assertThat(email.recipients()).containsExactly("reader@example.test", "reader@example.test");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from newsletter_deliveries where campaign_key = ?",
                Integer.class,
                responseLossCampaign))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from processed_events where event_id = ?",
                Integer.class,
                responseLossEventId))
        .isEqualTo(1);
  }

  private void consume(String className, String method, String json) {
    try {
      Object consumer = context.getBean(Class.forName(className));
      ReflectionTestUtils.invokeMethod(consumer, method, json);
    } catch (ClassNotFoundException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String json(String eventType) {
    return jdbc.queryForObject(
        "select envelope_json from outbox_events where event_type = ? order by created_at desc limit 1",
        String.class,
        eventType);
  }

  private <T> EventEnvelope<T> envelope(String eventType, Class<T> payloadType) {
    return reader.read(json(eventType), payloadType);
  }

  private int count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private int countEvents(String eventType) {
    return jdbc.queryForObject(
        "select count(*) from outbox_events where event_type = ?", Integer.class, eventType);
  }

  private boolean isUnpublishedOutboxEvent(UUID eventId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select published_at is null from outbox_events where id = ?", Boolean.class, eventId));
  }

  @TestConfiguration(proxyBeanMethods = false)
  @ComponentScan(
      basePackages = "com.nsangusa.news",
      useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = COMPONENT_PATTERN))
  static class WorkflowConfiguration {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    Validator validator() {
      return Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Bean
    ConcurrentMapCacheManager cacheManager() {
      return new ConcurrentMapCacheManager();
    }

    @Bean
    ReplaySafetyRegistry replaySafetyRegistry() {
      return new ReplaySafetyRegistry() {
        @Override
        public void suppressAggregate(UUID aggregateId, String reason, UUID actorId) {}

        @Override
        public boolean isSuppressed(UUID aggregateId) {
          return false;
        }
      };
    }

    @Bean
    @Primary
    InMemoryObjectStorage objectStorage() {
      return new InMemoryObjectStorage();
    }

    @Bean
    RecordingEmailDeliveryProvider emailDeliveryProvider() {
      return new RecordingEmailDeliveryProvider();
    }
  }

  static final class InMemoryObjectStorage implements ObjectStorage {
    private final ConcurrentHashMap<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
      objects.put(objectKey, bytes.clone());
    }

    @Override
    public void delete(String objectKey) {
      objects.remove(objectKey);
    }

    Set<String> keys() {
      return Set.copyOf(objects.keySet());
    }
  }

  static final class RecordingEmailDeliveryProvider implements EmailDeliveryProvider {
    private final List<String> recipients = new ArrayList<>();
    private boolean failNextAfterAcceptance;

    @Override
    public String send(String recipient, String subject, String text, String html) {
      recipients.add(recipient);
      if (failNextAfterAcceptance) {
        failNextAfterAcceptance = false;
        throw new IllegalStateException("response lost after provider acceptance");
      }
      return "message-" + recipients.size();
    }

    void failNextAfterAcceptance() {
      failNextAfterAcceptance = true;
    }

    List<String> recipients() {
      return List.copyOf(recipients);
    }
  }
}
