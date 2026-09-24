package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class AiProviderCallExecutorTests {
  @Test
  void boundsLocalRateAndNeverRetriesRejectedModelOutput() {
    var executor =
        new AiProviderCallExecutor(
            new SimpleMeterRegistry(),
            1,
            3,
            Duration.ZERO,
            Duration.ZERO,
            5,
            Duration.ofMinutes(1),
            ignored -> {});
    executor.configureRateLimit(1);
    var attempts = new AtomicInteger();
    assertThatThrownBy(
            () ->
                executor.execute(
                    "analysis",
                    () -> {
                      attempts.incrementAndGet();
                      throw new AiProviderException("malformed_output");
                    }))
        .hasMessageContaining("malformed_output");
    assertThatThrownBy(
            () ->
                executor.execute(
                    "analysis",
                    () -> {
                      attempts.incrementAndGet();
                      return "unexpected";
                    }))
        .hasMessageContaining("local_rate_limited");
    assertThat(attempts).hasValue(1);
  }

  @Test
  void retriesTransientFailuresWithinTheConfiguredBound() {
    var attempts = new AtomicInteger();
    var metrics = new SimpleMeterRegistry();
    var executor =
        new AiProviderCallExecutor(
            metrics, 1, 3, Duration.ZERO, Duration.ZERO, 5, Duration.ofMinutes(1), ignored -> {});

    String result =
        executor.execute(
            "analysis",
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw new ResourceAccessException("temporary");
              }
              return "ok";
            });

    assertThat(result).isEqualTo("ok");
    assertThat(attempts).hasValue(3);
    assertThat(metrics.counter("news.ai.provider.retries", "operation", "analysis").count())
        .isEqualTo(2);
    assertThat(
            metrics
                .counter("news.ai.provider.calls", "operation", "analysis", "outcome", "success")
                .count())
        .isEqualTo(1);
  }

  @Test
  void opensCircuitAfterRepeatedProviderAvailabilityFailures() {
    var attempts = new AtomicInteger();
    var metrics = new SimpleMeterRegistry();
    var executor =
        new AiProviderCallExecutor(
            metrics, 1, 1, Duration.ZERO, Duration.ZERO, 2, Duration.ofMinutes(1), ignored -> {});

    for (int failure = 0; failure < 2; failure++) {
      assertThatThrownBy(
              () ->
                  executor.execute(
                      "draft",
                      () -> {
                        attempts.incrementAndGet();
                        throw new ResourceAccessException("down");
                      }))
          .isInstanceOf(ResourceAccessException.class);
    }

    assertThatThrownBy(
            () ->
                executor.execute(
                    "draft",
                    () -> {
                      attempts.incrementAndGet();
                      return "unexpected";
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("AI provider circuit is open");
    assertThat(attempts).hasValue(2);
    assertThat(
            metrics
                .counter("news.ai.provider.calls", "operation", "draft", "outcome", "network")
                .count())
        .isEqualTo(2);
    assertThat(
            metrics
                .counter("news.ai.provider.calls", "operation", "draft", "outcome", "circuit_open")
                .count())
        .isEqualTo(1);
  }
}
