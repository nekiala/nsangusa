package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class OperationalMetricsTests {
  @Test
  void unavailableInventoryIsNotReportedAsHealthyZeroAndRetainsItsLastTimestamp() {
    var registry = new SimpleMeterRegistry();
    var failed = new AtomicBoolean();
    var metrics =
        new OperationalMetrics(
            () -> {
              if (failed.get()) throw new IllegalStateException("database unavailable");
              return Map.of("outbox.pending", 305d);
            },
            registry,
            Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
    assertThat(registry.get("news.operations.outbox.pending").gauge().value()).isNaN();
    assertThat(registry.get("news.operations.collection.success").gauge().value()).isZero();
    metrics.refresh();
    assertThat(registry.get("news.operations.outbox.pending").gauge().value()).isEqualTo(305);
    assertThat(registry.get("news.operations.snapshot.timestamp").gauge().value()).isEqualTo(1000);
    failed.set(true);
    metrics.refresh();
    assertThat(registry.get("news.operations.outbox.pending").gauge().value()).isEqualTo(305);
    assertThat(registry.get("news.operations.collection.success").gauge().value()).isZero();
    assertThat(registry.get("news.operations.snapshot.timestamp").gauge().value()).isEqualTo(1000);
    assertThat(registry.get("news.operations.collection.failures").counter().count()).isEqualTo(1);
  }

  @Test
  void snapshotsReplaceRatherThanIncrementDatabaseWideCounts() {
    var registry = new SimpleMeterRegistry();
    var metrics =
        new OperationalMetrics(() -> Map.of("outbox.pending", 3d), registry, Clock.systemUTC());
    metrics.refresh();
    metrics.refresh();
    assertThat(registry.get("news.operations.outbox.pending").gauge().value()).isEqualTo(3);
    assertThat(registry.getMeters())
        .allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
  }
}
