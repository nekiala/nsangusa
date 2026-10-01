package com.nsangusa.news;

import static com.nsangusa.news.eventprocessing.internal.ReliabilityBrokerProbeConfiguration.GROUP;
import static com.nsangusa.news.eventprocessing.internal.ReliabilityBrokerProbeConfiguration.OBSERVER_GROUP;
import static com.nsangusa.news.eventprocessing.internal.ReliabilityBrokerProbeConfiguration.RECOVERY_GROUP;
import static com.nsangusa.news.eventprocessing.internal.ReliabilityBrokerProbeConfiguration.RECOVERY_TOPIC;
import static com.nsangusa.news.eventprocessing.internal.ReliabilityBrokerProbeConfiguration.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.EventOperations;
import com.nsangusa.news.eventprocessing.EventOperations.FailedEventView;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import com.nsangusa.news.eventprocessing.internal.ReliabilityBrokerProbeConfiguration;
import com.nsangusa.news.eventprocessing.internal.ReliabilityBrokerProbeConfiguration.Failure;
import com.nsangusa.news.eventprocessing.internal.ReliabilityBrokerProbeConfiguration.Plan;
import com.nsangusa.news.eventprocessing.internal.ReliabilityBrokerProbeConfiguration.Probe;
import com.nsangusa.news.eventprocessing.internal.ReliabilityBrokerProbeConfiguration.RelayDriver;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticlePublished;
import com.nsangusa.news.integration.NewsEvents.ArticleReadyForReview;
import com.nsangusa.news.integration.NewsEvents.ArticleUnpublished;
import com.nsangusa.news.integration.NewsEvents.CommentSubmitted;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "news.local.seed=false",
      "spring.docker.compose.enabled=false",
      "management.tracing.enabled=false",
      "spring.cache.type=none",
      "news.outbox.poll-interval=3600000",
      "news.events.retry.delays=1s,2s",
      "spring.kafka.consumer.properties.metadata.max.age.ms=500",
      "spring.kafka.consumer.properties.allow.auto.create.topics=false",
      "spring.kafka.producer.properties.max.block.ms=3000",
      "spring.kafka.producer.properties.request.timeout.ms=1000",
      "spring.kafka.producer.properties.delivery.timeout.ms=4000"
    })
