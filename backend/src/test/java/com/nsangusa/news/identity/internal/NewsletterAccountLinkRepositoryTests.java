package com.nsangusa.news.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({
  NewsletterAccountLinkRepository.class,
  NewsletterAccountLinkRepositoryTests.TestBeans.class,
  NewsletterAccountLinkRepositoryTests.NewsletterPublicApi.class
})
class NewsletterAccountLinkRepositoryTests {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4")
          .withDatabaseName("news")
          .withUsername("news")
          .withPassword("news");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired NewsletterAccountLinkRepository newsletter;
  @Autowired JdbcTemplate jdbc;
  @Autowired jakarta.persistence.EntityManager entities;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  org.springframework.mail.javamail.JavaMailSender sender;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.nsangusa.news.audit.AuditService audit;

  @Test
  void accountDeletionUnsubscribesAndUnlinksNewsletter() {
    UUID userId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        """
        insert into users (id, email, display_name, password_hash, email_verified, enabled, created_at)
        values (?, ?, ?, ?, true, true, ?)
        """,
        userId,
        "person@example.test",
        "Person",
        "hash",
        now);
    jdbc.update(
        """
        insert into newsletter_subscriptions (
          id, email, status, frequency, consent_source, consent_at,
          verification_token_hash, unsubscribe_token_hash, verified_at, user_id
        ) values (?, ?, 'confirmed', 'immediate', 'profile', ?, ?, ?, ?, ?)
        """,
        subscriptionId,
        "person@example.test",
        now,
        "verification-hash",
        "unsubscribe-hash",
        now,
        userId);

    newsletter.unsubscribeAndUnlink(userId);
    entities.flush();

    var subscription =
        jdbc.queryForMap(
            """
            select status, user_id, unsubscribed_at, verification_token_hash, version
              from newsletter_subscriptions
             where id = ?
            """,
            subscriptionId);
    assertThat(subscription.get("status")).isEqualTo("unsubscribed");
    assertThat(subscription.get("user_id")).isNull();
    assertThat(subscription.get("unsubscribed_at")).isNotNull();
    assertThat(subscription.get("verification_token_hash").toString())
        .startsWith("invalidated:" + subscriptionId);
    assertThat(((Number) subscription.get("version")).longValue()).isEqualTo(1);
  }

  static class NewsletterPublicApi
      implements org.springframework.context.annotation.ImportSelector {
    @Override
    public String[] selectImports(org.springframework.core.type.AnnotationMetadata metadata) {
      return new String[] {
        "com.nsangusa.news.newsletter.internal.NewsletterApplicationService",
        "com.nsangusa.news.newsletter.internal.JavaMailDeliveryProvider",
        "com.nsangusa.news.newsletter.internal.UnsubscribeTokenService"
      };
    }
  }

  @TestConfiguration
  static class TestBeans {
    @Bean
    CacheManager cacheManager() {
      return new NoOpCacheManager();
    }
  }
}
