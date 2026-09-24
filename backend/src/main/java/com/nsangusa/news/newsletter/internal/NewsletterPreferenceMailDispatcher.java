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

  @Scheduled(fixedDelayString = "${news.newsletter.preference-mail-delay:10000}")
  void dispatch() {
    for (var id : queue.pending()) {
      var claim = queue.claim(id);
      if (claim.isEmpty()) continue;
      var message = claim.orElseThrow();
      String token =
          tokens.preferenceTokenFor(
              message.id(), message.subscriptionId(), message.expiresAt(), message.verifiedAt());
      String link =
          publicBaseUrl
              + "/newsletter/preferences?id="
              + message.subscriptionId()
              + "&token="
              + token;
      try {
        email.send(
            "newsletter-preferences/" + message.id(),
            message.email(),
            "Manage your newsletter preferences",
            "Manage your newsletter preferences:\n"
                + link
                + "\n\nThis private link expires at "
                + message.expiresAt()
                + " (30 minutes after the request). "
                + "Opening it makes no changes. If you did not request it, ignore this email.",
            "<p><a href=\""
                + org.owasp.encoder.Encode.forHtmlAttribute(link)
                + "\">Manage newsletter preferences</a></p>"
                + "<p>This private link expires at "
                + message.expiresAt()
                + " (30 minutes after the request). Opening it makes no changes. "
                + "If you did not request it, ignore this email.</p>");
        queue.complete(id, true);
      } catch (RuntimeException failure) {
        // SMTP may have accepted the message before an error. Never blindly resend the claim.
        queue.complete(id, false);
      }
    }
  }
}
