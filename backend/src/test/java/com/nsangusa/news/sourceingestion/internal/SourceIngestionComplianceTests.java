package com.nsangusa.news.sourceingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.XPostDiscovered;
import com.nsangusa.news.integration.SystemActors;
import com.nsangusa.news.sourceingestion.SourceIngestionService.ProviderPostSnapshot;
import com.nsangusa.news.sourceingestion.SourceIngestionService.SourceRelationshipInput;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SourceIngestionComplianceTests {
  @Mock MonitoredXAccountRepository accounts;
  @Mock SourcePostRepository posts;
  @Mock BlockedSourceAccountRepository blockedAccounts;
  @Mock SourceTombstoneRepository tombstones;
  @Mock SourceComplianceActionRepository complianceActions;
  @Mock SourceRelationshipRepository relationships;
  @Mock DurableEventPublisher events;
  @Mock ReplaySafetyRegistry replaySafety;
  @Mock IncomingEventReader reader;
  @Mock ProcessedEventRegistry processed;

  private SourceIngestionApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new SourceIngestionApplicationService(
            accounts,
            posts,
            blockedAccounts,
            tombstones,
            complianceActions,
            relationships,
            events,
            replaySafety);
  }

  @Test
  void deletionScrubsContentCreatesTombstoneAndSuppressesReplay() {
    UUID actorId = UUID.randomUUID();
    var post =
        new SourcePost(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "1900000000000000000",
            "123456",
            "official",
            "https://x.com/official/status/1900000000000000000",
            "content that must be deleted",
            Instant.now().minusSeconds(60));
    when(posts.findLockedById(post.id)).thenReturn(java.util.Optional.of(post));

    service.applyComplianceDeletion(post.id, "X deletion notice", actorId);

    assertThat(post.status).isEqualTo("deleted");
    assertThat(post.permittedText).isEqualTo("[deleted by source]");
    assertThat(post.reconciliationState).isEqualTo("pending");
    assertThat(post.contentDeletedAt).isNotNull();
    verify(tombstones).save(any(SourceTombstone.class));
    verify(replaySafety).suppressAggregate(post.id, "source content deleted", actorId);
    verify(complianceActions).save(any(SourceComplianceAction.class));
  }

  @Test
  void tombstonedPostCannotBeDiscoveredAgain() {
    UUID accountId = UUID.randomUUID();
    var account = new MonitoredXAccount(accountId, "123456", "official", "Official", "world", 0.5);
    when(accounts.findById(accountId)).thenReturn(java.util.Optional.of(account));
    when(tombstones.existsByPostId("1900000000000000000")).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.discoverPost(
                    accountId,
                    "1900000000000000000",
                    "https://x.com/official/status/1900000000000000000",
                    "content",
                    Instant.now().minusSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Post already ingested");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://x.com/BCC_RDC/status/2101913409423876230",
        "https://x.com/BCC_RDC/status/2101913409423876230?s=20",
        "https://x.com/BCC_RDC/status/2101913409423876230?s=20&t=share-token#replies",
        "https://x.com/BCC_RDC/status/2101913409423876230#replies",
        "  HTTPS://X.COM/bcc_rdc/status/2101913409423876230?s=20  "
      })
  void discoveryStoresAndEmitsOnlyTheCanonicalIdentity(String sharedUrl) {
    var account =
        new MonitoredXAccount(UUID.randomUUID(), "123456", "BCC_RDC", "BCC", "economy", 0.5);
    when(accounts.findById(account.id)).thenReturn(java.util.Optional.of(account));

    UUID sourceId =
        service.discoverPost(
            account.id,
            "2101913409423876230",
            sharedUrl,
            "Indicative daily exchange rates",
            Instant.now().minusSeconds(60));

    var stored = ArgumentCaptor.forClass(SourcePost.class);
    verify(posts).save(stored.capture());
    assertThat(stored.getValue().canonicalUrl)
        .isEqualTo("https://x.com/BCC_RDC/status/2101913409423876230");
    var payload = ArgumentCaptor.forClass(XPostDiscovered.class);
    verify(events)
        .enqueue(
            eq("XPostDiscovered"),
            eq(sourceId),
            eq(sourceId),
            isNull(),
            eq("x-post-discovered:123456:2101913409423876230"),
            payload.capture());
    assertThat(payload.getValue().canonicalUrl()).isEqualTo(stored.getValue().canonicalUrl);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://x.com/BCC_RDC/status/2101913409423876230?s=20",
        "https://example.test/BCC_RDC/status/2101913409423876230?s=20",
        "https://x.com.example.test/BCC_RDC/status/2101913409423876230?s=20",
        "https://user@x.com/BCC_RDC/status/2101913409423876230?s=20",
        "https://x.com:443/BCC_RDC/status/2101913409423876230?s=20",
        "https://x.com/other/status/2101913409423876230?s=20",
        "https://x.com/BCC_RDC/status/2101913409423876231?s=20",
        "https://x.com/%42CC_RDC/status/2101913409423876230?s=20",
        "https://x.com/other/../BCC_RDC/status/2101913409423876230?s=20",
        "https://x.com/BCC_RDC/status/2101913409423876230/photo/1",
        "not a URL"
      })
  void sharedLinksCannotBypassOriginAccountOrPostValidation(String invalidUrl) {
    var account =
        new MonitoredXAccount(UUID.randomUUID(), "123456", "BCC_RDC", "BCC", "economy", 0.5);
    when(accounts.findById(account.id)).thenReturn(java.util.Optional.of(account));

    assertThatThrownBy(
            () ->
                service.discoverPost(
                    account.id,
                    "2101913409423876230",
                    invalidUrl,
                    "Indicative daily exchange rates",
                    Instant.now().minusSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Source URL must be the official canonical X post URL");
    verifyNoInteractions(posts, events);
  }

  @Test
  void canonicalizingAShareLinkDoesNotPermitFuturePublicationTimes() {
    var account =
        new MonitoredXAccount(UUID.randomUUID(), "123456", "BCC_RDC", "BCC", "economy", 0.5);
    when(accounts.findById(account.id)).thenReturn(java.util.Optional.of(account));

    assertThatThrownBy(
            () ->
                service.discoverPost(
                    account.id,
                    "2101913409423876230",
                    "https://x.com/BCC_RDC/status/2101913409423876230?s=20",
                    "Indicative daily exchange rates",
                    Instant.now().plusSeconds(86400)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A valid X publication timestamp is required");
    verifyNoInteractions(posts, events);
  }

  @Test
  void accountViewExposesRateAndErrorHealth() {
    var account =
        new MonitoredXAccount(
            UUID.randomUUID(), "123456", "official", "Official", "world,news", 0.5);
    account.lastError = "upstream";
    account.consecutiveErrors = 3;
    account.lastSyncAttemptAt = Instant.now();
    when(accounts.findByRemovedAtIsNullOrderByHandleAsc()).thenReturn(List.of(account));

    var view = service.listAccounts(false).getFirst();

    assertThat(view.syncHealth()).isEqualTo("unhealthy");
    assertThat(view.lastError()).isEqualTo("upstream");
    assertThat(view.topics()).containsExactlyInAnyOrderElementsOf(Set.of("world", "news"));
  }

  @Test
  void normalizationDoesNotRepublishDeletedSourceContent() {
    var post =
        new SourcePost(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "1900000000000000000",
            "123456",
            "official",
            "https://x.com/official/status/1900000000000000000",
            "[deleted by source]",
            Instant.now().minusSeconds(60));
    post.status = "deleted";
    var payload =
        new XPostDiscovered(
            post.monitoredAccountId,
            post.postId,
            post.accountId,
            post.handle,
            post.canonicalUrl,
            post.permittedText,
            post.publishedAt);
    var envelope =
        new EventEnvelope<>(
            UUID.randomUUID(),
            "XPostDiscovered",
            1,
            post.id,
            post.id,
            null,
            Instant.now(),
            "test",
            java.util.Map.of(),
            "test",
            payload);
    when(reader.eventType("json")).thenReturn("XPostDiscovered");
    when(reader.read("json", XPostDiscovered.class)).thenReturn(envelope);
    when(posts.findLockedById(post.id)).thenReturn(java.util.Optional.of(post));
    var consumer = new SourceNormalizationConsumer(reader, processed, events, posts, accounts);

    consumer.consume("json");

    verify(processed).markProcessed(envelope.eventId(), "source-normalizer-v1");
    verifyNoInteractions(events);
  }

  @Test
  void providerEditUpdatesCurrentPostAndImmediatelySuppressesDerivedWork() {
    var post =
        new SourcePost(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "1900000000000000000",
            "123456",
            "official",
            "https://x.com/official/status/1900000000000000000",
            "Original report",
            Instant.now().minusSeconds(60));
    when(posts.findLockedById(post.id)).thenReturn(java.util.Optional.of(post));
    Instant observedAt = Instant.now();

    service.reconcileProviderPost(
        post.id,
        new ProviderPostSnapshot(
            "1900000000000000001",
            "https://x.com/official/status/1900000000000000001?s=20",
            "Corrected report",
            "1900000000000000000",
            observedAt,
            List.of(new SourceRelationshipInput("1899999999999999999", "quoted"))));

    assertThat(post.postId).isEqualTo("1900000000000000001");
    assertThat(post.canonicalUrl).isEqualTo("https://x.com/official/status/1900000000000000001");
    assertThat(post.permittedText).isEqualTo("Corrected report");
    assertThat(post.status).isEqualTo("excluded_compliance_edit");
    assertThat(post.reconciliationState).isEqualTo("reconciled");
    assertThat(post.lastCheckedAt).isEqualTo(observedAt);
    verify(replaySafety)
        .suppressAggregate(post.id, "source content edited", SystemActors.AUTOMATION);
    verify(complianceActions).save(any(SourceComplianceAction.class));
  }

  @Test
  void providerDeletionIsTombstonedAndMarkedReconciled() {
    var post =
        new SourcePost(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "1900000000000000000",
            "123456",
            "official",
            "https://x.com/official/status/1900000000000000000",
            "Original report",
            Instant.now().minusSeconds(60));
    when(posts.findLockedById(post.id)).thenReturn(java.util.Optional.of(post));

    service.applyProviderDeletion(post.id, "resource not found", Instant.now());

    assertThat(post.status).isEqualTo("deleted");
    assertThat(post.reconciliationState).isEqualTo("reconciled");
    assertThat(post.permittedText).isEqualTo("[deleted by source]");
    verify(tombstones).save(any(SourceTombstone.class));
  }

  @Test
  void publishabilityRequiresEveryRequestedSourceToRemainActive() {
    var sourceIds = Set.of(UUID.randomUUID(), UUID.randomUUID());
    when(posts.countByIdInAndStatus(sourceIds, "active")).thenReturn(1L);

    assertThatThrownBy(() -> service.assertSourcesPublishable(sourceIds))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("restricted or missing");
  }
}
