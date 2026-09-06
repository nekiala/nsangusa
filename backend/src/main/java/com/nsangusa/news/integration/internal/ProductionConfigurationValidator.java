package com.nsangusa.news.integration.internal;

import java.net.URI;
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
          "AI_API_KEY",
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
          "NEWSLETTER_TOKEN_SECRET",
          "NEWSLETTER_WEBHOOK_SECRET",
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
    requireHttps("X_API_BASE_URL");
    requireSecretLength("NEWSLETTER_TOKEN_SECRET");
    requireSecretLength("NEWSLETTER_WEBHOOK_SECRET");
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
