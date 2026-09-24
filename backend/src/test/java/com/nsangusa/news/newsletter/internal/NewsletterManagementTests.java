package com.nsangusa.news.newsletter.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.newsletter.NewsletterManagementService.Reconciliation;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class NewsletterManagementTests {
  private final NewsletterManagementRepository management =
      mock(NewsletterManagementRepository.class);
  private final NewsletterSubscriptionRepository subscriptions =
      mock(NewsletterSubscriptionRepository.class);
  private final NewsletterDeliveryRepository deliveries = mock(NewsletterDeliveryRepository.class);
  private final UnsubscribeTokenService tokens =
      new UnsubscribeTokenService("newsletter-test-secret-at-least-32-bytes");
  private final DurableCommandExecutor commands = mock(DurableCommandExecutor.class);
  private final AuditService audit = mock(AuditService.class);
  private final NewsletterManagementApplicationService service =
      new NewsletterManagementApplicationService(
          management, subscriptions, deliveries, tokens, commands, audit);

  @Test
  void accountDeletionWithdrawsConsentEvenAfterTheEmailSigningSecretRotates() {
    var subscription = subscription();
    UUID userId = UUID.randomUUID();
    subscription.userId = userId;
    when(subscriptions.findByUserId(userId)).thenReturn(Optional.of(subscription));
    var accounts =
        new NewsletterApplicationService(
            subscriptions,
            deliveries,
            tokens,
            mock(EmailDeliveryProvider.class),
            audit,
            "https://publication.example.test");
    accounts.unsubscribeAndUnlinkAccount(userId);
    assertThat(subscription.userId).isNull();
    assertThat(subscription.status).isEqualTo("unsubscribed");
    assertThat(subscription.verificationTokenHash).startsWith("invalidated:");
  }

  @Test
  void requestsAreNormalizedAndDoNotSendOrRevealUnknownPendingOrSuppressedSubscriptions() {
    for (String state : java.util.List.of("pending", "suppressed", "unsubscribed")) {
      var subscription = subscription();
      subscription.status = state;
      when(subscriptions.findLockedByEmail("reader@example.test"))
          .thenReturn(Optional.of(subscription));
      service.requestPreferenceLink(" Reader@Example.test ");
    }
    service.requestPreferenceLink("unknown@example.test");
    verifyNoInteractions(management);
    var subscription = subscription();
    when(subscriptions.findLockedByEmail(subscription.email)).thenReturn(Optional.of(subscription));
    service.requestPreferenceLink(subscription.email);
    verify(management).enqueueLink(eq(subscription), any());
  }

  @Test
  void preferenceReadIsAddressFreeAndNeverMutatesOrConsumesTheAuthorization() {
    var subscription = subscription();
    String token = authorize(subscription, Instant.now().plusSeconds(1800));
    var view = service.preferences(subscription.id, token);
    assertThat(view.frequency()).isEqualTo("weekly");
    assertThat(view.toString()).doesNotContain(subscription.email, token);
    assertThat(service.preferences(subscription.id, token)).isEqualTo(view);
    verifyNoInteractions(commands, audit);
    verify(subscriptions, never()).save(any());
  }

  @Test
  void forgedExpiredWrongScopeAndRevokedTokensCannotChangePreferences() {
    var subscription = subscription();
    String token = authorize(subscription, Instant.now().plusSeconds(1800));
    assertThatThrownBy(() -> service.preferences(subscription.id, token + "x"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> service.preferences(subscription.id, tokens.tokenFor(subscription.id)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> service.preferences(UUID.randomUUID(), token))
        .isInstanceOf(ResponseStatusException.class);
    subscription.status = "unsubscribed";
    assertThatThrownBy(() -> service.preferences(subscription.id, token))
        .isInstanceOf(ResponseStatusException.class);
    subscription.status = "confirmed";
    String expired = authorize(subscription, Instant.now().minusSeconds(1));
    assertThatThrownBy(() -> service.preferences(subscription.id, expired))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(commands, audit);
    assertThat(subscription.frequency).isEqualTo("weekly");
  }

  @Test
  void preferenceMutationIsAuthorizedBeforeDurableExecutionAndAuditsNoAddress() {
    var subscription = subscription();
    String token = authorize(subscription, Instant.now().plusSeconds(1800));
    executeCommands();
    service.updatePreferences(subscription.id, token, "daily", "request-key-123");
    assertThat(subscription.frequency).isEqualTo("daily");
    verify(commands).execute(eq(subscription.id), eq("request-key-123"), anyString(), any(), any());
    verify(audit)
        .record(
            isNull(),
            eq("newsletter.preferences.updated"),
            eq("newsletter_subscription"),
            eq(subscription.id),
            eq(java.util.Map.of("frequency", "daily", "authorization", "email-token")));
  }

  @Test
  void reconciliationRequiresEvidenceAndNeverReopensTheDeliveryForResending() {
    executeCommands();
    var delivery = new NewsletterDelivery(UUID.randomUUID(), UUID.randomUUID(), "campaign:test");
    delivery.status = "reconciliation_required";
    when(deliveries.findLockedById(delivery.id)).thenReturn(Optional.of(delivery));
    UUID actor = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                service.reconcile(
                    delivery.id,
                    actor,
                    new Reconciliation("provider_accepted", "ticket:123", null),
                    "request-key"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.reconcile(
                    delivery.id,
                    actor,
                    new Reconciliation("not_sent", "reader@example.test", null),
                    "request-key"))
        .isInstanceOf(IllegalArgumentException.class);
    var request = new Reconciliation("abandoned", "ticket:123", null);
    service.reconcile(delivery.id, actor, request, "request-key");
    verify(management).recordReconciliation(delivery.id, actor, request);
    delivery.status = "reconciled";
    when(deliveries.findLockedBySubscriptionIdAndCampaignKey(
            delivery.subscriptionId, delivery.campaignKey))
        .thenReturn(Optional.of(delivery));
    assertThat(
            new NewsletterDeliveryReservationService(deliveries)
                .reserve(delivery.subscriptionId, delivery.articleId, delivery.campaignKey))
        .isEmpty();
  }

  @Test
  void smtpCannotBlindlyRetryAnUncertainOrConcurrentSend() {
    var delivery = new NewsletterDelivery(UUID.randomUUID(), UUID.randomUUID(), "smtp:test");
    delivery.beginAttempt(Instant.now());
    delivery.providerIdempotencyApplied = false;
    when(deliveries.findLockedBySubscriptionIdAndCampaignKey(
            delivery.subscriptionId, delivery.campaignKey))
        .thenReturn(Optional.of(delivery));
    var reservations =
        new NewsletterDeliveryReservationService(
            deliveries, Duration.ofHours(24), Duration.ofMinutes(10), false);
    assertThat(
            reservations.reserve(delivery.subscriptionId, delivery.articleId, delivery.campaignKey))
        .isEmpty();
    assertThat(delivery.status).isEqualTo("pending");
    delivery.attemptStartedAt = Instant.now().minusSeconds(601);
    assertThat(
            reservations.reserve(delivery.subscriptionId, delivery.articleId, delivery.campaignKey))
        .isEmpty();
    assertThat(delivery.status).isEqualTo("reconciliation_required");
    assertThat(delivery.attemptCount).isEqualTo(1);
  }

  @Test
  void unsubscribingNeverRemovesSuppressionAndConfirmationIsIdempotent() {
    var subscription = subscription();
    subscription.status = "pending";
    subscription.confirm("verify");
    Instant verifiedAt = subscription.verifiedAt;
    subscription.confirm("verify");
    assertThat(subscription.verifiedAt).isEqualTo(verifiedAt);
    subscription.suppress();
    subscription.unsubscribe("remove");
    assertThat(subscription.status).isEqualTo("suppressed");
  }

  private NewsletterSubscription subscription() {
    var subscription =
        new NewsletterSubscription(
            UUID.randomUUID(), "reader@example.test", "footer", "weekly", "verify", "remove");
    subscription.confirm("verify");
    return subscription;
  }

  private String authorize(NewsletterSubscription subscription, Instant expires) {
    var link =
        new NewsletterManagementRepository.PreferenceLink(
            UUID.randomUUID(), subscription.id, subscription.verifiedAt, expires);
    when(management.preferenceLink(link.id(), subscription.id)).thenReturn(Optional.of(link));
    when(subscriptions.findById(subscription.id)).thenReturn(Optional.of(subscription));
    return tokens.preferenceTokenFor(link.id(), subscription.id, expires, subscription.verifiedAt);
  }

  @SuppressWarnings("unchecked")
  private void executeCommands() {
    when(commands.execute(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> ((Supplier<String>) invocation.getArgument(4)).get());
  }
}
