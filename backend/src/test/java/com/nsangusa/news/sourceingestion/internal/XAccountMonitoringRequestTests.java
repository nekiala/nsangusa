package com.nsangusa.news.sourceingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import com.nsangusa.news.integration.EventEnvelope;
import com.nsangusa.news.integration.NewsEvents.XAccountMonitoringRequested;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

class XAccountMonitoringRequestTests {
  private final MonitoredXAccountRepository accounts = mock(MonitoredXAccountRepository.class);
  private final DurableEventPublisher events = mock(DurableEventPublisher.class);
  private final SourceIngestionApplicationService service =
      new SourceIngestionApplicationService(
          accounts,
          mock(SourcePostRepository.class),
          mock(BlockedSourceAccountRepository.class),
          mock(SourceTombstoneRepository.class),
          mock(SourceComplianceActionRepository.class),
          mock(SourceRelationshipRepository.class),
          events,
          mock(ReplaySafetyRegistry.class));

  @Test
  void addingAnAccountRequestsItsFirstSynchronization() {
    UUID id = service.addAccount("123456", "@Official", "Official", Set.of("politics"), 0.5);

    var request = ArgumentCaptor.forClass(XAccountMonitoringRequested.class);
    verify(events)
        .enqueue(
            eq("XAccountMonitoringRequested"),
            eq(id),
            eq(id),
            isNull(),
            startsWith("x-account-monitoring-requested:" + id + ":"),
            request.capture());
    assertThat(request.getValue().monitoredAccountId()).isEqualTo(id);
    assertThat(request.getValue().accountId()).isEqualTo("123456");
    assertThat(request.getValue().handle()).isEqualTo("Official");
    assertThat(request.getValue().reason()).isEqualTo("added");
  }

  @Test
  void onlyResumingPausedMonitoringRequestsASynchronization() {
    var account = account();
    when(accounts.findById(account.id)).thenReturn(Optional.of(account));

    service.setMonitoring(account.id, true);
    service.setMonitoring(account.id, false);
    verifyNoInteractions(events);

    service.setMonitoring(account.id, true);
    var request = ArgumentCaptor.forClass(XAccountMonitoringRequested.class);
    verify(events)
        .enqueue(
            eq("XAccountMonitoringRequested"),
            eq(account.id),
            eq(account.id),
            isNull(),
            any(),
            request.capture());
    assertThat(request.getValue().reason()).isEqualTo("resumed");
  }

  @Test
  void consumerSynchronizesOnlyAccountsThatAreStillMonitored() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var monitoring = mock(XMonitoringScheduler.class);
    var consumer =
        new XAccountMonitoringConsumer(
            reader, processed, accounts, monitoring, mock(PlatformTransactionManager.class));
    var active = account();
    var paused = account();
    paused.monitoringEnabled = false;
    when(accounts.findById(active.id)).thenReturn(Optional.of(active));
    when(accounts.findById(paused.id)).thenReturn(Optional.of(paused));
    var activeRequest = request(active);
    var pausedRequest = request(paused);
    when(reader.eventType(any())).thenReturn("XAccountMonitoringRequested");
    when(reader.read("active", XAccountMonitoringRequested.class)).thenReturn(activeRequest);
    when(reader.read("paused", XAccountMonitoringRequested.class)).thenReturn(pausedRequest);

    consumer.consume("active");
    consumer.consume("paused");

    verify(monitoring).synchronizeAccount(active);
    verify(monitoring, never()).synchronizeAccount(paused);
    verify(processed).markProcessed(activeRequest.eventId(), "x-account-monitoring-v1");
    verify(processed).markProcessed(pausedRequest.eventId(), "x-account-monitoring-v1");
  }

  @Test
  void consumerIgnoresOtherIngestionEventsAndProcessedRequests() {
    var reader = mock(IncomingEventReader.class);
    var processed = mock(ProcessedEventRegistry.class);
    var monitoring = mock(XMonitoringScheduler.class);
    var consumer =
        new XAccountMonitoringConsumer(
            reader, processed, accounts, monitoring, mock(PlatformTransactionManager.class));
    when(reader.eventType("discovered")).thenReturn("XPostDiscovered");
    consumer.consume("discovered");

    var account = account();
    var request = request(account);
    when(reader.eventType("repeat")).thenReturn("XAccountMonitoringRequested");
    when(reader.read("repeat", XAccountMonitoringRequested.class)).thenReturn(request);
    when(processed.wasProcessed(request.eventId(), "x-account-monitoring-v1")).thenReturn(true);
    consumer.consume("repeat");

    verifyNoInteractions(monitoring);
    verify(processed, never()).markProcessed(any(), any());
  }

  private static MonitoredXAccount account() {
    return new MonitoredXAccount(
        UUID.randomUUID(), "123456", "official", "Official", "politics", 0.5);
  }

  private static EventEnvelope<XAccountMonitoringRequested> request(MonitoredXAccount account) {
    return new EventEnvelope<>(
        UUID.randomUUID(),
        "XAccountMonitoringRequested",
        1,
        account.id,
        account.id,
        null,
        Instant.now(),
        "test",
        Map.of(),
        "x-account-monitoring-requested:" + account.id + ":test",
        new XAccountMonitoringRequested(
            account.id, account.accountId, account.handle, "added", Instant.now()));
  }
}
