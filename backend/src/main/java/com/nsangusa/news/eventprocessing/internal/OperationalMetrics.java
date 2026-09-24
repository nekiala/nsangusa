package com.nsangusa.news.eventprocessing.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Database-wide inventory gauges: use max, not sum, when scraping multiple replicas. */
@Component
class OperationalMetrics {
  static final String SQL =
      """
      select name, value::double precision from (values
        ('outbox.pending', (select count(*) from outbox_events where published_at is null)),
        ('outbox.oldest.age', (select coalesce(extract(epoch from now() - min(created_at)), 0)
          from outbox_events where published_at is null)),
        ('failures.pending', (select count(*) from failed_events
          where status not in ('suppressed', 'replayed'))),
        ('replays.pending', (select count(*) from event_replay_requests
          where not dry_run and status in ('pending', 'processing'))),
        ('publication.overdue', (select count(*) from scheduled_publications
          where status = 'scheduled' and scheduled_for < now())),
        ('publication.oldest.age', (select coalesce(extract(epoch from now() - min(scheduled_for)), 0)
          from scheduled_publications where status = 'scheduled' and scheduled_for < now())),
        ('publication.failed', (select count(*) from scheduled_publications where status = 'failed')),
        ('ingestion.failed.accounts', (select count(*) from monitored_x_accounts
          where monitoring_enabled and removed_at is null and consecutive_errors > 0)),
        ('ingestion.rate.limited.accounts', (select count(*) from monitored_x_accounts
          where monitoring_enabled and removed_at is null and rate_limit_reset_at > now())),
        ('ingestion.never.synced.accounts', (select count(*) from monitored_x_accounts
          where monitoring_enabled and removed_at is null and last_successful_sync_at is null)),
        ('ingestion.oldest.age', (select coalesce(extract(epoch from now() - min(last_successful_sync_at)), 0)
          from monitored_x_accounts where monitoring_enabled and removed_at is null)),
        ('ai.pending', (select count(*) from ai_requests where completed_at is null
          and status not in ('failed', 'redacted', 'blocked'))),
        ('ai.oldest.age', (select coalesce(extract(epoch from now() - min(created_at)), 0)
          from ai_requests where completed_at is null
          and status not in ('failed', 'redacted', 'blocked'))),
        ('newsletter.reconciliation', (select count(*) from newsletter_deliveries
          where status = 'reconciliation_required')),
        ('newsletter.pending', (select count(*) from newsletter_deliveries
          where status in ('pending', 'sending', 'failed'))),
        ('newsletter.delivered', (select count(*) from newsletter_deliveries where status = 'delivered')),
        ('moderation.pending', (select count(*) from comments where state in ('pending', 'spam'))),
        ('retention.active.holds', (select count(*) from operational_legal_holds where released_at is null))
      ) as inventory(name, value)
      """;

  private static final String[] INVENTORIES = {
    "outbox.pending",
    "outbox.oldest.age",
    "failures.pending",
    "replays.pending",
    "publication.overdue",
    "publication.oldest.age",
    "publication.failed",
    "ingestion.failed.accounts",
    "ingestion.rate.limited.accounts",
    "ingestion.never.synced.accounts",
    "ingestion.oldest.age",
    "ai.pending",
    "ai.oldest.age",
    "newsletter.reconciliation",
    "newsletter.pending",
    "newsletter.delivered",
    "moderation.pending",
    "retention.active.holds"
  };

  private final Supplier<Map<String, Double>> query;
  private final MeterRegistry registry;
  private final Clock clock;
  private volatile Map<String, Double> snapshot = Map.of();
  private volatile double lastSuccess;
  private volatile double collectionSuccess;

  @Autowired
  OperationalMetrics(DataSource dataSource, MeterRegistry registry) {
    this(
        () -> {
          var jdbc = new JdbcTemplate(dataSource);
          jdbc.setQueryTimeout(5);
          var result = new LinkedHashMap<String, Double>();
          jdbc.query(SQL, (rs, row) -> Map.entry(rs.getString("name"), rs.getDouble("value")))
              .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
          return result;
        },
        registry,
        Clock.systemUTC());
  }

  OperationalMetrics(Supplier<Map<String, Double>> query, MeterRegistry registry, Clock clock) {
    this.query = query;
    this.registry = registry;
    this.clock = clock;
    for (String name : INVENTORIES) {
      var builder =
          Gauge.builder("news.operations." + name, this, metrics -> metrics.value(name))
              .description("Database-wide inventory; aggregate replicas with max, never sum");
      if (name.endsWith(".age")) builder.baseUnit("seconds");
      builder.register(registry);
    }
    Gauge.builder("news.operations.collection.success", this, metrics -> metrics.collectionSuccess)
        .register(registry);
    Gauge.builder("news.operations.snapshot.timestamp", this, metrics -> metrics.lastSuccess)
        .baseUnit("seconds")
        .register(registry);
  }

  @Scheduled(fixedDelayString = "${news.operations.metrics-interval:30000}")
  void refresh() {
    try {
      snapshot = Map.copyOf(query.get());
      lastSuccess = clock.instant().getEpochSecond();
      collectionSuccess = 1;
    } catch (RuntimeException unavailable) {
      collectionSuccess = 0;
      registry.counter("news.operations.collection.failures").increment();
    }
  }

  private double value(String name) {
    return snapshot.getOrDefault(name, Double.NaN);
  }
}
