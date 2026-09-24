package com.nsangusa.news.media.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@Component
class ImageProviderCallExecutor {
  private final MeterRegistry metrics;
  private final Semaphore bulkhead;
  private final int maxAttempts;
  private final Duration initialDelay;
  private final Duration maxDelay;
  private final int circuitFailureThreshold;
  private final Duration circuitOpenDuration;
  private final Sleeper sleeper;
  private final AtomicInteger failures = new AtomicInteger();
  private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.EPOCH);

  @Autowired
  ImageProviderCallExecutor(
      MeterRegistry metrics,
      @Value("${news.providers.image.max-concurrent-requests:2}") int maxConcurrentRequests,
      @Value("${news.providers.image.max-attempts:2}") int maxAttempts,
      @Value("${news.providers.image.retry-initial-delay:250ms}") Duration initialDelay,
      @Value("${news.providers.image.retry-max-delay:2s}") Duration maxDelay,
      @Value("${news.providers.image.circuit-failure-threshold:5}") int circuitFailureThreshold,
      @Value("${news.providers.image.circuit-open-duration:30s}") Duration circuitOpenDuration) {
    this(
        metrics,
        maxConcurrentRequests,
        maxAttempts,
        initialDelay,
        maxDelay,
        circuitFailureThreshold,
        circuitOpenDuration,
        duration -> Thread.sleep(duration));
  }

  ImageProviderCallExecutor(
      MeterRegistry metrics,
      int maxConcurrentRequests,
      int maxAttempts,
      Duration initialDelay,
      Duration maxDelay,
      int circuitFailureThreshold,
      Duration circuitOpenDuration,
      Sleeper sleeper) {
    if (maxConcurrentRequests < 1
        || maxConcurrentRequests > 20
        || maxAttempts < 1
        || maxAttempts > 5
        || circuitFailureThreshold < 1
        || initialDelay.isNegative()
        || maxDelay.isNegative()
        || maxDelay.compareTo(Duration.ofSeconds(30)) > 0
        || initialDelay.compareTo(maxDelay) > 0
        || circuitOpenDuration.isNegative()) {
      throw new IllegalArgumentException("Invalid image provider resilience configuration");
    }
    this.metrics = metrics;
    this.bulkhead = new Semaphore(maxConcurrentRequests);
    this.maxAttempts = maxAttempts;
    this.initialDelay = initialDelay;
    this.maxDelay = maxDelay;
    this.circuitFailureThreshold = circuitFailureThreshold;
    this.circuitOpenDuration = circuitOpenDuration;
    this.sleeper = sleeper;
  }

  <T> T execute(Supplier<T> action) {
    if (Instant.now().isBefore(openUntil.get())) {
      metrics.counter("news.image.provider.calls", "outcome", "circuit_open").increment();
      throw new IllegalStateException("Image provider circuit is open");
    }
    if (!bulkhead.tryAcquire()) {
      metrics.counter("news.image.provider.calls", "outcome", "bulkhead_full").increment();
      throw new IllegalStateException("Image provider bulkhead is full");
    }
    long started = System.nanoTime();
    String outcome = "failure";
    try {
      for (int attempt = 1; ; attempt++) {
        try {
          T result = action.get();
          failures.set(0);
          openUntil.set(Instant.EPOCH);
          outcome = "success";
          return result;
        } catch (RuntimeException exception) {
          if (!retryable(exception) || attempt == maxAttempts) {
            if (retryable(exception) && failures.incrementAndGet() >= circuitFailureThreshold) {
              openUntil.set(Instant.now().plus(circuitOpenDuration));
            }
            throw exception;
          }
          metrics.counter("news.image.provider.retries").increment();
          pause(attempt);
        }
      }
    } finally {
      bulkhead.release();
      metrics.counter("news.image.provider.calls", "outcome", outcome).increment();
      metrics
          .timer("news.image.provider.duration", "outcome", outcome)
          .record(Duration.ofNanos(System.nanoTime() - started));
    }
  }

  private void pause(int attempt) {
    long base = Math.min(maxDelay.toMillis(), initialDelay.toMillis() * (1L << (attempt - 1)));
    long jitter = base == 0 ? 0 : ThreadLocalRandom.current().nextLong(Math.max(1, base / 2));
    try {
      sleeper.sleep(Duration.ofMillis(Math.min(maxDelay.toMillis(), base + jitter)));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Image provider call interrupted", exception);
    }
  }

  private static boolean retryable(RuntimeException exception) {
    return exception instanceof ResourceAccessException
        || exception instanceof HttpServerErrorException
        || exception instanceof HttpClientErrorException.TooManyRequests;
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }
}
