package com.nsangusa.news.sourceingestion.internal;

import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.eventprocessing.ProcessedEventRegistry;
import com.nsangusa.news.integration.EventTopics;
import com.nsangusa.news.integration.NewsEvents.XAccountMonitoringRequested;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the first synchronization for an account an administrator added or resumed, instead of
 * waiting for the next scheduled poll. Not transactional: like the scheduler, each recorded post
 * commits on its own, and a repeated request only repeats an idempotent sync.
 */
@Component
class XAccountMonitoringConsumer {
  private static final String CONSUMER = "x-account-monitoring-v1";

  private final IncomingEventReader reader;
  private final ProcessedEventRegistry processed;
  private final MonitoredXAccountRepository accounts;
  private final XMonitoringScheduler monitoring;
  private final TransactionTemplate transactions;

  XAccountMonitoringConsumer(
      IncomingEventReader reader,
      ProcessedEventRegistry processed,
      MonitoredXAccountRepository accounts,
      XMonitoringScheduler monitoring,
      PlatformTransactionManager transactionManager) {
    this.reader = reader;
    this.processed = processed;
    this.accounts = accounts;
    this.monitoring = monitoring;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @KafkaListener(topics = EventTopics.INGESTION, groupId = CONSUMER)
  @KafkaListener(
      topics = EventTopics.INGESTION_RETRY,
      groupId = CONSUMER + EventTopics.RETRY_GROUP_SUFFIX)
  void consume(String json) {
    if (!"XAccountMonitoringRequested".equals(reader.eventType(json))) {
      return;
    }
    var event = reader.read(json, XAccountMonitoringRequested.class);
    if (processed.wasProcessed(event.eventId(), CONSUMER)) {
      return;
    }
    // Monitoring may have been paused, removed or blocked since the request was made.
    accounts
        .findById(event.payload().monitoredAccountId())
        .filter(account -> account.monitoringEnabled && account.removedAt == null)
        .ifPresent(monitoring::synchronizeAccount);
    transactions.executeWithoutResult(status -> processed.markProcessed(event.eventId(), CONSUMER));
  }
}
