package com.nsangusa.news.newsletter.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

class NewsletterOperationsTest {
  private static final String SECRET = "test-secret-with-at-least-thirty-two-characters";
  private static final byte[] RESEND_SECRET_BYTES =
      "resend-webhook-test-secret-at-least-32".getBytes(StandardCharsets.UTF_8);
  private static final String RESEND_SECRET =
      "whsec_" + Base64.getEncoder().encodeToString(RESEND_SECRET_BYTES);

  @Test
  void onlySignedDeliveryCallbacksEstablishDeliveryConfirmation() throws Exception {
    var webhooks = mock(ProviderWebhookRepository.class);
    var deliveries = mock(NewsletterDeliveryRepository.class);
    var delivery = new NewsletterDelivery(UUID.randomUUID(), UUID.randomUUID(), "callback:test");
    delivery.delivered(delivery.beginAttempt(Instant.now()), "message-1");
    when(deliveries.findByProviderMessageId("message-1")).thenReturn(Optional.of(delivery));
    when(webhooks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var service =
        new NewsletterWebhookService(
            webhooks,
            deliveries,
            mock(NewsletterSubscriptionRepository.class),
            mock(SuppressionEntryRepository.class),
            new ObjectMapper(),
            SECRET,
            "mail");
    String body = "{\"id\":\"delivery-1\",\"type\":\"delivered\",\"messageId\":\"message-1\"}";
    assertThat(delivery.deliveryConfirmedAt).isNull();
    assertThatThrownBy(() -> service.process("mail", null, null, null, "invalid", body))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(delivery.deliveryConfirmedAt).isNull();
    service.process("mail", null, null, null, signature(body), body);
    assertThat(delivery.deliveryConfirmedAt).isNotNull();
  }

  @Test
  void unsubscribeTokensAreStableAndSubscriptionSpecific() {
    var tokens = new UnsubscribeTokenService(SECRET);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    assertThat(tokens.tokenFor(first)).isEqualTo(tokens.tokenFor(first));
    assertThat(tokens.tokenFor(first)).isNotEqualTo(tokens.tokenFor(second));
  }

  @Test
  void oldVerificationLinkCannotReactivateAnUnsubscribedSubscription() {
    var subscription =
        new NewsletterSubscription(
            UUID.randomUUID(), "reader@example.test", "footer", "immediate", "verify", "remove");
    subscription.unsubscribe("remove");

    assertThatThrownBy(() -> subscription.confirm("verify"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Subscription is not pending confirmation");
  }

  @Test
  void rejectsUnsignedProviderWebhook() {
    var fixture = fixture();

    assertThatThrownBy(
            () ->
                fixture.service.process(
                    "mail",
                    null,
                    null,
                    null,
                    "invalid",
                    "{\"id\":\"event-1\",\"type\":\"bounce\"}"))
        .isInstanceOf(AccessDeniedException.class);
    verify(fixture.webhooks, never()).save(any());
  }

  @Test
  void bounceSuppressesSubscriberAndRecordsSuppression() throws Exception {
    var fixture = fixture();
    var subscription =
        new NewsletterSubscription(
            UUID.randomUUID(), "Reader@Example.com", "footer", "immediate", "verify", "remove");
    when(fixture.subscriptions.findByEmail("reader@example.com"))
        .thenReturn(Optional.of(subscription));
    String body =
        """
        {"id":"event-1","type":"bounce","messageId":"message-1","email":"Reader@Example.com"}
        """;

    fixture.service.process("mail", null, null, null, signature(body), body);

    assertThat(subscription.status).isEqualTo("suppressed");
    var suppression = ArgumentCaptor.forClass(SuppressionEntry.class);
    verify(fixture.suppressions).save(suppression.capture());
    assertThat(suppression.getValue().reason).isEqualTo("bounce");
  }

  @Test
  void resendComplaintUsesSvixHeadersAndNestedPayload() throws Exception {
    var fixture = resendFixture();
    var subscription =
        new NewsletterSubscription(
            UUID.randomUUID(), "Reader@Example.com", "footer", "immediate", "verify", "remove");
    when(fixture.subscriptions.findByEmail("reader@example.com"))
        .thenReturn(Optional.of(subscription));
    String body =
        """
        {
          "type":"email.complained",
          "created_at":"2026-09-07T10:00:00Z",
          "data":{
            "email_id":"email-1",
            "message_id":"<newsletter-id@nsangusa>",
            "to":["Reader@Example.com"]
          }
        }
        """;
    String webhookId = "msg_webhook_1";
    String timestamp = Long.toString(Instant.now().getEpochSecond());

    fixture.service.process(
        "resend", webhookId, timestamp, resendSignature(webhookId, timestamp, body), null, body);

    assertThat(subscription.status).isEqualTo("suppressed");
    var suppression = ArgumentCaptor.forClass(SuppressionEntry.class);
    verify(fixture.suppressions).save(suppression.capture());
    assertThat(suppression.getValue().reason).isEqualTo("complaint");
  }

  @Test
  void resendRejectsAValidSignatureWithAStaleTimestamp() throws Exception {
    var fixture = resendFixture();
    String body = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"email-1\"}}";
    String webhookId = "msg_webhook_stale";
    String timestamp = Long.toString(Instant.now().minusSeconds(600).getEpochSecond());

    assertThatThrownBy(
            () ->
                fixture.service.process(
                    "resend",
                    webhookId,
                    timestamp,
                    resendSignature(webhookId, timestamp, body),
                    null,
                    body))
        .isInstanceOf(AccessDeniedException.class);
    verify(fixture.webhooks, never()).save(any());
  }

  private static Fixture fixture() {
    var webhooks = mock(ProviderWebhookRepository.class);
    var deliveries = mock(NewsletterDeliveryRepository.class);
    var subscriptions = mock(NewsletterSubscriptionRepository.class);
    var suppressions = mock(SuppressionEntryRepository.class);
    when(webhooks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    return new Fixture(
        new NewsletterWebhookService(
            webhooks, deliveries, subscriptions, suppressions, new ObjectMapper(), SECRET, "mail"),
        webhooks,
        subscriptions,
        suppressions);
  }

  private static Fixture resendFixture() {
    var webhooks = mock(ProviderWebhookRepository.class);
    var deliveries = mock(NewsletterDeliveryRepository.class);
    var subscriptions = mock(NewsletterSubscriptionRepository.class);
    var suppressions = mock(SuppressionEntryRepository.class);
    when(webhooks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    return new Fixture(
        new NewsletterWebhookService(
            webhooks,
            deliveries,
            subscriptions,
            suppressions,
            new ObjectMapper(),
            RESEND_SECRET,
            "resend"),
        webhooks,
        subscriptions,
        suppressions);
  }

  private static String signature(String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private static String resendSignature(String id, String timestamp, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(RESEND_SECRET_BYTES, "HmacSHA256"));
    String signature =
        Base64.getEncoder()
            .encodeToString(
                mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    return "v1," + signature;
  }

  private record Fixture(
      NewsletterWebhookService service,
      ProviderWebhookRepository webhooks,
      NewsletterSubscriptionRepository subscriptions,
      SuppressionEntryRepository suppressions) {}
}
