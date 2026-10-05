package com.nsangusa.news.newsletter.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

class NewsletterConfirmationDeliveryTests {
  @Test
  void newsletterSetsOneClickHeadersWithoutClaimingSmtpIdempotency() throws Exception {
    var sender = mock(JavaMailSender.class);
    var message = new MimeMessage(Session.getInstance(new Properties()));
    when(sender.createMimeMessage()).thenReturn(message);
    var provider = new JavaMailDeliveryProvider(sender, "news@example.test", "local-smtp");
    String unsubscribe =
        "https://news.example.test/api/v1/newsletter/unsubscribe?id=example&token=private";
    provider.sendNewsletter(
        "newsletter-test", "reader@example.test", "News", "Text", "<p>HTML</p>", unsubscribe);
    assertThat(message.getHeader("List-Unsubscribe", null)).isEqualTo("<" + unsubscribe + ">");
    assertThat(message.getHeader("List-Unsubscribe-Post", null))
        .isEqualTo("List-Unsubscribe=One-Click");
    assertThat(provider.supportsIdempotency()).isFalse();
  }

  @Test
  void confirmationUsesConfiguredSenderMultipartBodiesAndBrowserUrl() throws Exception {
    var subscriptions = mock(NewsletterSubscriptionRepository.class);
    var sender = mock(JavaMailSender.class);
    var message = new MimeMessage(Session.getInstance(new Properties()));
    when(sender.createMimeMessage()).thenReturn(message);
    var service =
        new NewsletterApplicationService(
            subscriptions,
            mock(NewsletterDeliveryRepository.class),
            new UnsubscribeTokenService("a-secret-that-is-at-least-32-characters"),
            new JavaMailDeliveryProvider(sender, "Nsangusa <editorial@example.test>", "local-smtp"),
            mock(com.nsangusa.news.audit.AuditService.class),
            "https://news.example.test/");

    var requested = service.subscribe(" Reader@Example.test ", "footer", "immediate");

    var subscription = ArgumentCaptor.forClass(NewsletterSubscription.class);
    verify(subscriptions).save(subscription.capture());
    verify(sender).send(message);
    assertThat(message.getFrom()[0].toString()).isEqualTo("Nsangusa <editorial@example.test>");
    assertThat(message.getAllRecipients()[0].toString()).isEqualTo("reader@example.test");
    assertThat(message.getHeader("Resend-Idempotency-Key")).isNull();
    var bodies = new ArrayList<String>();
    bodies(message, bodies);
    var link =
        java.util.regex.Pattern.compile(
                "https://news\\.example\\.test/en/newsletter/confirm\\?id="
                    + requested.subscriptionId()
                    + "&token=([A-Za-z0-9_-]+)")
            .matcher(bodies.getFirst());
    assertThat(link.find()).isTrue();
    String url = link.group();
    assertThat(bodies).anySatisfy(body -> assertThat(body).contains(url));
    assertThat(bodies)
        .anySatisfy(
            body -> assertThat(body).contains("href=\"" + url.replace("&", "&amp;") + "\""));
    assertThat(message.isMimeType("multipart/*")).isTrue();
    assertThat(subscription.getValue().status).isEqualTo("pending");
    subscription.getValue().confirm(link.group(1));
    assertThat(subscription.getValue().status).isEqualTo("confirmed");
  }

  @Test
  void resendConfirmationRetainsTheSameProviderIdempotencyHeader() throws Exception {
    var sender = mock(JavaMailSender.class);
    var message = new MimeMessage(Session.getInstance(new Properties()));
    when(sender.createMimeMessage()).thenReturn(message);
    var provider = new JavaMailDeliveryProvider(sender, "news@example.test", "resend");

    provider.send(
        "newsletter-confirmation/123", "reader@example.test", "Confirm", "Text", "<p>HTML</p>");

    assertThat(message.getHeader("Resend-Idempotency-Key", null))
        .isEqualTo("newsletter-confirmation/123");
    assertThat(message.getMessageID()).doesNotContain("newsletter-confirmation/");
    verify(sender).send(message);
  }

  @Test
  void transportFailureDoesNotReportVerificationSent() {
    var subscriptions = mock(NewsletterSubscriptionRepository.class);
    var provider = mock(EmailDeliveryProvider.class);
    when(provider.send(any(), any(), any(), any(), any()))
        .thenThrow(new org.springframework.mail.MailSendException("SMTP unavailable"));
    var service =
        new NewsletterApplicationService(
            subscriptions,
            mock(NewsletterDeliveryRepository.class),
            new UnsubscribeTokenService("a-secret-that-is-at-least-32-characters"),
            provider,
            mock(com.nsangusa.news.audit.AuditService.class),
            "https://news.example.test");

    assertThatThrownBy(() -> service.subscribe("reader@example.test", "footer", "immediate"))
        .isInstanceOf(org.springframework.mail.MailSendException.class);
  }

  private static void bodies(Part part, java.util.List<String> result) throws Exception {
    if (part.isMimeType("multipart/*")) {
      var multipart = (Multipart) part.getContent();
      for (int index = 0; index < multipart.getCount(); index++) {
        bodies(multipart.getBodyPart(index), result);
      }
    } else if (part.isMimeType("text/*")) {
      result.add((String) part.getContent());
    }
  }
}
