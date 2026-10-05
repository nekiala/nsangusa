package com.nsangusa.news.newsletter.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class NewsletterPreferenceMailDispatcher {
  private final NewsletterPreferenceMailQueue queue;
  private final EmailDeliveryProvider email;
  private final UnsubscribeTokenService tokens;
  private final String publicBaseUrl;

  NewsletterPreferenceMailDispatcher(
      NewsletterPreferenceMailQueue queue,
      EmailDeliveryProvider email,
      UnsubscribeTokenService tokens,
      @Value("${news.public-base-url}") String publicBaseUrl) {
    this.queue = queue;
    this.email = email;
    this.tokens = tokens;
    this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
  }

  private NewsletterSubscriptionRepository subscriptions;

  /** Supplies each subscriber's language; without it emails are English. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSubscriptions(NewsletterSubscriptionRepository subscriptions) {
    this.subscriptions = subscriptions;
  }

  @Scheduled(fixedDelayString = "${news.newsletter.preference-mail-delay:10000}")
  void dispatch() {
    for (var id : queue.pending()) {
      var claim = queue.claim(id);
      if (claim.isEmpty()) continue;
      var message = claim.orElseThrow();
      String token =
          tokens.preferenceTokenFor(
              message.id(), message.subscriptionId(), message.expiresAt(), message.verifiedAt());
      var wording =
          NewsletterText.in(
              subscriptions == null
                  ? null
                  : subscriptions
                      .findById(message.subscriptionId())
                      .map(subscription -> subscription.language)
                      .orElse(null));
      String link =
          wording.url(
              publicBaseUrl,
              "/newsletter/preferences?id=" + message.subscriptionId() + "&token=" + token);
      String note = wording.preferencesNote().formatted(message.expiresAt());
      try {
        email.send(
            "newsletter-preferences/" + message.id(),
            message.email(),
            wording.preferencesSubject(),
            wording.preferencesSubject() + ":\n" + link + "\n\n" + note,
            "<p><a href=\""
                + org.owasp.encoder.Encode.forHtmlAttribute(link)
                + "\">"
                + org.owasp.encoder.Encode.forHtml(wording.preferencesAction())
                + "</a></p><p>"
                + org.owasp.encoder.Encode.forHtml(note)
                + "</p>");
        queue.complete(id, true);
      } catch (RuntimeException failure) {
        // SMTP may have accepted the message before an error. Never blindly resend the claim.
        queue.complete(id, false);
      }
    }
  }
}
