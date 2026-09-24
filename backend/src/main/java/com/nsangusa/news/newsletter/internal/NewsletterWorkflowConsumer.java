package com.nsangusa.news.newsletter.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.ArticlePublished;
import com.nsangusa.news.integration.NewsEvents.NewsletterDispatchRequested;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class NewsletterWorkflowConsumer {
  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final DurableEventPublisher events;
  private final NewsletterSubscriptionRepository subscriptions;
  private final NewsletterDeliveryReservationService deliveryReservations;
  private final NewsletterCampaignRepository campaigns;
  private final SuppressionEntryRepository suppressions;
  private final EmailDeliveryProvider mailSender;
  private final UnsubscribeTokenService unsubscribeTokens;
  private final String publicBaseUrl;

  NewsletterWorkflowConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      DurableEventPublisher events,
      NewsletterSubscriptionRepository subscriptions,
      NewsletterDeliveryReservationService deliveryReservations,
      NewsletterCampaignRepository campaigns,
      SuppressionEntryRepository suppressions,
      EmailDeliveryProvider mailSender,
      UnsubscribeTokenService unsubscribeTokens,
      @Value("${news.public-base-url}") String publicBaseUrl) {
    this.reader = reader;
    this.processed = processed;
    this.events = events;
    this.subscriptions = subscriptions;
    this.deliveryReservations = deliveryReservations;
    this.campaigns = campaigns;
    this.suppressions = suppressions;
    this.mailSender = mailSender;
    this.unsubscribeTokens = unsubscribeTokens;
    this.publicBaseUrl = publicBaseUrl;
  }

  @KafkaListener(topics = EventTopics.PUBLICATION, groupId = "newsletter-request-v1")
  @Transactional
  void publication(String json) {
    if (!"ArticlePublished".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, ArticlePublished.class);
    if (processed.wasProcessed(event.eventId(), "newsletter-request-v1")) {
      return;
    }
    if (event.payload().newsletterEligible()) {
      String campaignKey = "article:" + event.payload().articleId() + ":initial";
      events.enqueue(
          "NewsletterDispatchRequested",
          event.payload().articleId(),
          event.correlationId(),
          event.eventId(),
          "newsletter-dispatch:" + campaignKey,
          new NewsletterDispatchRequested(
              event.payload().articleId(),
              campaignKey,
              event.payload().slug(),
              event.payload().headline()));
    }
    processed.markProcessed(event.eventId(), "newsletter-request-v1");
  }

  @KafkaListener(topics = EventTopics.NOTIFICATIONS, groupId = "newsletter-delivery-v1")
  @Transactional
  void dispatch(String json) {
    if (!"NewsletterDispatchRequested".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, NewsletterDispatchRequested.class);
    if (processed.wasProcessed(event.eventId(), "newsletter-delivery-v1")) {
      return;
    }
    NewsletterCampaign campaign =
        campaigns
            .findByCampaignKey(event.payload().campaignKey())
            .orElseGet(
                () ->
                    campaigns.save(
                        new NewsletterCampaign(
                            event.payload().campaignKey(),
                            event.payload().articleId(),
                            "immediate")));
    for (var subscription :
        subscriptions.findByStatusAndFrequencyIn("confirmed", List.of("immediate", "all"))) {
      if (suppressions.existsByEmailHash(hash(subscription.email))) {
        continue;
      }
      NewsletterDeliveryReservationService.DeliveryAttempt attempt;
      try {
        var reservation =
            deliveryReservations.reserve(
                subscription.id, event.payload().articleId(), event.payload().campaignKey());
        if (reservation.isEmpty()) {
          continue;
        }
        attempt = reservation.orElseThrow();
      } catch (org.springframework.dao.DataIntegrityViolationException duplicateReservation) {
        continue;
      }
      String articleUrl = publicBaseUrl + "/articles/" + event.payload().slug();
      String unsubscribeUrl =
          publicBaseUrl
              + "/newsletter/unsubscribe?id="
              + subscription.id
              + "&token="
              + unsubscribeTokens.tokenFor(subscription.id);
      String text =
          event.payload().headline()
              + "\n\n"
              + articleUrl
              + "\n\nUnsubscribe: "
              + unsubscribeUrl
              + "\nManage preferences: "
              + publicBaseUrl
              + "/newsletter/preferences";
      String html =
          "<h1>"
              + org.owasp.encoder.Encode.forHtml(event.payload().headline())
              + "</h1><p><a href=\""
              + org.owasp.encoder.Encode.forHtmlAttribute(articleUrl)
              + "\">Read the article</a></p><p><a href=\""
              + org.owasp.encoder.Encode.forHtmlAttribute(unsubscribeUrl)
              + "\">Unsubscribe</a></p><p><a href=\""
              + org.owasp.encoder.Encode.forHtmlAttribute(publicBaseUrl + "/newsletter/preferences")
              + "\">Manage preferences</a></p>";
      try {
        deliveryReservations.delivered(
            attempt.deliveryId(),
            attempt.attemptToken(),
            mailSender.sendNewsletter(
                attempt.providerIdempotencyKey(),
                subscription.email,
                event.payload().headline(),
                text,
                html,
                publicBaseUrl
                    + "/api/v1/newsletter/unsubscribe?id="
                    + subscription.id
                    + "&token="
                    + unsubscribeTokens.tokenFor(subscription.id)));
      } catch (RuntimeException exception) {
        deliveryReservations.failed(attempt.deliveryId(), attempt.attemptToken(), "provider_error");
        throw exception;
      }
    }
    campaign.complete();
    processed.markProcessed(event.eventId(), "newsletter-delivery-v1");
  }

  private static String hash(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
