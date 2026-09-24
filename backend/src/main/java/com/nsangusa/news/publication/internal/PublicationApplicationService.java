package com.nsangusa.news.publication.internal;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.publication.PublicationService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class PublicationApplicationService implements PublicationService {
  private final ScheduledPublicationRepository schedules;
  private final ArticleService articles;
  private final AuditService audit;
  private final PublicationPolicyEvaluator policy;

  PublicationApplicationService(
      ScheduledPublicationRepository schedules,
      ArticleService articles,
      AuditService audit,
      PublicationPolicyEvaluator policy) {
    this.schedules = schedules;
    this.articles = articles;
    this.audit = audit;
    this.policy = policy;
  }

  @Override
  @Transactional
  public UUID schedule(UUID articleId, Instant publishAt, UUID editorId) {
    requireFuture(publishAt);
    requireActor(editorId);
    if (articleId == null) {
      throw new IllegalArgumentException("An article is required");
    }
    if (schedules.existsByArticleIdAndStatusIn(articleId, List.of("scheduled", "failed"))) {
      throw new IllegalStateException("The article already has an active publication schedule");
    }
    articles.markScheduled(articleId, editorId);
    var schedule =
        new ScheduledPublication(articleId, publishAt, editorId, articles.get(articleId).version());
    try {
      schedules.saveAndFlush(schedule);
    } catch (DataIntegrityViolationException exception) {
      throw new IllegalStateException(
          "The article already has an active publication schedule", exception);
    }
    audit.record(
        editorId,
        "PUBLICATION_SCHEDULE_CREATED",
        "publication_schedule",
        schedule.id,
        metadata(schedule));
    return schedule.id;
  }

  @Override
  @Transactional(readOnly = true)
  public ScheduleView get(UUID scheduleId) {
    if (scheduleId == null) {
      throw new IllegalArgumentException("A publication schedule is required");
    }
    return schedules
        .findById(scheduleId)
        .orElseThrow(
            () ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Publication schedule not found"))
        .view();
  }

  @Override
  @Transactional(readOnly = true)
  public SchedulePage list(String status, UUID articleId, int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Page must be nonnegative and size between 1 and 100");
    }
    String filter =
        status == null || status.isBlank() ? null : status.trim().toLowerCase(Locale.ROOT);
    if (filter != null
        && !Set.of("scheduled", "published", "cancelled", "failed").contains(filter)) {
      throw new IllegalArgumentException("Unsupported publication schedule status");
    }
    var result =
        schedules.inventory(
            filter,
            articleId,
            PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("scheduledFor"), Sort.Order.asc("id"))));
    return new SchedulePage(
        result.stream().map(ScheduledPublication::view).toList(),
        page,
        size,
        result.getTotalElements());
  }

  @Override
  @Transactional
  public ScheduleView reschedule(
      UUID scheduleId, long expectedVersion, Instant publishAt, UUID editorId) {
    requireFuture(publishAt);
    requireActor(editorId);
    var schedule = findLocked(scheduleId);
    schedule.requireVersion(expectedVersion);
    schedule.requireScheduledArticle(articles.getLocked(schedule.articleId));
    Instant previous = schedule.scheduledFor;
    schedule.scheduledFor = publishAt;
    schedule.status = "scheduled";
    schedule.updatedBy = editorId;
    schedule.updatedAt = Instant.now();
    schedule.completedAt = null;
    schedules.flush();
    var metadata = new java.util.HashMap<>(metadata(schedule));
    metadata.put("previousPublishAt", previous.toString());
    audit.record(
        editorId, "PUBLICATION_SCHEDULE_CHANGED", "publication_schedule", schedule.id, metadata);
    return schedule.view();
  }

  @Override
  @Transactional
  public ScheduleView cancel(UUID scheduleId, long expectedVersion, UUID editorId) {
    requireActor(editorId);
    var schedule = findLocked(scheduleId);
    schedule.requireVersion(expectedVersion);
    if (articles.getLocked(schedule.articleId).state() == ArticleState.SCHEDULED) {
      articles.cancelSchedule(schedule.articleId, editorId);
    }
    schedule.status = "cancelled";
    schedule.updatedBy = editorId;
    schedule.updatedAt = Instant.now();
    schedule.completedAt = schedule.updatedAt;
    schedules.flush();
    audit.record(
        editorId,
        "PUBLICATION_SCHEDULE_CANCELLED",
        "publication_schedule",
        schedule.id,
        metadata(schedule));
    return schedule.view();
  }

  @Override
  public PolicyView policy() {
    return policy.view();
  }

  private ScheduledPublication findLocked(UUID id) {
    if (id == null) {
      throw new IllegalArgumentException("A publication schedule is required");
    }
    return schedules
        .lockById(id)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Publication schedule not found"));
  }

  static Map<String, String> metadata(ScheduledPublication schedule) {
    return Map.of(
        "articleId", schedule.articleId.toString(),
        "articleVersion", String.valueOf(schedule.articleVersion),
        "publishAt", schedule.scheduledFor.toString(),
        "scheduledBy", schedule.scheduledBy.toString(),
        "version", Long.toString(schedule.version));
  }

  private static void requireFuture(Instant publishAt) {
    if (publishAt == null || !publishAt.isAfter(Instant.now())) {
      throw new IllegalArgumentException("Scheduled publication must be in the future");
    }
  }

  private static void requireActor(UUID editorId) {
    if (editorId == null) {
      throw new IllegalArgumentException("A responsible editor is required");
    }
  }
}
