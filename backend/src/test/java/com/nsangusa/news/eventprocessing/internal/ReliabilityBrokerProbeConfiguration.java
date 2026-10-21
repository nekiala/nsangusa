package com.nsangusa.news.eventprocessing.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.eventprocessing.TerminalEventException;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticleReadyForReview;
import com.nsangusa.news.integration.NewsEvents.CommentSubmitted;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

@TestConfiguration(proxyBeanMethods = false)
public class ReliabilityBrokerProbeConfiguration {
  public static final String TOPIC = "news.reliability.v1";
  public static final String RECOVERY_TOPIC = "news.reliability-recovery.v1";
  public static final String GROUP = "reliability-probe-v1";
  public static final String OBSERVER_GROUP = "reliability-observer-v1";
  public static final String RECOVERY_GROUP = "reliability-recovery-v1";

  @Bean
  KafkaAdmin.NewTopics reliabilityTopics() {
    return new KafkaAdmin.NewTopics(
        TopicBuilder.name(TOPIC).partitions(2).replicas(1).build(),
        TopicBuilder.name(TOPIC + ".dlt").partitions(2).replicas(1).build(),
        TopicBuilder.name(TOPIC + EventTopics.RETRY_SUFFIX).partitions(2).replicas(1).build(),
        TopicBuilder.name(RECOVERY_TOPIC).partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.INGESTION).partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.INGESTION + ".dlt").partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.INGESTION_RETRY).partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.EDITORIAL).partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.EDITORIAL + ".dlt").partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.EDITORIAL_RETRY).partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.PUBLICATION).partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.PUBLICATION + ".dlt").partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.PUBLICATION_RETRY).partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.NOTIFICATIONS + ".dlt").partitions(2).replicas(1).build(),
        TopicBuilder.name(EventTopics.NOTIFICATIONS_RETRY).partitions(2).replicas(1).build());
  }

  @Bean
  Probe reliabilityProbe(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      DurableEventPublisher events,
      JdbcTemplate jdbc,
      EntityManager entityManager) {
    return new Probe(reader, processed, events, jdbc, entityManager);
  }

  @Bean
  RelayDriver reliabilityRelayDriver(OutboxRelay relay) {
    return new RelayDriver(relay);
  }

  public static class RelayDriver {
    private final OutboxRelay relay;

    RelayDriver(OutboxRelay relay) {
      this.relay = relay;
    }

    public void relay() throws Exception {
      relay.relay();
    }
  }

  public enum Failure {
    TRANSIENT,
    AUTHORIZATION,
    INVARIANT
  }

  public static class Plan {
    public volatile int failures;
    public final Failure failure;
    public final AtomicInteger calls = new AtomicInteger();
    public final List<Long> attemptNanos = new CopyOnWriteArrayList<>();
    public final List<Boolean> cleanTransactions = new CopyOnWriteArrayList<>();

    Plan(int failures, Failure failure) {
      this.failures = failures;
      this.failure = failure;
    }

    void fail(int attempt) {
      if (attempt > failures) return;
      switch (failure) {
        case AUTHORIZATION -> throw new AccessDeniedException("credential=DO_NOT_DISCLOSE");
        case INVARIANT -> throw new TerminalEventException("secret=DO_NOT_DISCLOSE");
        case TRANSIENT -> {
          if (attempt % 2 == 0) {
            throw new org.springframework.dao.TransientDataAccessResourceException(
                "database-password=DO_NOT_DISCLOSE");
          }
          throw new IllegalStateException("transient missing predecessor; DO_NOT_DISCLOSE");
        }
      }
    }
  }

  public static class Probe {
    private final IncomingEventReader reader;
    private final ProcessedEventRegistry processed;
    private final DurableEventPublisher events;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> observed = new ConcurrentHashMap<>();

    Probe(
        IncomingEventReader reader,
        ProcessedEventRegistry processed,
        DurableEventPublisher events,
        JdbcTemplate jdbc,
        EntityManager entityManager) {
      this.reader = reader;
      this.processed = processed;
      this.events = events;
      this.jdbc = jdbc;
      this.entityManager = entityManager;
    }

    public Plan plan(String key, int failures, Failure failure) {
      var plan = new Plan(failures, failure);
      plans.put(key, plan);
      return plan;
    }

    public int observed(String key) {
      return observed.getOrDefault(key, new AtomicInteger()).get();
    }

    @KafkaListener(id = GROUP, topics = TOPIC, groupId = GROUP)
    @KafkaListener(
        id = GROUP + EventTopics.RETRY_GROUP_SUFFIX,
        topics = TOPIC + EventTopics.RETRY_SUFFIX,
        groupId = GROUP + EventTopics.RETRY_GROUP_SUFFIX)
    @Transactional
    public void consume(ConsumerRecord<String, String> record) {
      process(record, GROUP);
    }

    @KafkaListener(id = RECOVERY_GROUP, topics = RECOVERY_TOPIC, groupId = RECOVERY_GROUP)
    @Transactional
    public void recovery(ConsumerRecord<String, String> record) {
      process(record, RECOVERY_GROUP);
    }

    private void process(ConsumerRecord<String, String> record, String group) {
      var plan = plans.get(record.key());
      int attempt = plan.calls.incrementAndGet();
      plan.attemptNanos.add(System.nanoTime());
      var event = reader.read(record.value(), ArticleReadyForReview.class);
      if (processed.wasProcessed(event.eventId(), group)) return;
      plan.cleanTransactions.add(
          jdbc.queryForObject(
                      "select count(*) from reliability_probe_effects where event_id = ?",
                      Integer.class,
                      event.eventId())
                  == 0
              && jdbc.queryForObject(
                      "select count(*) from outbox_events where causation_id = ?",
                      Integer.class,
                      event.eventId())
                  == 0);
      processed.markProcessed(event.eventId(), group);
      jdbc.update("insert into reliability_probe_effects(event_id) values (?)", event.eventId());
      events.enqueue(
          "CommentSubmitted",
          event.aggregateId(),
          event.correlationId(),
          event.eventId(),
          "reliability-effect:" + event.eventId(),
          new CommentSubmitted(event.aggregateId(), event.aggregateId(), event.aggregateId()));
      entityManager.flush();
      plan.fail(attempt);
    }

    @KafkaListener(id = OBSERVER_GROUP, topics = TOPIC, groupId = OBSERVER_GROUP)
    @KafkaListener(
        id = OBSERVER_GROUP + EventTopics.RETRY_GROUP_SUFFIX,
        topics = TOPIC + EventTopics.RETRY_SUFFIX,
        groupId = OBSERVER_GROUP + EventTopics.RETRY_GROUP_SUFFIX)
    public void observe(ConsumerRecord<String, String> record) {
      observed.computeIfAbsent(record.key(), ignored -> new AtomicInteger()).incrementAndGet();
    }
  }
}
