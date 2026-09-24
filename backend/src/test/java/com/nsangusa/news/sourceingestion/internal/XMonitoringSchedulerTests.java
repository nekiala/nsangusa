package com.nsangusa.news.sourceingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class XMonitoringSchedulerTests {
  @Test
  void editedPostChainUpdatesExistingSourceInsteadOfCreatingADuplicate() {
    var accounts = mock(MonitoredXAccountRepository.class);
    var provider = mock(XSourceProvider.class);
    var ingestion = mock(SourceIngestionService.class);
    var posts = mock(SourcePostRepository.class);
    var account =
        new MonitoredXAccount(UUID.randomUUID(), "123456", "publisher", "Publisher", "world", 0.5);
    var existing =
        new SourcePost(
            UUID.randomUUID(),
            account.id,
            "1900000000000000000",
            account.accountId,
            account.handle,
            "https://x.com/publisher/status/1900000000000000000",
            "Original report",
            Instant.now().minusSeconds(120));
    var edited =
        new XSourceProvider.Post(
            "1900000000000000001",
            "Corrected report",
            Instant.now().minusSeconds(60),
            "1900000000000000000",
            List.of("1900000000000000000", "1900000000000000001"),
            List.of());
    when(accounts.findByMonitoringEnabledTrueAndRemovedAtIsNull()).thenReturn(List.of(account));
    when(provider.fetchRecent(account.accountId, account.lastPostId))
        .thenReturn(new XSourceProvider.FetchResult(List.of(edited), Instant.now(), 300, 299));
    when(posts.findByEditChainId("1900000000000000000"))
        .thenReturn(java.util.Optional.of(existing));
    when(posts.findReconciliationCandidates(account.id, 100)).thenReturn(List.of());

    new XMonitoringScheduler(accounts, provider, ingestion, posts, 100).synchronize();

    verify(ingestion).reconcileProviderPost(eq(existing.id), any());
    verify(ingestion, never()).discoverPost(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void legacyEditVersionMatchesAnyOfficialHistoryId() {
    var accounts = mock(MonitoredXAccountRepository.class);
    var provider = mock(XSourceProvider.class);
    var ingestion = mock(SourceIngestionService.class);
    var posts = mock(SourcePostRepository.class);
    var account =
        new MonitoredXAccount(UUID.randomUUID(), "123456", "publisher", "Publisher", "world", 0.5);
    var legacy =
        new SourcePost(
            UUID.randomUUID(),
            account.id,
            "1900000000000000001",
            account.accountId,
            account.handle,
            "https://x.com/publisher/status/1900000000000000001",
            "First corrected report",
            Instant.now().minusSeconds(120));
    var latest =
        new XSourceProvider.Post(
            "1900000000000000002",
            "Latest corrected report",
            Instant.now().minusSeconds(60),
            "1900000000000000000",
            List.of("1900000000000000000", "1900000000000000001", "1900000000000000002"),
            List.of());
    when(accounts.findByMonitoringEnabledTrueAndRemovedAtIsNull()).thenReturn(List.of(account));
    when(accounts.findAllByOrderByHandleAsc()).thenReturn(List.of(account));
    when(provider.fetchRecent(account.accountId, account.lastPostId))
        .thenReturn(new XSourceProvider.FetchResult(List.of(latest), Instant.now(), 300, 299));
    when(posts.findByEditChainId("1900000000000000000")).thenReturn(Optional.empty());
    when(posts.findByPostIdIn(latest.editHistoryPostIds())).thenReturn(List.of(legacy));
    when(posts.findReconciliationCandidates(account.id, 100)).thenReturn(List.of());

    new XMonitoringScheduler(accounts, provider, ingestion, posts, 100).synchronize();

    var snapshot = ArgumentCaptor.forClass(SourceIngestionService.ProviderPostSnapshot.class);
    verify(ingestion).reconcileProviderPost(eq(legacy.id), snapshot.capture());
    assertThat(snapshot.getValue().postId()).isEqualTo("1900000000000000002");
    assertThat(snapshot.getValue().editChainId()).isEqualTo("1900000000000000000");
    verify(ingestion, never()).discoverPost(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void duplicateLegacyEditRowsAreSuppressedAroundTheNewestStoredVersion() {
    var accounts = mock(MonitoredXAccountRepository.class);
    var provider = mock(XSourceProvider.class);
    var ingestion = mock(SourceIngestionService.class);
    var posts = mock(SourcePostRepository.class);
    var account =
        new MonitoredXAccount(UUID.randomUUID(), "123456", "publisher", "Publisher", "world", 0.5);
    var first =
        new SourcePost(
            UUID.randomUUID(),
            account.id,
            "1900000000000000000",
            account.accountId,
            account.handle,
            "https://x.com/publisher/status/1900000000000000000",
            "Original report",
            Instant.now().minusSeconds(180));
    var second =
        new SourcePost(
            UUID.randomUUID(),
            account.id,
            "1900000000000000001",
            account.accountId,
            account.handle,
            "https://x.com/publisher/status/1900000000000000001",
            "First correction",
            Instant.now().minusSeconds(120));
    var latest =
        new XSourceProvider.Post(
            "1900000000000000002",
            "Latest correction",
            Instant.now().minusSeconds(60),
            "1900000000000000000",
            List.of("1900000000000000000", "1900000000000000001", "1900000000000000002"),
            List.of());
    when(accounts.findByMonitoringEnabledTrueAndRemovedAtIsNull()).thenReturn(List.of(account));
    when(accounts.findAllByOrderByHandleAsc()).thenReturn(List.of(account));
    when(provider.fetchRecent(account.accountId, account.lastPostId))
        .thenReturn(new XSourceProvider.FetchResult(List.of(latest), Instant.now(), 300, 299));
    when(posts.findByEditChainId("1900000000000000000")).thenReturn(Optional.of(first));
    when(posts.findByPostIdIn(latest.editHistoryPostIds())).thenReturn(List.of(first, second));
    when(posts.findReconciliationCandidates(account.id, 100)).thenReturn(List.of());

    new XMonitoringScheduler(accounts, provider, ingestion, posts, 100).synchronize();

    verify(ingestion)
        .applyComplianceEdit(
            eq(first.id),
            eq(first.permittedText),
            eq("Duplicate legacy row from an official X edit chain"),
            eq(com.nsangusa.news.integration.SystemActors.AUTOMATION));
    verify(ingestion)
        .reconcileCompliance(
            eq(first.id),
            eq(true),
            eq("Canonicalized into the newest retained X edit-chain row"),
            eq(com.nsangusa.news.integration.SystemActors.AUTOMATION));
    verify(ingestion).reconcileProviderPost(eq(second.id), any());
  }

  @Test
  void explicitNotFoundLookupDeletesAnAlreadyExcludedEditedPost() {
    var accounts = mock(MonitoredXAccountRepository.class);
    var provider = mock(XSourceProvider.class);
    var ingestion = mock(SourceIngestionService.class);
    var posts = mock(SourcePostRepository.class);
    var account =
        new MonitoredXAccount(UUID.randomUUID(), "123456", "publisher", "Publisher", "world", 0.5);
    var existing =
        new SourcePost(
            UUID.randomUUID(),
            account.id,
            "1900000000000000000",
            account.accountId,
            account.handle,
            "https://x.com/publisher/status/1900000000000000000",
            "Original report",
            Instant.now().minusSeconds(120));
    existing.status = "excluded_compliance_edit";
    when(accounts.findByMonitoringEnabledTrueAndRemovedAtIsNull()).thenReturn(List.of(account));
    when(provider.fetchRecent(account.accountId, account.lastPostId))
        .thenReturn(new XSourceProvider.FetchResult(List.of(), Instant.now(), 300, 299));
    when(posts.findReconciliationCandidates(account.id, 100)).thenReturn(List.of(existing));
    when(provider.lookupPosts(List.of(existing.postId)))
        .thenReturn(
            new XSourceProvider.LookupResult(
                List.of(
                    new XSourceProvider.LookupPost(
                        existing.postId,
                        XSourceProvider.LookupState.DELETED,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        "resource not found"))));

    new XMonitoringScheduler(accounts, provider, ingestion, posts, 100).synchronize();

    verify(ingestion)
        .applyProviderDeletion(eq(existing.id), eq("resource not found"), any(Instant.class));
  }

  @Test
  void disabledAccountStillReconcilesRetainedPostsForDeletion() {
    var accounts = mock(MonitoredXAccountRepository.class);
    var provider = mock(XSourceProvider.class);
    var ingestion = mock(SourceIngestionService.class);
    var posts = mock(SourcePostRepository.class);
    var account =
        new MonitoredXAccount(UUID.randomUUID(), "123456", "publisher", "Publisher", "world", 0.5);
    account.monitoringEnabled = false;
    var retained =
        new SourcePost(
            UUID.randomUUID(),
            account.id,
            "1900000000000000000",
            account.accountId,
            account.handle,
            "https://x.com/publisher/status/1900000000000000000",
            "Previously retained report",
            Instant.now().minusSeconds(120));
    retained.status = "excluded_blocked";
    when(accounts.findByMonitoringEnabledTrueAndRemovedAtIsNull()).thenReturn(List.of());
    when(accounts.findAllByOrderByHandleAsc()).thenReturn(List.of(account));
    when(posts.findReconciliationCandidates(account.id, 100)).thenReturn(List.of(retained));
    when(provider.lookupPosts(List.of(retained.postId)))
        .thenReturn(
            new XSourceProvider.LookupResult(
                List.of(
                    new XSourceProvider.LookupPost(
                        retained.postId,
                        XSourceProvider.LookupState.DELETED,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        "resource not found"))));

    new XMonitoringScheduler(accounts, provider, ingestion, posts, 100).synchronize();

    verify(provider, never()).fetchRecent(any(), any());
    verify(ingestion)
        .applyProviderDeletion(eq(retained.id), eq("resource not found"), any(Instant.class));
  }

  @Test
  void disabledAccountLooksUpNewestEditVersion() {
    var accounts = mock(MonitoredXAccountRepository.class);
    var provider = mock(XSourceProvider.class);
    var ingestion = mock(SourceIngestionService.class);
    var posts = mock(SourcePostRepository.class);
    var account =
        new MonitoredXAccount(UUID.randomUUID(), "123456", "publisher", "Publisher", "world", 0.5);
    account.monitoringEnabled = false;
    var retained =
        new SourcePost(
            UUID.randomUUID(),
            account.id,
            "1900000000000000001",
            account.accountId,
            account.handle,
            "https://x.com/publisher/status/1900000000000000001",
            "First corrected report",
            Instant.now().minusSeconds(120));
    var history = List.of("1900000000000000000", "1900000000000000001", "1900000000000000002");
    when(accounts.findByMonitoringEnabledTrueAndRemovedAtIsNull()).thenReturn(List.of());
    when(accounts.findAllByOrderByHandleAsc()).thenReturn(List.of(account));
    when(posts.findReconciliationCandidates(account.id, 100)).thenReturn(List.of(retained));
    when(provider.lookupPosts(List.of(retained.postId)))
        .thenReturn(
            new XSourceProvider.LookupResult(
                List.of(
                    new XSourceProvider.LookupPost(
                        retained.postId,
                        XSourceProvider.LookupState.AVAILABLE,
                        retained.permittedText,
                        retained.publishedAt,
                        null,
                        history,
                        List.of(),
                        null))));
    when(provider.lookupPosts(List.of("1900000000000000002")))
        .thenReturn(
            new XSourceProvider.LookupResult(
                List.of(
                    new XSourceProvider.LookupPost(
                        "1900000000000000002",
                        XSourceProvider.LookupState.AVAILABLE,
                        "Latest corrected report",
                        Instant.now(),
                        null,
                        history,
                        List.of(),
                        null))));
    when(posts.findByEditChainId("1900000000000000000")).thenReturn(Optional.empty());
    when(posts.findByPostIdIn(history)).thenReturn(List.of(retained));

    new XMonitoringScheduler(accounts, provider, ingestion, posts, 100).synchronize();

    var snapshot = ArgumentCaptor.forClass(SourceIngestionService.ProviderPostSnapshot.class);
    verify(ingestion).reconcileProviderPost(eq(retained.id), snapshot.capture());
    assertThat(snapshot.getValue().postId()).isEqualTo("1900000000000000002");
    assertThat(snapshot.getValue().permittedText()).isEqualTo("Latest corrected report");
  }
}
