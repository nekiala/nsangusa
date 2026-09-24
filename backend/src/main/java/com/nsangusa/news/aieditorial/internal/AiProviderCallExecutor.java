package com.nsangusa.news.aieditorial.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@Component
class AiProviderCallExecutor {
  private final MeterRegistry metrics;
  private final Semaphore bulkhead;
  private final int maxAttempts;
  private final Duration initialDelay;
  private final Duration maxDelay;
  private final int circuitFailureThreshold;
  private final Duration circuitOpenDuration;
  private final Sleeper sleeper;
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicReference<Instant> circuitOpenUntil = new AtomicReference<>(Instant.EPOCH);
  private int maxRequestsPerMinute = 60;
  private Instant rateWindow = Instant.EPOCH;
  private int windowRequests;

  @Value("${news.providers.ai.max-requests-per-minute:60}")
  void configureRateLimit(int maxRequestsPerMinute) {
    if (maxRequestsPerMinute < 1 || maxRequestsPerMinute > 10_000) {
      throw new IllegalArgumentException("Invalid AI provider rate limit");
    }
    this.maxRequestsPerMinute = maxRequestsPerMinute;
  }

  @Autowired
  AiProviderCallExecutor(
      MeterRegistry metrics,
      @Value("${news.providers.ai.max-concurrent-requests:8}") int maxConcurrentRequests,
      @Value("${news.providers.ai.max-attempts:3}") int maxAttempts,
      @Value("${news.providers.ai.retry-initial-delay:250ms}") Duration initialDelay,
      @Value("${news.providers.ai.retry-max-delay:2s}") Duration maxDelay,
      @Value("${news.providers.ai.circuit-failure-threshold:5}") int circuitFailureThreshold,
      @Value("${news.providers.ai.circuit-open-duration:30s}") Duration circuitOpenDuration) {
    this(
        metrics,
        maxConcurrentRequests,
        maxAttempts,
        initialDelay,
        maxDelay,
        circuitFailureThreshold,
        circuitOpenDuration,
        duration -> Thread.sleep(duration.toMillis()));
  }

  AiProviderCallExecutor(
      MeterRegistry metrics,
      int maxConcurrentRequests,
      int maxAttempts,
      Duration initialDelay,
      Duration maxDelay,
      int circuitFailureThreshold,
      Duration circuitOpenDuration,
      Sleeper sleeper) {
    if (maxConcurrentRequests < 1
        || maxConcurrentRequests > 64
        || maxAttempts < 1
        || maxAttempts > 5
        || circuitFailureThreshold < 1
        || circuitFailureThreshold > 100
        || initialDelay.isNegative()
        || maxDelay.isNegative()
        || initialDelay.compareTo(maxDelay) > 0
        || maxDelay.compareTo(Duration.ofSeconds(10)) > 0
        || circuitOpenDuration.isNegative()
        || circuitOpenDuration.compareTo(Duration.ofMinutes(10)) > 0) {
      throw new IllegalArgumentException("Invalid AI provider resilience configuration");
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

  <T> T execute(String operation, Callable<T> action) {
    if (Instant.now().isBefore(circuitOpenUntil.get())) {
      metrics
          .counter("news.ai.provider.calls", "operation", operation, "outcome", "circuit_open")
          .increment();
      throw new IllegalStateException("AI provider circuit is open");
    }
    if (!bulkhead.tryAcquire()) {
      metrics
          .counter("news.ai.provider.calls", "operation", operation, "outcome", "bulkhead_full")
          .increment();
      throw new IllegalStateException("AI provider bulkhead is full");
    }
    long started = System.nanoTime();
    String outcome = "failure";
    try {
      for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
          reserveRateSlot();
          T result = action.call();
          consecutiveFailures.set(0);
          circuitOpenUntil.set(Instant.EPOCH);
          outcome = "success";
          metrics
              .counter("news.ai.provider.attempts", "operation", operation, "outcome", "success")
              .increment();
          return result;
        } catch (RuntimeException exception) {
          String failureType = failureType(exception);
          metrics
              .counter("news.ai.provider.attempts", "operation", operation, "outcome", failureType)
              .increment();
          if (!isRetryable(exception) || attempt == maxAttempts) {
            if (isRetryable(exception)
                && consecutiveFailures.incrementAndGet() >= circuitFailureThreshold) {
              circuitOpenUntil.set(Instant.now().plus(circuitOpenDuration));
            }

            outcome = failureType;
            throw exception;
          }
          metrics.counter("news.ai.provider.retries", "operation", operation).increment();
          sleep(backoff(attempt));
        } catch (Exception exception) {
          outcome = "unexpected";
          throw new IllegalStateException("AI provider call failed", exception);
        }
      }
      throw new IllegalStateException("AI provider retry loop exhausted unexpectedly");
    } finally {
      bulkhead.release();
      metrics
          .counter("news.ai.provider.calls", "operation", operation, "outcome", outcome)
          .increment();
      metrics
          .timer("news.ai.provider.duration", "operation", operation, "outcome", outcome)
          .record(Duration.ofNanos(System.nanoTime() - started));
    }
  }

  private synchronized void reserveRateSlot() {
    Instant now = Instant.now();
    if (!now.isBefore(rateWindow.plusSeconds(60))) {
      rateWindow = now;
      windowRequests = 0;
    }
    if (windowRequests >= maxRequestsPerMinute) {
      throw new AiProviderException("local_rate_limited");
    }
    windowRequests++;
  }

  private Duration backoff(int attempt) {
    long multiplier = 1L << Math.min(attempt - 1, 20);
    long baseMillis = Math.min(maxDelay.toMillis(), initialDelay.toMillis() * multiplier);
    if (baseMillis == 0) {
      return Duration.ZERO;
    }
    long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, baseMillis / 2));
    return Duration.ofMillis(Math.min(maxDelay.toMillis(), baseMillis + jitter));
  }

  private void sleep(Duration delay) {
    try {
      sleeper.sleep(delay);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AI provider call interrupted", exception);
    }
  }

  private static boolean isRetryable(RuntimeException exception) {
    return exception instanceof ResourceAccessException
        || exception instanceof HttpServerErrorException
        || exception
            instanceof org.springframework.web.client.HttpClientErrorException.TooManyRequests;
  }

  private static String failureType(RuntimeException exception) {
    if (exception instanceof ResourceAccessException) {
      return "network";
    }
    if (exception instanceof HttpServerErrorException) {
      return "server";
    }
    if (exception
        instanceof org.springframework.web.client.HttpClientErrorException.TooManyRequests) {
      return "rate_limited";
    }
    if (exception instanceof org.springframework.web.client.HttpClientErrorException) {
      return "client";
    }
    return "unexpected";
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }
}