@ActiveProfiles("local")
@Import(ReliabilityBrokerProbeConfiguration.class)
@Testcontainers
class EventReliabilityBrokerIntegrationTests {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.3.1").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.10.0").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<Object, Object> kafka;
  @Autowired Probe probe;
  @Autowired RelayDriver relay;
  @Autowired EventOperations operations;
  @Autowired ReplaySafetyRegistry safety;
  @Autowired DurableEventPublisher events;

  @BeforeEach
  void createProbeTable() {
    jdbc.execute("create table if not exists reliability_probe_effects(event_id uuid primary key)");
  }

  @Test
  void transientFailuresRetryTheAssignedRecordAndRollbackInboxEffectsAndOutboxTogether()
      throws Exception {
    var message = message();
    var plan = probe.plan(message.key(), 2, Failure.TRANSIENT);
    var sent = send(TOPIC, message);
    awaitCommitted(GROUP, sent);
    awaitCommitted(OBSERVER_GROUP, sent);

    assertThat(plan.calls.get()).isEqualTo(3);
    assertBackoff(plan, 1_000, 2_000);
    assertThat(plan.cleanTransactions).containsExactly(true, true, true);
    assertThat(count("reliability_probe_effects", "event_id", message.eventId())).isEqualTo(1);
    assertThat(inbox(message.eventId(), GROUP)).isEqualTo(1);
    assertThat(count("outbox_events", "causation_id", message.eventId())).isEqualTo(1);
    assertThat(probe.observed(message.key())).isEqualTo(1);
    assertThat(failures(sent, GROUP)).isEmpty();
  }

  @Test
  void exhaustedFailuresHaveFourBlockingAndTwoDelayedAttemptsAndPreserveMetadata()
      throws Exception {
    var message = message();
    var plan = probe.plan(message.key(), 1000, Failure.TRANSIENT);
    var sent = send(TOPIC, message);
    var failure = awaitFailure(sent, GROUP);
    awaitCommitted(GROUP, sent);
    awaitCommitted(OBSERVER_GROUP, sent);

    assertThat(plan.calls.get()).isEqualTo(6);
    assertBackoff(plan, 1_000, 2_000, 4_000, 1_000, 2_000);
    assertThat(plan.cleanTransactions).containsExactly(true, true, true, true, true, true);
    assertThat(failure.deliveryAttempt()).isEqualTo(6);
    assertThat(failure.originalTopic()).isEqualTo(TOPIC);
    assertThat(failure.poisonMessage()).isFalse();
    assertThat(failure.status()).isEqualTo("eligible");
    assertThat(failure.originalPartition()).isEqualTo(1);
    assertThat(failure.originalOffset()).isEqualTo(sent.offset());
    assertThat(failure.consumerGroup()).isEqualTo(GROUP);
    assertThat(failure.exceptionMessage()).doesNotContain("DO_NOT_DISCLOSE", "password");
    assertThat(inbox(message.eventId(), GROUP)).isZero();
    assertThat(count("reliability_probe_effects", "event_id", message.eventId())).isZero();
    assertThat(count("outbox_events", "causation_id", message.eventId())).isZero();
    assertThat(probe.observed(message.key())).isEqualTo(1);

    var dlt = dltRecord(failure.id());
    assertThat(dlt.value()).isEqualTo(message.json());
    assertThat(dlt.headers().lastHeader("kafka_dlt-exception-stacktrace")).isNull();
    for (var header : dlt.headers()) {
      assertThat(new String(header.value(), StandardCharsets.UTF_8))
          .doesNotContain("DO_NOT_DISCLOSE");
    }
  }

  @Test
  void invalidSchemaPayloadAuthorizationAndInvariantPoisonBypassRetries() throws Exception {
    for (String invalid :
        List.of("not-json", "{\"eventType\":\"ArticleReadyForReview\",\"schemaVersion\":99}")) {
      var message = message();
      var plan = probe.plan(message.key(), 0, Failure.TRANSIENT);
      var sent = send(TOPIC, new Message(message.eventId(), message.aggregateId(), invalid));
      var failure = awaitFailure(sent, GROUP);
      awaitCommitted(GROUP, sent);
      assertThat(plan.calls.get()).isEqualTo(1);
      assertThat(failure.poisonMessage()).isTrue();
      assertThat(failure.deliveryAttempt()).isEqualTo(1);
    }
    var invalidPayload = message();
    var invalidPlan = probe.plan(invalidPayload.key(), 0, Failure.TRANSIENT);
    var root =
        (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(invalidPayload.json());
    root.set("payload", mapper.createObjectNode());
    var invalidSent =
        send(
            TOPIC,
            new Message(
                invalidPayload.eventId(),
                invalidPayload.aggregateId(),
                mapper.writeValueAsString(root)));
    assertThat(awaitFailure(invalidSent, GROUP).poisonMessage()).isTrue();
    awaitCommitted(GROUP, invalidSent);
    assertThat(invalidPlan.calls.get()).isEqualTo(1);

    for (var kind : List.of(Failure.AUTHORIZATION, Failure.INVARIANT)) {
      var message = message();
      var plan = probe.plan(message.key(), 1000, kind);
      var sent = send(TOPIC, message);
      var failure = awaitFailure(sent, GROUP);
      awaitCommitted(GROUP, sent);
      assertThat(plan.calls.get()).isEqualTo(1);
      assertThat(failure.poisonMessage()).isTrue();
      assertThat(failure.deliveryAttempt()).isEqualTo(1);
      assertThat(failure.exceptionMessage()).doesNotContain("DO_NOT_DISCLOSE");
      assertThat(inbox(message.eventId(), GROUP)).isZero();
    }
  }

  @Test
  void recoveryPublishFailureDoesNotCommitTheSourceOffset() throws Exception {
    var message = message();
    var plan = probe.plan(message.key(), 1000, Failure.INVARIANT);
    var sent = send(RECOVERY_TOPIC, message);
    // A second listener invocation proves the first real broker recovery send failed.
    await(() -> plan.calls.get(), calls -> calls >= 2);
    assertThat(committed(RECOVERY_GROUP, sent)).isLessThanOrEqualTo(sent.offset());
    assertThat(failures(sent, RECOVERY_GROUP)).isEmpty();
    createTopic(RECOVERY_TOPIC + ".dlt");
    var failure = awaitFailure(sent, RECOVERY_GROUP);
    awaitCommitted(RECOVERY_GROUP, sent);
    assertThat(failure.originalOffset()).isEqualTo(sent.offset());
    assertThat(failure.consumerGroup()).isEqualTo(RECOVERY_GROUP);
    assertThat(inbox(message.eventId(), RECOVERY_GROUP)).isZero();
  }

  @Test
  void delayedRetryTopicRecoversOnlyTheFailedGroupWithoutBlockingLaterRecords() throws Exception {
    var message = message();
    var plan = probe.plan(message.key(), 4, Failure.TRANSIENT);
    var sent = send(TOPIC, message);
    awaitCommitted(GROUP, sent);
    // The source partition advances once the record is on the retry topic, before recovery.
    assertThat(inbox(message.eventId(), GROUP)).isZero();
    var later = message();
    probe.plan(later.key(), 0, Failure.TRANSIENT);
    var laterSent = send(TOPIC, later);
    awaitCommitted(GROUP, laterSent);
    assertThat(inbox(later.eventId(), GROUP)).isEqualTo(1);

    await(() -> inbox(message.eventId(), GROUP), count -> count == 1);
    assertThat(plan.calls.get()).isEqualTo(5);
    assertBackoff(plan, 1_000, 2_000, 4_000, 1_000);
    assertThat(count("reliability_probe_effects", "event_id", message.eventId())).isEqualTo(1);
    assertThat(count("outbox_events", "causation_id", message.eventId())).isEqualTo(1);
    assertThat(failures(sent, GROUP)).isEmpty();

    var retry =
        brokerRecord(
            TOPIC + EventTopics.RETRY_SUFFIX,
            value -> value.contains(message.eventId().toString()));
    assertThat(retry.partition()).isEqualTo(sent.partition());
    assertThat(retry.key()).isEqualTo(message.key());
    assertThat(text(retry, "x-retry-consumer-group")).isEqualTo(GROUP);
    assertThat(text(retry, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
    var retryMetadata =
        new RecordMetadata(
            new TopicPartition(retry.topic(), retry.partition()), retry.offset(), 0, 0, 0, 0);
    awaitCommitted(OBSERVER_GROUP + EventTopics.RETRY_GROUP_SUFFIX, retryMetadata);
    // The observer group processed the original once and skipped the other group's retry.
    assertThat(probe.observed(message.key())).isEqualTo(1);
  }

  @Test
  void retryRecordsAddressedToAnotherGroupAreSkippedByEveryRetryListener() throws Exception {
    var message = message();
    var plan = probe.plan(message.key(), 0, Failure.TRANSIENT);
    var record =
        new ProducerRecord<Object, Object>(
            TOPIC + EventTopics.RETRY_SUFFIX, 1, message.key(), message.json());
    record.headers().add("x-retry-consumer-group", "unrelated-v1".getBytes(StandardCharsets.UTF_8));
    var sent = kafka.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
    awaitCommitted(GROUP + EventTopics.RETRY_GROUP_SUFFIX, sent);
    awaitCommitted(OBSERVER_GROUP + EventTopics.RETRY_GROUP_SUFFIX, sent);
    assertThat(plan.calls.get()).isZero();
    assertThat(probe.observed(message.key())).isZero();
    assertThat(inbox(message.eventId(), GROUP)).isZero();
  }

  @Test
  void outboxRemainsUnpublishedWhenTheRealBrokerCannotAcknowledgeAndRecoversAfterProvisioning()
      throws Exception {
    UUID aggregate = UUID.randomUUID();
    UUID eventId =
        events.enqueue(
            "CommentSubmitted",
            aggregate,
            UUID.randomUUID(),
            null,
            "reliability-outbox:" + aggregate,
            new CommentSubmitted(aggregate, aggregate, aggregate));

    assertThatThrownBy(relay::relay).isInstanceOf(Exception.class);
    assertThat(
            jdbc.queryForObject(
                "select published_at is null from outbox_events where id = ?",
                Boolean.class,
                eventId))
        .isTrue();

    createTopic(EventTopics.NOTIFICATIONS);
    relay.relay();
    assertThat(
            jdbc.queryForObject(
                "select published_at is not null from outbox_events where id = ?",
                Boolean.class,
                eventId))
        .isTrue();
    var record =
        brokerRecord(EventTopics.NOTIFICATIONS, value -> value.contains(eventId.toString()));
    assertThat(record.key()).isEqualTo(aggregate.toString());
  }

  @Test
  void actualReplayTargetsOnlyTheFailedGroupAndDuplicateReplayCannotRepeatEffects()
      throws Exception {
    var message = message();
    var plan = probe.plan(message.key(), 1000, Failure.TRANSIENT);
    var sent = send(TOPIC, message);
    var failure = awaitFailure(sent, GROUP);
    awaitCommitted(GROUP, sent);
    awaitCommitted(OBSERVER_GROUP, sent);
    plan.failures = 0;
    UUID actor = UUID.randomUUID();
    var preview = preview(failure, actor);
    assertThat(plan.calls.get()).isEqualTo(6);
    var request = operations.confirmReplay(preview.id(), actor);
    await(() -> operations.getReplay(request.id()), result -> "completed".equals(result.status()));
    await(() -> inbox(message.eventId(), GROUP), count -> count == 1);

    var replay =
        brokerRecord(
            TOPIC, value -> value.contains(message.eventId().toString()), "x-replay-request-id");
    var replayMetadata =
        new RecordMetadata(
            new TopicPartition(replay.topic(), replay.partition()), replay.offset(), 0, 0, 0, 0);
    awaitCommitted(OBSERVER_GROUP, replayMetadata);
    assertThat(plan.calls.get()).isEqualTo(7);
    assertThat(probe.observed(message.key())).isEqualTo(1);
    assertThat(replay.partition()).isEqualTo(sent.partition());
    assertThat(
            new String(
                replay.headers().lastHeader("x-replay-consumer-group").value(),
                StandardCharsets.UTF_8))
        .isEqualTo(GROUP);

    var duplicate =
        new ProducerRecord<Object, Object>(
            TOPIC, replay.partition(), message.key(), message.json());
    replay.headers().forEach(header -> duplicate.headers().add(header));
    var duplicateMetadata = kafka.send(duplicate).get(10, TimeUnit.SECONDS).getRecordMetadata();
    awaitCommitted(GROUP, duplicateMetadata);
    awaitCommitted(OBSERVER_GROUP, duplicateMetadata);
    assertThat(plan.calls.get()).isEqualTo(8);
    assertThat(probe.observed(message.key())).isEqualTo(1);
    assertThat(count("reliability_probe_effects", "event_id", message.eventId())).isEqualTo(1);
    assertThat(count("outbox_events", "causation_id", message.eventId())).isEqualTo(1);
    assertThat(operations.confirmReplay(preview.id(), actor).id()).isEqualTo(request.id());
  }

  @Test
  void suppressionAddedAfterPreviewBlocksActualReplayWithoutPublishing() throws Exception {
    var message = message();
    var plan = probe.plan(message.key(), 1000, Failure.TRANSIENT);
    var sent = send(TOPIC, message);
    var failure = awaitFailure(sent, GROUP);
    awaitCommitted(GROUP, sent);
    UUID actor = UUID.randomUUID();
    var preview = preview(failure, actor);
    safety.suppressAggregate(message.aggregateId(), "source withdrawn", actor);
    plan.failures = 0;
    var request = operations.confirmReplay(preview.id(), actor);
    var completed =
        await(
            () -> operations.getReplay(request.id()),
            result -> "completed_with_blocks".equals(result.status()));
    assertThat(completed.blockedCount()).isEqualTo(1);
    assertThat(completed.replayedCount()).isZero();
    assertThat(operations.listReplayRecords(request.id()))
        .singleElement()
        .satisfies(record -> assertThat(record.outcome()).isEqualTo("blocked"));
    assertThat(plan.calls.get()).isEqualTo(6);
    assertThat(inbox(message.eventId(), GROUP)).isZero();
  }

  @Test
  void realSearchConsumerDeduplicatesAndCannotResurrectUnpublishedArticlesFromReorderedEvents()
      throws Exception {
    UUID article = UUID.randomUUID();
    String slug = "reliability-" + article;
    jdbc.update(
        """
        insert into articles(id, slug, headline, summary, body, seo_title, seo_description,
          topic, tags, state, confidence, warnings, created_at, updated_at, published_at)
        values (?, ?, 'Synthetic headline', 'Synthetic summary', 'Synthetic body',
          'Synthetic title', 'Synthetic description', 'culture', 'testing', 'PUBLISHED', 1, '',
          now(), now(), now())
        """,
        article,
        slug);
    var published =
        envelope(
            UUID.randomUUID(),
            article,
            "ArticlePublished",
            new ArticlePublished(article, slug, "Synthetic headline", Instant.now(), false));
    var first =
        kafka
            .send(EventTopics.PUBLICATION, article.toString(), published)
            .get(10, TimeUnit.SECONDS)
            .getRecordMetadata();
    awaitCommitted("search-index-v1", first);
    assertThat(count("article_search_documents", "article_id", article)).isEqualTo(1);
    var duplicate =
        kafka
            .send(EventTopics.PUBLICATION, article.toString(), published)
            .get(10, TimeUnit.SECONDS)
            .getRecordMetadata();
    awaitCommitted("search-index-v1", duplicate);
    assertThat(count("article_search_documents", "article_id", article)).isEqualTo(1);

    jdbc.update(
        "update articles set state = 'UNPUBLISHED', unpublished_at = now() where id = ?", article);
    var unpublished =
        envelope(UUID.randomUUID(), article, "ArticleUnpublished", new ArticleUnpublished(article));
    var removed =
        kafka
            .send(EventTopics.PUBLICATION, article.toString(), unpublished)
            .get(10, TimeUnit.SECONDS)
            .getRecordMetadata();
    awaitCommitted("search-index-v1", removed);
    var latePublished =
        envelope(
            UUID.randomUUID(),
            article,
            "ArticlePublished",
            new ArticlePublished(
                article, slug, "Old synthetic headline", Instant.now().minusSeconds(60), false));
    var late =
        kafka
            .send(EventTopics.PUBLICATION, article.toString(), latePublished)
            .get(10, TimeUnit.SECONDS)
            .getRecordMetadata();
    awaitCommitted("search-index-v1", late);
    assertThat(count("article_search_documents", "article_id", article)).isZero();
    assertThat(
            jdbc.queryForObject("select state from articles where id = ?", String.class, article))
        .isEqualTo("UNPUBLISHED");
  }

  private EventOperations.ReplayRequestView preview(FailedEventView failure, UUID actor) {
    return operations.requestReplay(
        new EventOperations.ReplayCommand(
            Set.of(failure.id()),
            null,
            null,
            1,
            1,
            true,
            false,
            actor,
            "Synthetic failure repaired",
            "broker-integration"));
  }

  private Message message() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    return new Message(
        eventId,
        aggregateId,
        envelope(
            eventId, aggregateId, "ArticleReadyForReview", new ArticleReadyForReview(aggregateId)));
  }

  private String envelope(UUID eventId, UUID aggregateId, String type, Object payload)
      throws Exception {
    return mapper.writeValueAsString(
        new EventEnvelope<>(
            eventId,
            type,
            1,
            aggregateId,
            UUID.randomUUID(),
            null,
            Instant.now(),
            "reliability-integration",
            Map.of(),
            "reliability:" + eventId,
            payload));
  }

  private RecordMetadata send(String topic, Message message) throws Exception {
    return kafka
        .send(new ProducerRecord<Object, Object>(topic, 1, message.key(), message.json()))
        .get(10, TimeUnit.SECONDS)
        .getRecordMetadata();
  }

  private List<FailedEventView> failures(RecordMetadata sent, String group) {
    return operations.listFailed(null, 200).stream()
        .filter(
            failure ->
                sent.topic().equals(failure.originalTopic())
                    && sent.partition() == failure.originalPartition()
                    && sent.offset() == failure.originalOffset()
                    && group.equals(failure.consumerGroup()))
        .toList();
  }

  private FailedEventView awaitFailure(RecordMetadata sent, String group) throws Exception {
    return await(() -> failures(sent, group), failures -> !failures.isEmpty()).getFirst();
  }

  private int inbox(UUID eventId, String group) {
    return jdbc.queryForObject(
        "select count(*) from processed_events where event_id = ? and consumer_name = ?",
        Integer.class,
        eventId,
        group);
  }

  private int count(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, id);
  }

  private void assertBackoff(Plan plan, long... milliseconds) {
    for (int index = 0; index < milliseconds.length; index++) {
      assertThat(
              Duration.ofNanos(plan.attemptNanos.get(index + 1) - plan.attemptNanos.get(index))
                  .toMillis())
          .isGreaterThanOrEqualTo(milliseconds[index] - 100);
    }
  }

  private void createTopic(String topic) throws Exception {
    try (var admin = admin()) {
      admin
          .createTopics(List.of(new NewTopic(topic, 2, (short) 1)))
          .all()
          .get(15, TimeUnit.SECONDS);
    }
  }

  private Admin admin() {
    return Admin.create(
        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
  }

  private long committed(String group, RecordMetadata record) {
    try (var admin = admin()) {
      var offset =
          admin
              .listConsumerGroupOffsets(group)
              .partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS)
              .get(new TopicPartition(record.topic(), record.partition()));
      return offset == null ? -1 : offset.offset();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private void awaitCommitted(String group, RecordMetadata record) throws Exception {
    await(() -> committed(group, record), offset -> offset > record.offset());
  }

  private ConsumerRecord<String, String> dltRecord(UUID failureId) throws Exception {
    var metadata =
        jdbc.queryForMap(
            "select dlt_topic, dlt_partition, dlt_offset from failed_events where id = ?",
            failureId);
    return brokerRecord(
        metadata.get("dlt_topic").toString(),
        value -> true,
        null,
        ((Number) metadata.get("dlt_partition")).intValue(),
        ((Number) metadata.get("dlt_offset")).longValue());
  }

  private ConsumerRecord<String, String> brokerRecord(String topic, Predicate<String> predicate)
      throws Exception {
    return brokerRecord(topic, predicate, null);
  }

  private ConsumerRecord<String, String> brokerRecord(
      String topic, Predicate<String> predicate, String requiredHeader) throws Exception {
    return brokerRecord(topic, predicate, requiredHeader, null, 0);
  }

  private ConsumerRecord<String, String> brokerRecord(
      String topic,
      Predicate<String> predicate,
      String requiredHeader,
      Integer partition,
      long offset)
      throws Exception {
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
      var partitions =
          partition == null
              ? List.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1))
              : List.of(new TopicPartition(topic, partition));
      consumer.assign(partitions);
      partitions.forEach(value -> consumer.seek(value, offset));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline) {
        for (var record : consumer.poll(Duration.ofMillis(200))) {
          if (predicate.test(record.value())
              && (requiredHeader == null || record.headers().lastHeader(requiredHeader) != null))
            return record;
        }
      }
      throw new AssertionError("Expected broker record not found in " + topic);
    }
  }

  private static String text(ConsumerRecord<String, String> record, String header) {
    return new String(record.headers().lastHeader(header).value(), StandardCharsets.UTF_8);
  }

  private static <T> T await(Supplier<T> supplier, Predicate<T> predicate) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    T result;
    do {
      result = supplier.get();
      if (predicate.test(result)) return result;
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Condition not reached: " + result);
  }

  private record Message(UUID eventId, UUID aggregateId, String json) {
    String key() {
      return aggregateId.toString();
    }
  }
}
