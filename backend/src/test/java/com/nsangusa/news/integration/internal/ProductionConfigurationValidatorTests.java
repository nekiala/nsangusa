package com.nsangusa.news.integration.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationValidatorTests {
  @Test
  void rejectsAutomaticPublicationInProduction() {
    var environment =
        new MockEnvironment().withProperty("PUBLICATION_POLICY", "AUTOMATIC_ABOVE_THRESHOLD");

    assertThatThrownBy(() -> new ProductionConfigurationValidator(environment).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PUBLICATION_POLICY must be HUMAN_REVIEW_ALWAYS in production");
  }

  @Test
  void acceptsCompleteHardenedProductionConfiguration() {
    assertThatCode(() -> new ProductionConfigurationValidator(secureEnvironment()).run(null))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingRequiredCredentials() {
    var environment = secureEnvironment().withProperty("AI_API_KEY", " ");

    assertThatThrownBy(() -> new ProductionConfigurationValidator(environment).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AI_API_KEY");
  }

  @Test
  void rejectsInsecurePublicTransportAndWeakNewsletterSecrets() {
    var insecureUrl =
        secureEnvironment().withProperty("PUBLIC_BASE_URL", "http://news.example.test");
    assertThatThrownBy(() -> new ProductionConfigurationValidator(insecureUrl).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PUBLIC_BASE_URL must use HTTPS in production");

    var weakSecret = secureEnvironment().withProperty("NEWSLETTER_TOKEN_SECRET", "too-short");
    assertThatThrownBy(() -> new ProductionConfigurationValidator(weakSecret).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("NEWSLETTER_TOKEN_SECRET must contain at least 32 characters");
  }

  @Test
  void rejectsProductionConnectionsWithoutVerifiedTls() {
    var database =
        secureEnvironment()
            .withProperty("DATABASE_URL", "jdbc:postgresql://db.example.test/news?sslmode=require");
    assertThatThrownBy(() -> new ProductionConfigurationValidator(database).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DATABASE_URL must require verified PostgreSQL TLS in production");

    var kafka = secureEnvironment().withProperty("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT");
    assertThatThrownBy(() -> new ProductionConfigurationValidator(kafka).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("KAFKA_SECURITY_PROTOCOL must be SASL_SSL in production");

    var redis = secureEnvironment().withProperty("REDIS_SSL_ENABLED", "false");
    assertThatThrownBy(() -> new ProductionConfigurationValidator(redis).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REDIS_SSL_ENABLED must be true in production");
  }

  private static MockEnvironment secureEnvironment() {
    var environment = new MockEnvironment();
    environment.setProperty("PUBLICATION_POLICY", "HUMAN_REVIEW_ALWAYS");
    environment.setProperty(
        "DATABASE_URL", "jdbc:postgresql://db.example.test/news?sslmode=verify-full");
    environment.setProperty("DATABASE_USERNAME", "news");
    environment.setProperty("DATABASE_PASSWORD", "database-secret");
    environment.setProperty("KAFKA_BOOTSTRAP_SERVERS", "kafka.example.test:9093");
    environment.setProperty("KAFKA_SASL_JAAS_CONFIG", "configured");
    environment.setProperty("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
    environment.setProperty("REDIS_HOST", "redis.example.test");
    environment.setProperty("REDIS_PASSWORD", "redis-secret");
    environment.setProperty("REDIS_SSL_ENABLED", "true");
    environment.setProperty("MAIL_HOST", "mail.example.test");
    environment.setProperty("MAIL_USERNAME", "mailer");
    environment.setProperty("MAIL_PASSWORD", "mail-secret");
    environment.setProperty("AI_API_KEY", "ai-secret");
    environment.setProperty("AI_BASE_URL", "https://ai.example.test");
    environment.setProperty("IMAGE_API_KEY", "image-secret");
    environment.setProperty("IMAGE_BASE_URL", "https://images.example.test");
    environment.setProperty("S3_ENDPOINT", "https://s3.example.test");
    environment.setProperty("S3_REGION", "eu-west-1");
    environment.setProperty("S3_BUCKET", "news");
    environment.setProperty("S3_ACCESS_KEY", "access");
    environment.setProperty("S3_SECRET_KEY", "storage-secret");
    environment.setProperty("X_BEARER_TOKEN", "x-secret");
    environment.setProperty("X_API_BASE_URL", "https://api.x.com");
    environment.setProperty("NEWSLETTER_TOKEN_SECRET", "newsletter-token-secret-at-least-32-chars");
    environment.setProperty("NEWSLETTER_WEBHOOK_SECRET", "newsletter-webhook-secret-at-least-32");
    environment.setProperty("NEWSLETTER_FROM_ADDRESS", "news@example.test");
    environment.setProperty("OIDC_ISSUER_URI", "https://identity.example.test");
    environment.setProperty("OIDC_CLIENT_ID", "client");
    environment.setProperty("OIDC_CLIENT_SECRET", "oidc-secret");
    environment.setProperty("PUBLIC_BASE_URL", "https://news.example.test");
    return environment;
  }
}
