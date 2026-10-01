package com.nsangusa.news.newsletter.internal;

import com.nsangusa.news.articles.ArticleService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class NewsletterDigestScheduler {
  private final ArticleService articles;
  private final NewsletterSubscriptionRepository subscriptions;
  private final NewsletterDeliveryReservationService deliveryReservations;
  private final NewsletterCampaignRepository campaigns;
  private final SuppressionEntryRepository suppressions;
  private final EmailDeliveryProvider email;
  private final UnsubscribeTokenService unsubscribeTokens;
  private final String publicBaseUrl;

  NewsletterDigestScheduler(
      ArticleService articles,
      NewsletterSubscriptionRepository subscriptions,
      NewsletterDeliveryReservationService deliveryReservations,
      NewsletterCampaignRepository campaigns,
      SuppressionEntryRepository suppressions,
      EmailDeliveryProvider email,
      UnsubscribeTokenService unsubscribeTokens,
      @Value("${news.public-base-url}") String publicBaseUrl) {
    this.articles = articles;
    this.subscriptions = subscriptions;
    this.deliveryReservations = deliveryReservations;
    this.campaigns = campaigns;
    this.suppressions = suppressions;
    this.email = email;
    this.unsubscribeTokens = unsubscribeTokens;
    this.publicBaseUrl = publicBaseUrl;
  }

  @Scheduled(cron = "${news.newsletter.daily-cron:0 0 7 * * *}", zone = "UTC")
  @Transactional
  void daily() {
    dispatch("daily", List.of("daily", "all"), "daily:" + LocalDate.now(ZoneOffset.UTC));
  }

  @Scheduled(cron = "${news.newsletter.weekly-cron:0 0 8 * * MON}", zone = "UTC")
  @Transactional
  void weekly() {
    dispatch("weekly", List.of("weekly", "all"), "weekly:" + LocalDate.now(ZoneOffset.UTC));
  }

  private void dispatch(String type, List<String> frequencies, String key) {
    if (campaigns.existsByCampaignKey(key)) {
      return;
    }
    var recent = articles.latestPublished(type.equals("daily") ? 10 : 30);
    if (recent.isEmpty()) {
      return;
    }
    var campaign = campaigns.save(new NewsletterCampaign(key, recent.getFirst().id(), type));
    StringBuilder text = new StringBuilder("Nsangusa " + type + " digest\n\n");
    StringBuilder html = new StringBuilder("<h1>Nsangusa " + type + " digest</h1><ul>");
    for (var article : recent) {
      text.append(article.headline()).append("\n");
      html.append("<li>")
          .append("<a href=\"")
          .append(
              org.owasp.encoder.Encode.forHtmlAttribute(
                  publicBaseUrl + "/articles/" + article.slug()))
          .append("\">")
          .append(org.owasp.encoder.Encode.forHtml(article.headline()))
          .append("</a>")
          .append("</li>");
    }
    html.append("</ul>");
    for (var subscription : subscriptions.findByStatusAndFrequencyIn("confirmed", frequencies)) {
      if (suppressions.existsByEmailHash(hash(subscription.email))) {
        continue;
      }
      String token = unsubscribeTokens.tokenFor(subscription.id);
      String unsubscribe =
          publicBaseUrl + "/newsletter/unsubscribe?id=" + subscription.id + "&token=" + token;
      var reservation = deliveryReservations.reserve(subscription.id, recent.getFirst().id(), key);
      if (reservation.isEmpty()) {
        continue;
      }
      var attempt = reservation.orElseThrow();
      try {
        deliveryReservations.delivered(
            attempt.deliveryId(),
            attempt.attemptToken(),
            email.sendNewsletter(
                attempt.providerIdempotencyKey(),
                subscription.email,
                "Your Nsangusa " + type + " digest",
                text
                    + "\nUnsubscribe: "
                    + unsubscribe
                    + "\nManage preferences: "
                    + publicBaseUrl
                    + "/newsletter/preferences",
                html
                    + "<p><a href=\""
                    + org.owasp.encoder.Encode.forHtmlAttribute(unsubscribe)
                    + "\">Unsubscribe</a></p><p><a href=\""
                    + org.owasp.encoder.Encode.forHtmlAttribute(
                        publicBaseUrl + "/newsletter/preferences")
                    + "\">Manage preferences</a></p>",
                publicBaseUrl
                    + "/api/v1/newsletter/unsubscribe?id="
                    + subscription.id
                    + "&token="
                    + token),
            campaign.id,
            null);
      } catch (RuntimeException exception) {
        deliveryReservations.failed(
            attempt.deliveryId(), attempt.attemptToken(), "provider_error", campaign.id, null);
        throw exception;
      }
    }
    campaign.complete();
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
