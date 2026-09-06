package com.nsangusa.news.sourceingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.XPostDiscovered;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}
