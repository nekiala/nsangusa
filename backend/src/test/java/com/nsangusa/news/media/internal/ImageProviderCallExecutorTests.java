package com.nsangusa.news.media.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class ImageProviderCallExecutorTests {
  @Test
  void transientFailuresAreRetriedWithinTheConfiguredLimit() {
    var attempts = new AtomicInteger();
    var executor = executor(3, 5);

    assertThat(
            executor.execute(
                () -> {
                  if (attempts.incrementAndGet() < 3) {
                    throw new ResourceAccessException("unavailable");
                  }
                  return "image";
                }))
        .isEqualTo("image");
    assertThat(attempts).hasValue(3);
  }

  @Test
  void repeatedAvailabilityFailuresOpenTheCircuit() {
    var attempts = new AtomicInteger();
    var executor = executor(1, 2);
    for (int failure = 0; failure < 2; failure++) {
      assertThatThrownBy(
              () ->
                  executor.execute(
                      () -> {
                        attempts.incrementAndGet();
                        throw new ResourceAccessException("unavailable");
                      }))
          .isInstanceOf(ResourceAccessException.class);
    }

    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> {
                      attempts.incrementAndGet();
                      return "not contacted";
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("circuit is open");
    assertThat(attempts).hasValue(2);
  }

  @Test
  void malformedImageResultsAreNotRetriedOrReplaced() {
    var attempts = new AtomicInteger();
    assertThatThrownBy(
            () ->
                executor(3, 5)
                    .execute(
                        () -> {
                          attempts.incrementAndGet();
                          throw new IllegalArgumentException("invalid image");
                        }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(attempts).hasValue(1);
  }

  static ImageProviderCallExecutor executor(int attempts, int failures) {
    return new ImageProviderCallExecutor(
        new SimpleMeterRegistry(),
        1,
        attempts,
        Duration.ZERO,
        Duration.ZERO,
        failures,
        Duration.ofMinutes(1),
        ignored -> {});
  }
}
