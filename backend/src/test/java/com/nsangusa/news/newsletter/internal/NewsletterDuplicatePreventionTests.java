package com.nsangusa.news.newsletter.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.NewsletterDispatchRequested;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.javamail.JavaMailSender;

class NewsletterDuplicatePreventionTests {
  @Test
  void rejectsRetryWindowBeyondProviderGuarantee() {
    assertThatThrownBy(
            () ->
                new NewsletterDeliveryReservationService(
                    mock(NewsletterDeliveryRepository.class), Duration.ofHours(25)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("24-hour guarantee");
  }

  @Test
  void resendReceivesTheProviderIdempotencyHeader() throws Exception {
    var sender = mock(JavaMailSender.class);
    var message = new MimeMessage(Session.getInstance(new Properties()));
    when(sender.createMimeMessage()).thenReturn(message);

    new JavaMailDeliveryProvider(sender, "news@example.test", "resend")
        .send(
            "newsletter-idempotency-key", "reader@example.test", "Subject", "Text", "<p>HTML</p>");

    assertThat(message.getHeader("Resend-Idempotency-Key", null))
        .isEqualTo("newsletter-idempotency-key");
    verify(sender).send(message);
  }

  @Test
  void retryStopsAfterProviderIdempotencyWindowExpires() {
    var deliveries = mock(NewsletterDeliveryRepository.class);
    UUID subscriptionId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    String campaignKey = "article-published:" + articleId;
    var delivery = new NewsletterDelivery(subscriptionId, articleId, campaignKey);
    delivery.status = "failed";
    delivery.providerIdempotencyApplied = true;
    delivery.createdAt = Instant.now().minus(Duration.ofHours(25));
    when(deliveries.findLockedBySubscriptionIdAndCampaignKey(subscriptionId, campaignKey))
        .thenReturn(Optional.of(delivery));

    var reservation =
        new NewsletterDeliveryReservationService(deliveries, Duration.ofHours(24))
            .reserve(subscriptionId, articleId, campaignKey);

    assertThat(reservation).isEmpty();
    assertThat(delivery.status).isEqualTo("reconciliation_required");
    assertThat(delivery.failureCode).isEqualTo("provider_idempotency_window_expired");
  }

  @Test
  void activeAttemptLeaseReusesTheSameProviderCallIdentity() {
    var deliveries = mock(NewsletterDeliveryRepository.class);
    UUID subscriptionId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    String campaignKey = "article-published:" + articleId;
    var delivery = new NewsletterDelivery(subscriptionId, articleId, campaignKey);
    UUID attemptToken = delivery.beginAttempt(Instant.now());
    when(deliveries.findLockedBySubscriptionIdAndCampaignKey(subscriptionId, campaignKey))
        .thenReturn(Optional.of(delivery));

    var reservation =
        new NewsletterDeliveryReservationService(deliveries, Duration.ofHours(24))
            .reserve(subscriptionId, articleId, campaignKey);

    assertThat(reservation).isPresent();
    assertThat(reservation.orElseThrow().attemptToken()).isEqualTo(attemptToken);
    assertThat(reservation.orElseThrow().providerIdempotencyKey())
        .isEqualTo(delivery.providerIdempotencyKey);
    assertThat(delivery.attemptCount).isEqualTo(1);
  }

  @Test
  void staleAttemptCannotOverwriteSuccessfulDelivery() {
    var delivery =
        new NewsletterDelivery(UUID.randomUUID(), UUID.randomUUID(), "article-published:test");
    UUID staleAttempt = delivery.beginAttempt(Instant.now().minus(Duration.ofMinutes(11)));
    UUID currentAttempt = delivery.beginAttempt(Instant.now());

    assertThat(delivery.delivered(currentAttempt, "message-1")).isTrue();
    assertThat(delivery.failed(staleAttempt, "provider_error")).isFalse();
    assertThat(delivery.status).isEqualTo("delivered");
    assertThat(delivery.providerMessageId).isEqualTo("message-1");
  }

  @Test
  void legacyAttemptWithoutProviderIdempotencyRequiresReconciliation() {
    var deliveries = mock(NewsletterDeliveryRepository.class);
    UUID subscriptionId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    String campaignKey = "article-published:" + articleId;
    var delivery = new NewsletterDelivery(subscriptionId, articleId, campaignKey);
    delivery.status = "failed";
    delivery.attemptCount = 1;
    when(deliveries.findLockedBySubscriptionIdAndCampaignKey(subscriptionId, campaignKey))
        .thenReturn(Optional.of(delivery));

    var reservation =
        new NewsletterDeliveryReservationService(deliveries, Duration.ofHours(24))
            .reserve(subscriptionId, articleId, campaignKey);

    assertThat(reservation).isEmpty();
    assertThat(delivery.status).isEqualTo("reconciliation_required");
    assertThat(delivery.failureCode).isEqualTo("provider_idempotency_not_confirmed");
  }

  @Test
  void duplicateSubscriptionIsRejectedAfterEmailNormalization() {
    var subscriptions = mock(NewsletterSubscriptionRepository.class);
    var deliveries = mock(NewsletterDeliveryRepository.class);
    var mail = mock(EmailDeliveryProvider.class);
    when(subscriptions.findByEmail("reader@example.com"))
        .thenReturn(
            Optional.of(
                new NewsletterSubscription(
                    UUID.randomUUID(),
                    "reader@example.com",
                    "footer",
                    "immediate",
                    "verify",
                    "unsubscribe")));
    var service =
        new NewsletterApplicationService(
            subscriptions,
            deliveries,
            new UnsubscribeTokenService("a-secret-that-is-at-least-32-characters"),
            mail,
            mock(com.nsangusa.news.audit.AuditService.class),
            "https://news.example.test");

    assertThatThrownBy(() -> service.subscribe(" Reader@Example.com ", "footer", "immediate"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Subscription already exists");
    verify(subscriptions, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(mail);
  }

  @Test
  void existingCampaignDeliveryIsNeverSentAgain() {
    var fixture = fixture();
    var event = dispatchEvent();
    var subscription =
        new NewsletterSubscription(
            UUID.randomUUID(), "reader@example.com", "footer", "immediate", "verify", "remove");
    subscription.status = "confirmed";
    when(fixture.reader.eventType("event")).thenReturn("NewsletterDispatchRequested");
    when(fixture.reader.read("event", NewsletterDispatchRequested.class)).thenReturn(event);
    when(fixture.campaigns.findByCampaignKey(event.payload().campaignKey()))
        .thenReturn(
            Optional.of(
                new NewsletterCampaign(
                    event.payload().campaignKey(), event.payload().articleId(), "immediate")));
    when(fixture.subscriptions.findByStatusAndFrequencyIn("confirmed", List.of("immediate", "all")))
        .thenReturn(List.of(subscription));
    var existing =
        new NewsletterDelivery(
            subscription.id, event.payload().articleId(), event.payload().campaignKey());
    existing.status = "delivered";
    when(fixture.deliveries.findLockedBySubscriptionIdAndCampaignKey(
            subscription.id, event.payload().campaignKey()))
        .thenReturn(Optional.of(existing));

    fixture.consumer.dispatch("event");

    verify(fixture.deliveries, never()).saveAndFlush(any());
    verify(fixture.mail, never()).send(any(), any(), any(), any(), any());
    verify(fixture.processed).markProcessed(event.eventId(), "newsletter-delivery-v1");
  }

  @Test
  void databaseUniquenessRaceIsTreatedAsAnExistingReservation() {
    var fixture = fixture();
    var event = dispatchEvent();
    var subscription =
        new NewsletterSubscription(
            UUID.randomUUID(), "reader@example.com", "footer", "immediate", "verify", "remove");
    subscription.status = "confirmed";
    when(fixture.reader.eventType("event")).thenReturn("NewsletterDispatchRequested");
    when(fixture.reader.read("event", NewsletterDispatchRequested.class)).thenReturn(event);
    when(fixture.campaigns.findByCampaignKey(event.payload().campaignKey()))
        .thenReturn(
            Optional.of(
                new NewsletterCampaign(
                    event.payload().campaignKey(), event.payload().articleId(), "immediate")));
    when(fixture.subscriptions.findByStatusAndFrequencyIn("confirmed", List.of("immediate", "all")))
        .thenReturn(List.of(subscription));
    when(fixture.deliveries.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate delivery"));

    fixture.consumer.dispatch("event");

    verify(fixture.mail, never()).send(any(), any(), any(), any(), any());
    verify(fixture.processed).markProcessed(event.eventId(), "newsletter-delivery-v1");
  }

  @Test
  void retryAfterProviderAcceptanceDoesNotSendTheSameCampaignTwice() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var events = mock(DurableEventPublisher.class);
    var subscriptions = mock(NewsletterSubscriptionRepository.class);
    var campaigns = mock(NewsletterCampaignRepository.class);
    var suppressions = mock(SuppressionEntryRepository.class);
    var deliveryStore = new HashMap<DeliveryKey, NewsletterDelivery>();
    var deliveries =
        (NewsletterDeliveryRepository)
            Proxy.newProxyInstance(
                NewsletterDeliveryRepository.class.getClassLoader(),
                new Class<?>[] {NewsletterDeliveryRepository.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("findLockedBySubscriptionIdAndCampaignKey")) {
                    return Optional.ofNullable(
                        deliveryStore.get(
                            new DeliveryKey((UUID) arguments[0], (String) arguments[1])));
                  }
                  if (method.getName().equals("saveAndFlush")) {
                    var delivery = (NewsletterDelivery) arguments[0];
                    deliveryStore.put(
                        new DeliveryKey(delivery.subscriptionId, delivery.campaignKey), delivery);
                    return delivery;
                  }
                  if (method.getName().equals("findLockedById")) {
                    return deliveryStore.values().stream()
                        .filter(delivery -> delivery.id.equals(arguments[0]))
                        .findFirst();
                  }
                  if (method.getName().equals("toString")) {
                    return "in-memory deliveries";
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    var mail = new FailsAfterAcceptanceProvider();
    var event = dispatchEvent();
    var subscription =
        new NewsletterSubscription(
            UUID.randomUUID(), "reader@example.com", "footer", "immediate", "verify", "remove");
    subscription.status = "confirmed";
    when(reader.eventType("event")).thenReturn("NewsletterDispatchRequested");
    when(reader.read("event", NewsletterDispatchRequested.class)).thenReturn(event);
    when(campaigns.findByCampaignKey(event.payload().campaignKey()))
        .thenReturn(
            Optional.of(
                new NewsletterCampaign(
                    event.payload().campaignKey(), event.payload().articleId(), "immediate")));
    when(subscriptions.findByStatusAndFrequencyIn("confirmed", List.of("immediate", "all")))
        .thenReturn(List.of(subscription));
    var consumer =
        new NewsletterWorkflowConsumer(
            reader,
            processed,
            events,
            subscriptions,
            new NewsletterDeliveryReservationService(
                deliveries, Duration.ofHours(24), Duration.ofMinutes(10), true, events),
            campaigns,
            suppressions,
            mail,
            new UnsubscribeTokenService("a-secret-that-is-at-least-32-characters"),
            "https://news.example.test");

    assertThatThrownBy(() -> consumer.dispatch("event"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("response lost after provider acceptance");
    consumer.dispatch("event");

    assertThat(mail.acceptedMessages).isEqualTo(1);
    verify(processed).markProcessed(event.eventId(), "newsletter-delivery-v1");
    // The lost response is recorded as a failure, the confirmed resend as a delivery.
    var delivery = deliveryStore.values().iterator().next();
    var failed =
        org.mockito.ArgumentCaptor.forClass(
            com.nsangusa.news.integration.NewsEvents.NewsletterDeliveryFailed.class);
    verify(events)
        .enqueue(
            eq("NewsletterDeliveryFailed"),
            eq(delivery.id),
            eq(event.correlationId()),
            eq(event.eventId()),
            org.mockito.ArgumentMatchers.startsWith("newsletter-delivery-failed:" + delivery.id),
            failed.capture());
    assertThat(failed.getValue().failureCode()).isEqualTo("provider_error");
    assertThat(failed.getValue().reconciliationRequired()).isFalse();
    var delivered =
        org.mockito.ArgumentCaptor.forClass(
            com.nsangusa.news.integration.NewsEvents.NewsletterDelivered.class);
    verify(events)
        .enqueue(
            eq("NewsletterDelivered"),
            eq(delivery.id),
            eq(event.correlationId()),
            eq(event.eventId()),
            eq("newsletter-delivered:" + delivery.id),
            delivered.capture());
    assertThat(delivered.getValue().campaignKey()).isEqualTo(event.payload().campaignKey());
    assertThat(delivered.getValue().toString()).doesNotContain("reader@example.com");
  }

  private static Fixture fixture() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var events = mock(DurableEventPublisher.class);
    var subscriptions = mock(NewsletterSubscriptionRepository.class);
    var deliveries = mock(NewsletterDeliveryRepository.class);
    var campaigns = mock(NewsletterCampaignRepository.class);
    var suppressions = mock(SuppressionEntryRepository.class);
    var mail = mock(EmailDeliveryProvider.class);
    return new Fixture(
        new NewsletterWorkflowConsumer(
            reader,
            processed,
            events,
            subscriptions,
            new NewsletterDeliveryReservationService(
                deliveries, Duration.ofHours(24), Duration.ofMinutes(10), true, events),
            campaigns,
            suppressions,
            mail,
            new UnsubscribeTokenService("a-secret-that-is-at-least-32-characters"),
            "https://news.example.test"),
        reader,
        processed,
        subscriptions,
        deliveries,
        campaigns,
        mail);
  }

  private static EventEnvelope<NewsletterDispatchRequested> dispatchEvent() {
    UUID articleId = UUID.randomUUID();
    String campaignKey = "article:" + articleId + ":initial";
    return new EventEnvelope<>(
        UUID.randomUUID(),
        "NewsletterDispatchRequested",
        1,
        articleId,
        UUID.randomUUID(),
        null,
        Instant.now(),
        "test",
        Map.of(),
        "newsletter-dispatch:" + campaignKey,
        new NewsletterDispatchRequested(articleId, campaignKey, "article", "Headline"));
  }

  private record Fixture(
      NewsletterWorkflowConsumer consumer,
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      NewsletterSubscriptionRepository subscriptions,
      NewsletterDeliveryRepository deliveries,
      NewsletterCampaignRepository campaigns,
      EmailDeliveryProvider mail) {}

  private record DeliveryKey(UUID subscriptionId, String campaignKey) {}

  private static final class FailsAfterAcceptanceProvider implements EmailDeliveryProvider {
    private int acceptedMessages;
    private final Map<String, String> accepted = new HashMap<>();

    @Override
    public String send(
        String idempotencyKey, String recipient, String subject, String text, String html) {
      if (accepted.containsKey(idempotencyKey)) {
        return accepted.get(idempotencyKey);
      }
      acceptedMessages++;
      String messageId = "message-" + acceptedMessages;
      accepted.put(idempotencyKey, messageId);
      if (acceptedMessages == 1) {
        throw new IllegalStateException("response lost after provider acceptance");
      }
      return messageId;
    }
  }
}
