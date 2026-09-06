package com.nsangusa.news.newsletter.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.NewsletterDispatchRequested;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.javamail.JavaMailSender;

class NewsletterDuplicatePreventionTests {
  @Test
  void duplicateSubscriptionIsRejectedAfterEmailNormalization() {
    var subscriptions = mock(NewsletterSubscriptionRepository.class);
    var deliveries = mock(NewsletterDeliveryRepository.class);
    var mail = mock(JavaMailSender.class);
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
            "https://news.example.test");

    assertThatThrownBy(() -> service.subscribe(" Reader@Example.com ", "footer", "immediate"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Subscription already exists");
    verify(subscriptions, never()).save(any());
    verify(mail, never()).send(any(org.springframework.mail.SimpleMailMessage.class));
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
    when(fixture.deliveries.existsBySubscriptionIdAndCampaignKey(
            subscription.id, event.payload().campaignKey()))
        .thenReturn(true);

    fixture.consumer.dispatch("event");

    verify(fixture.deliveries, never()).saveAndFlush(any());
    verify(fixture.mail, never()).send(any(), any(), any(), any());
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

    verify(fixture.mail, never()).send(any(), any(), any(), any());
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
                  if (method.getName().equals("existsBySubscriptionIdAndCampaignKey")) {
                    return deliveryStore.containsKey(
                        new DeliveryKey((UUID) arguments[0], (String) arguments[1]));
                  }
                  if (method.getName().equals("saveAndFlush")) {
                    var delivery = (NewsletterDelivery) arguments[0];
                    deliveryStore.put(
                        new DeliveryKey(delivery.subscriptionId, delivery.campaignKey), delivery);
                    return delivery;
                  }
                  if (method.getName().equals("findById")) {
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
            new NewsletterDeliveryReservationService(deliveries),
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
            new NewsletterDeliveryReservationService(deliveries),
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

    @Override
    public String send(String recipient, String subject, String text, String html) {
      acceptedMessages++;
      if (acceptedMessages == 1) {
        throw new IllegalStateException("response lost after provider acceptance");
      }
      return "message-" + acceptedMessages;
    }
  }
}
