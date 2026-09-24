package com.nsangusa.news.aieditorial.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class AiUsageMetrics {
  private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

  private final MeterRegistry metrics;
  private final BigDecimal inputCostPerMillion;
  private final BigDecimal outputCostPerMillion;

  AiUsageMetrics(
      MeterRegistry metrics,
      @Value("${news.providers.ai.input-cost-per-million:0}") BigDecimal inputCostPerMillion,
      @Value("${news.providers.ai.output-cost-per-million:0}") BigDecimal outputCostPerMillion) {
    if (inputCostPerMillion.signum() < 0 || outputCostPerMillion.signum() < 0) {
      throw new IllegalArgumentException("AI token costs cannot be negative");
    }
    this.metrics = metrics;
    this.inputCostPerMillion = inputCostPerMillion;
    this.outputCostPerMillion = outputCostPerMillion;
  }

  void recordUsage(
      String operation, String provider, String model, long inputTokens, long outputTokens) {
    String safeProvider = bounded(provider);
    String safeModel = bounded(model);
    metrics
        .counter(
            "news.ai.tokens",
            "operation",
            operation,
            "direction",
            "input",
            "provider",
            safeProvider,
            "model",
            safeModel)
        .increment(inputTokens);
    metrics
        .counter(
            "news.ai.tokens",
            "operation",
            operation,
            "direction",
            "output",
            "provider",
            safeProvider,
            "model",
            safeModel)
        .increment(outputTokens);
    BigDecimal cost =
        inputCostPerMillion
            .multiply(BigDecimal.valueOf(inputTokens))
            .add(outputCostPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
            .divide(ONE_MILLION, 9, RoundingMode.HALF_UP);
    metrics
        .counter(
            "news.ai.estimated.cost.usd",
            "operation",
            operation,
            "provider",
            safeProvider,
            "model",
            safeModel)
        .increment(cost.doubleValue());
  }

  void recordSafetyBlock() {
    metrics.counter("news.ai.safety.blocks").increment();
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value.length() > 80 ? value.substring(0, 80) : value;
  }
}
