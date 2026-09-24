package com.nsangusa.news.integration.internal;

import java.net.URI;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
class ProductionConfigurationValidator implements ApplicationRunner {
  private static final List<String> REQUIRED =
      List.of(
          "DATABASE_URL",
          "DATABASE_USERNAME",
          "DATABASE_PASSWORD",
          "KAFKA_BOOTSTRAP_SERVERS",
          "KAFKA_SASL_JAAS_CONFIG",
          "REDIS_HOST",
          "REDIS_PASSWORD",
          "MAIL_HOST",
          "MAIL_USERNAME",
          "MAIL_PASSWORD",
          "AI_CREDENTIAL_MASTER_KEY",
          "AI_BASE_URL",
          "IMAGE_API_KEY",
          "IMAGE_BASE_URL",
          "S3_ENDPOINT",
          "S3_REGION",
          "S3_BUCKET",
          "S3_ACCESS_KEY",
          "S3_SECRET_KEY",
          "X_BEARER_TOKEN",
          "X_API_BASE_URL",
          "PROVIDER_MODE",
          "NEWSLETTER_TOKEN_SECRET",
          "NEWSLETTER_WEBHOOK_SECRET",
          "NEWSLETTER_PROVIDER",
          "NEWSLETTER_FROM_ADDRESS",
          "OIDC_ISSUER_URI",
          "OIDC_CLIENT_ID",
          "OIDC_CLIENT_SECRET",
          "PUBLIC_BASE_URL");

  private final Environment environment;

  ProductionConfigurationValidator(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    String publicationPolicy = environment.getProperty("PUBLICATION_POLICY", "HUMAN_REVIEW_ALWAYS");
    if (!"HUMAN_REVIEW_ALWAYS".equals(publicationPolicy)) {
      throw new IllegalStateException(
          "PUBLICATION_POLICY must be HUMAN_REVIEW_ALWAYS in production");
    }
    var missing =
        REQUIRED.stream()
            .filter(
                key -> {
                  String value = environment.getProperty(key);
                  return value == null
                      || value.isBlank()
                      || value.contains("replace-me")
                      || value.contains("example.invalid");
                })
            .toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Production configuration is missing required secure values: " + missing);
    }
    if (!"production".equals(environment.getRequiredProperty("PROVIDER_MODE"))) {
      throw new IllegalStateException("PROVIDER_MODE must be production in production");
    }
    try {
      if (java.util.Base64.getDecoder()
              .decode(environment.getRequiredProperty("AI_CREDENTIAL_MASTER_KEY"))
              .length
          != 32) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "AI_CREDENTIAL_MASTER_KEY must be base64 encoding of exactly 32 bytes");
    }
    if (!environment.getProperty(
        "news.providers.ai.live-enabled",
        Boolean.class,
        environment.getProperty("AI_LIVE_ENABLED", Boolean.class, false))) {
      throw new IllegalStateException(
          "AI_LIVE_ENABLED must explicitly enable live AI in production; administrator activation is also required");
    }
    URI publicBaseUrl = URI.create(environment.getRequiredProperty("PUBLIC_BASE_URL"));
    if (!"https".equalsIgnoreCase(publicBaseUrl.getScheme())) {
      throw new IllegalStateException("PUBLIC_BASE_URL must use HTTPS in production");
    }
    if (!"SASL_SSL".equals(environment.getRequiredProperty("KAFKA_SECURITY_PROTOCOL"))) {
      throw new IllegalStateException("KAFKA_SECURITY_PROTOCOL must be SASL_SSL in production");
    }
    if (!environment.getProperty("REDIS_SSL_ENABLED", Boolean.class, false)) {
      throw new IllegalStateException("REDIS_SSL_ENABLED must be true in production");
    }
    String databaseUrl = environment.getRequiredProperty("DATABASE_URL");
    if (!databaseUrl.contains("sslmode=verify-full")) {
      throw new IllegalStateException(
          "DATABASE_URL must require verified PostgreSQL TLS in production");
    }
    requireHttps("AI_BASE_URL");
    requireHttps("IMAGE_BASE_URL");
    requireHttps("S3_ENDPOINT");
    if (!"AES256"
        .equals(environment.getProperty("news.storage.server-side-encryption", "AES256"))) {
      throw new IllegalStateException(
          "S3 server-side encryption must remain enabled in production");
    }
    requireHttps("X_API_BASE_URL");
    requireSecretLength("NEWSLETTER_TOKEN_SECRET");
    requireSecretLength("NEWSLETTER_WEBHOOK_SECRET");
    if (!environment.getRequiredProperty("NEWSLETTER_WEBHOOK_SECRET").startsWith("whsec_")) {
      throw new IllegalStateException(
          "NEWSLETTER_WEBHOOK_SECRET must be a Resend whsec_ signing secret in production");
    }
    if (!"resend".equalsIgnoreCase(environment.getRequiredProperty("NEWSLETTER_PROVIDER"))) {
      throw new IllegalStateException(
          "NEWSLETTER_PROVIDER must support provider-side idempotency in production");
    }
    Duration idempotencyWindow;
    try {
      idempotencyWindow =
          Duration.parse(environment.getProperty("NEWSLETTER_IDEMPOTENCY_WINDOW", "PT24H"));
    } catch (DateTimeParseException exception) {
      throw new IllegalStateException(
          "NEWSLETTER_IDEMPOTENCY_WINDOW must be a valid ISO-8601 duration", exception);
    }
    if (idempotencyWindow.isNegative()
        || idempotencyWindow.isZero()
        || idempotencyWindow.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalStateException(
          "NEWSLETTER_IDEMPOTENCY_WINDOW must be positive and no greater than Resend's 24-hour guarantee");
    }
  }

  private void requireSecretLength(String key) {
    if (environment.getRequiredProperty(key).length() < 32) {
      throw new IllegalStateException(key + " must contain at least 32 characters");
    }
  }

  private void requireHttps(String key) {
    if (!"https".equalsIgnoreCase(URI.create(environment.getRequiredProperty(key)).getScheme())) {
      throw new IllegalStateException(key + " must use HTTPS in production");
    }
  }
}
