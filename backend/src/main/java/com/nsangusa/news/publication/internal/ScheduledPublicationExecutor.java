package com.nsangusa.news.publication.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.audit.AuditService;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ScheduledPublicationExecutor {
  private static final Logger log = LoggerFactory.getLogger(ScheduledPublicationExecutor.class);
  private final ScheduledPublicationRepository schedules;
  private final ArticleService articles;
  private final AuditService audit;
  private final TransactionTemplate transactions;

  ScheduledPublicationExecutor(
      ScheduledPublicationRepository schedules,
      ArticleService articles,
      AuditService audit,
      PlatformTransactionManager transactionManager) {
    this.schedules = schedules;
    this.articles = articles;
    this.audit = audit;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  void execute(UUID id, Instant cutoff) {
    var attemptedVersion = new AtomicReference<Long>();
    try {
      transactions.executeWithoutResult(
          ignored -> {
            var claimed = schedules.claimDue(id, cutoff);
            if (claimed.isEmpty()) {
              return;
            }
            var schedule = claimed.get();
            attemptedVersion.set(schedule.version);
            schedule.requireScheduledArticle(articles.getLocked(schedule.articleId));
            articles.publish(
                schedule.articleId, schedule.scheduledBy, schedule.articleId, schedule.id);
            schedule.status = "published";
            schedule.attemptCount++;
            schedule.lastAttemptAt = Instant.now();
            schedule.updatedAt = schedule.lastAttemptAt;
            schedule.completedAt = schedule.lastAttemptAt;
            schedule.lastError = null;
            schedules.flush();
            audit.record(
                schedule.scheduledBy,
                "PUBLICATION_SCHEDULE_PUBLISHED",
                "publication_schedule",
                schedule.id,
                PublicationApplicationService.metadata(schedule));
          });
    } catch (RuntimeException failure) {
      log.warn("Scheduled publication {} failed ({})", id, failure.getClass().getSimpleName());
      if (attemptedVersion.get() != null) {
        // Persist only after the publication transaction rolled back, without overwriting an
        // editor's change.
        transactions.executeWithoutResult(
            ignored ->
                schedules
                    .lockById(id)
                    .ifPresent(
                        schedule -> {
                          if (!"scheduled".equals(schedule.status)
                              || schedule.version != attemptedVersion.get()) {
                            return;
                          }
                          schedule.status = "failed";
                          schedule.attemptCount++;
                          schedule.lastAttemptAt = Instant.now();
                          schedule.updatedAt = schedule.lastAttemptAt;
                          schedule.lastError = failureMessage(failure);
                          schedules.flush();
                          var metadata =
                              new java.util.HashMap<>(
                                  PublicationApplicationService.metadata(schedule));
                          metadata.put("error", schedule.lastError);
                          audit.record(
                              schedule.scheduledBy,
                              "PUBLICATION_SCHEDULE_FAILED",
                              "publication_schedule",
                              schedule.id,
                              metadata);
                        }));
      }
    }
  }

  private static String failureMessage(RuntimeException failure) {
    String message =
        failure instanceof IllegalStateException || failure instanceof IllegalArgumentException
            ? failure.getMessage()
            : null;
    if (message == null || message.isBlank()) {
      return "Publication failed; review the article and its sources before retrying";
    }
    return message.substring(0, Math.min(message.length(), 1000));
  }
}
