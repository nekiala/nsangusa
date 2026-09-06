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

  @Test
  void unsubscribeTokensAreStableAndSubscriptionSpecific() {
    var tokens = new UnsubscribeTokenService(SECRET);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    assertThat(tokens.tokenFor(first)).isEqualTo(tokens.tokenFor(first));
    assertThat(tokens.tokenFor(first)).isNotEqualTo(tokens.tokenFor(second));
  }

  @Test
  void rejectsUnsignedProviderWebhook() {
    var fixture = fixture();

    assertThatThrownBy(
            () ->
                fixture.service.process(
                    "mail", "invalid", "{\"id\":\"event-1\",\"type\":\"bounce\"}"))
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

    fixture.service.process("mail", signature(body), body);

    assertThat(subscription.status).isEqualTo("suppressed");
    var suppression = ArgumentCaptor.forClass(SuppressionEntry.class);
    verify(fixture.suppressions).save(suppression.capture());
    assertThat(suppression.getValue().reason).isEqualTo("bounce");
  }

  private static Fixture fixture() {
    var webhooks = mock(ProviderWebhookRepository.class);
    var deliveries = mock(NewsletterDeliveryRepository.class);
    var subscriptions = mock(NewsletterSubscriptionRepository.class);
    var suppressions = mock(SuppressionEntryRepository.class);
    when(webhooks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    return new Fixture(
        new NewsletterWebhookService(
            webhooks, deliveries, subscriptions, suppressions, new ObjectMapper(), SECRET),
        webhooks,
        subscriptions,
        suppressions);
  }

  private static String signature(String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private record Fixture(
      NewsletterWebhookService service,
      ProviderWebhookRepository webhooks,
      NewsletterSubscriptionRepository subscriptions,
      SuppressionEntryRepository suppressions) {}
}
