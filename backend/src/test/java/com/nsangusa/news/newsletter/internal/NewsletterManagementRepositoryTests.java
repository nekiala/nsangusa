package com.nsangusa.news.newsletter.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.newsletter.NewsletterManagementService.Reconciliation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  NewsletterManagementRepository.class,
  NewsletterPreferenceMailQueue.class,
  NewsletterManagementRepositoryTests.Configuration.class
})
class NewsletterManagementRepositoryTests {
  @org.springframework.boot.test.context.TestConfiguration
  static class Configuration {
    @org.springframework.context.annotation.Bean
    org.springframework.cache.CacheManager cacheManager() {
      return new org.springframework.cache.support.NoOpCacheManager();
    }
  }

  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

  @Container
  static final GenericContainer<?> MAILPIT =
      new GenericContainer<>("axllent/mailpit:v1.27.8").withExposedPorts(1025, 8025);

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired NewsletterManagementRepository management;
  @Autowired NewsletterPreferenceMailQueue queue;
  @Autowired NewsletterSubscriptionRepository subscriptions;
  @Autowired NewsletterDeliveryRepository deliveries;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  private final UnsubscribeTokenService tokens =
      new UnsubscribeTokenService("newsletter-test-secret-at-least-32-bytes");

  @Test
  void accountPreferenceChangesIncrementTheSubscriptionVersionAndDeletionRevokesConsent() {
    UUID subscriptionId = subscriber("weekly");
    UUID userId = UUID.randomUUID();
    jdbc.update(
        """
        insert into users(id, email, display_name, password_hash, email_verified, created_at)
        values (?, ?, 'Newsletter account test', 'unused-test-hash', true, now())
        """,
        userId,
        "account-" + userId + "@example.test");
    jdbc.update(
        "update newsletter_subscriptions set user_id = ? where id = ?", userId, subscriptionId);
    var accounts =
        new NewsletterApplicationService(
            subscriptions,
            deliveries,
            tokens,
            mock(EmailDeliveryProvider.class),
            mock(AuditService.class),
            "https://publication.example.test");
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> accounts.updateAccountFrequency(userId, "immediate"));
    assertThat(
            jdbc.queryForObject(
                "select version from newsletter_subscriptions where id = ?",
                Long.class,
                subscriptionId))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "select frequency from newsletter_subscriptions where id = ?",
                String.class,
                subscriptionId))
        .isEqualTo("immediate");
    transaction.executeWithoutResult(status -> accounts.unsubscribeAndUnlinkAccount(userId));
    assertThat(
            jdbc.queryForObject(
                "select version from newsletter_subscriptions where id = ?",
                Long.class,
                subscriptionId))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "select status from newsletter_subscriptions where id = ?",
                String.class,
                subscriptionId))
        .isEqualTo("unsubscribed");
    assertThat(accounts.accountPreference(userId)).isEmpty();
  }

  @Test
  void consentAndQueuesArePageableFilterableAndMinimizePersonalData() {
    UUID id = subscriber("weekly");
    jdbc.update("update newsletter_subscriptions set frequency = 'daily' where id = ?", id);
    var consent = management.consent(id, 0, 1);
    assertThat(consent.total()).isEqualTo(2);
    assertThat(consent.items()).hasSize(1);
    assertThat(consent.items().getFirst().action()).isEqualTo("preferences_changed");
    var selected = management.subscriptions("confirmed", "daily", 0, 20);
    assertThat(selected.items()).extracting(item -> item.id()).contains(id);
    assertThat(selected.toString())
        .doesNotContain("@example.test", "verificationToken", "unsubscribeToken");
    jdbc.update(
        """
        insert into newsletter_suppressions(id, email_hash, reason, created_at)
        select ?, encode(sha256(convert_to(email, 'UTF8')), 'hex'), 'complaint', now()
        from newsletter_subscriptions where id = ?
        """,
        UUID.randomUUID(),
        id);
    var suppression = management.suppressions("complaint", 0, 20);
    assertThat(suppression.items()).extracting(item -> item.subscriptionId()).contains(id);
    assertThat(suppression.toString()).doesNotContain("@example.test", "emailHash");
  }

  @Test
  void actualLocalSmtpPreferenceMailWorksWithoutAnAccountOrMutationOnRead() throws Exception {
    UUID id = subscriber("weekly");
    var service = service();
    String recipient = subscriptions.findById(id).orElseThrow().email;
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          service.requestPreferenceLink(recipient);
          service.requestPreferenceLink(recipient);
        });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from newsletter_preference_links where subscription_id = ?",
                Integer.class,
                id))
        .isEqualTo(1);
    var sender = new JavaMailSenderImpl();
    sender.setHost(MAILPIT.getHost());
    sender.setPort(MAILPIT.getMappedPort(1025));
    sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
    sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");
    new NewsletterPreferenceMailDispatcher(
            queue,
            new JavaMailDeliveryProvider(sender, "editorial@example.test", "local-smtp"),
            tokens,
            "https://publication.example.test")
        .dispatch();

    String apiBase = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    var mapper = JsonMapper.builder().build();
    var messages = mapper.readTree(get(client, apiBase + "/api/v1/messages")).path("messages");
    String messageId = messages.get(0).path("ID").asString();
    var message = mapper.readTree(get(client, apiBase + "/api/v1/message/" + messageId));
    String text = message.path("Text").asString();
    String html = message.path("HTML").asString();
    var link =
        java.util.regex.Pattern.compile(
                "/newsletter/preferences\\?id=" + id + "&token=([A-Za-z0-9_.-]+)")
            .matcher(text);
    assertThat(link.find()).isTrue();
    assertThat(html).contains("Manage newsletter preferences", "&amp;token=");
    assertThat(text)
        .contains("30 minutes after the request", "Opening it makes no changes")
        .doesNotContain(recipient);
    assertThat(
            jdbc.queryForObject(
                "select status from newsletter_preference_links where subscription_id = ?",
                String.class,
                id))
        .isEqualTo("accepted");
    var preference = service.preferences(id, link.group(1));
    assertThat(preference.frequency()).isEqualTo("weekly");
    assertThat(preference.toString()).doesNotContain(recipient);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from consent_records where subscription_id = ?",
                Integer.class,
                id))
        .isEqualTo(1);
    jdbc.update("update newsletter_subscriptions set status = 'unsubscribed' where id = ?", id);
    assertThatThrownBy(() -> service.preferences(id, link.group(1)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void smtpFailureIsDurablyUncertainAndIsNotAutomaticallyRetried() {
    UUID id = subscriber("weekly");
    var subscription = subscriptions.findById(id).orElseThrow();
    management.enqueueLink(subscription, java.time.Instant.now());
    var email = mock(EmailDeliveryProvider.class);
    when(email.send(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("response lost"));
    var dispatcher =
        new NewsletterPreferenceMailDispatcher(
            queue, email, tokens, "https://publication.example.test");
    dispatcher.dispatch();
    dispatcher.dispatch();
    verify(email, times(1)).send(any(), any(), any(), any(), any());
    assertThat(
            jdbc.queryForObject(
                "select status from newsletter_preference_links where subscription_id = ?",
                String.class,
                id))
        .isEqualTo("reconciliation_required");
  }

  @Test
  void persistedAttemptHistoryAndReconciliationNeverClaimInboxDeliveryOrPermitResend() {
    UUID subscriptionId = subscriber("weekly");
    UUID articleId = UUID.randomUUID();
    jdbc.update(
        """
        insert into articles(id, slug, headline, summary, body, seo_title, seo_description, topic, tags,
          state, confidence, warnings, created_at, updated_at)
        values (?, ?, 'Headline', 'Summary', 'Body', 'Headline', 'Summary', 'News', '', 'DRAFTING', 0, '', now(), now())
        """,
        articleId,
        "newsletter-management-" + articleId);
    UUID deliveryId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    String campaign = "test:" + deliveryId;
    jdbc.update(
        """
        insert into newsletter_campaigns(id, campaign_key, article_id, campaign_type, created_at, status)
        values (?, ?, ?, 'weekly', now(), 'completed')
        """,
        UUID.randomUUID(),
        campaign,
        articleId);
    jdbc.update(
        """
        insert into newsletter_deliveries(id, subscription_id, article_id, campaign_key, status, attempt_count,
          attempt_token, attempt_started_at, provider_idempotency_applied, created_at)
        values (?, ?, ?, ?, 'pending', 1, ?, now(), false, now())
        """,
        deliveryId,
        subscriptionId,
        articleId,
        campaign,
        attemptId);
    jdbc.update(
        "update newsletter_deliveries set status = 'delivered', delivered_at = now(), provider_message_id = 'smtp-message-1' where id = ?",
        deliveryId);
    var accepted = management.deliveries("provider_accepted", subscriptionId, campaign, 0, 20);
    assertThat(accepted.total()).isEqualTo(1);
    assertThat(accepted.items().getFirst().deliveryConfirmedAt()).isNull();
    assertThat(management.attempts(deliveryId, 0, 20).items().getFirst().status())
        .isEqualTo("provider_accepted");
    assertThat(management.campaigns("weekly", "completed", 0, 100).items())
        .anySatisfy(
            item -> {
              assertThat(item.campaignKey()).isEqualTo(campaign);
              assertThat(item.acceptedCount()).isEqualTo(1);
            });
    jdbc.update(
        "update newsletter_deliveries set delivery_confirmed_at = now() where id = ?", deliveryId);
    assertThat(management.deliveries("delivery_confirmed", subscriptionId, null, 0, 20).total())
        .isEqualTo(1);
    jdbc.update("update newsletter_deliveries set status = 'bounced' where id = ?", deliveryId);
    assertThat(management.deliveries("delivery_confirmed", subscriptionId, null, 0, 20).total())
        .isZero();
    jdbc.update(
        "update newsletter_deliveries set status = 'reconciliation_required' where id = ?",
        deliveryId);
    management.recordReconciliation(
        deliveryId, UUID.randomUUID(), new Reconciliation("not_sent", "ticket:local-123", null));
    assertThat(management.deliveries("reconciled", subscriptionId, null, 0, 20).total())
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update newsletter_deliveries set attempt_token = ?, attempt_count = 2 where id = ?",
                    UUID.randomUUID(),
                    deliveryId))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  private NewsletterManagementApplicationService service() {
    return new NewsletterManagementApplicationService(
        management,
        subscriptions,
        deliveries,
        tokens,
        mock(DurableCommandExecutor.class),
        mock(AuditService.class));
  }

  private UUID subscriber(String frequency) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into newsletter_subscriptions(id, email, status, frequency, consent_source, consent_at,
          verification_token_hash, unsubscribe_token_hash, verified_at)
        values (?, ?, 'confirmed', ?, 'newsletter-test', now(), 'hash', 'hash', now())
        """,
        id,
        "newsletter-" + id + "@example.test",
        frequency);
    return id;
  }

  private static String get(HttpClient client, String url) throws Exception {
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(200);
    return result.body();
  }
}
