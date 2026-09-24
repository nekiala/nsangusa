package com.nsangusa.news.comments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "comment_settings")
class CommentGlobalSettings {
  @Id Integer id;

  @Column(nullable = false)
  boolean enabled;

  @Column(nullable = false)
  boolean requireApproval;

  @Column(nullable = false)
  int editingWindowMinutes;

  @Column(nullable = false)
  double reviewSpamThreshold;

  @Column(nullable = false)
  double rejectSpamThreshold;

  @Column(nullable = false)
  int reportEscalationThreshold;

  @Column(nullable = false)
  Instant updatedAt;

  UUID updatedBy;
  @Version long version;

  protected CommentGlobalSettings() {}

  CommentGlobalSettings(
      boolean enabled,
      boolean requireApproval,
      int editingWindowMinutes,
      double reviewSpamThreshold,
      double rejectSpamThreshold,
      int reportEscalationThreshold) {
    this.id = 1;
    update(
        enabled,
        requireApproval,
        editingWindowMinutes,
        reviewSpamThreshold,
        rejectSpamThreshold,
        reportEscalationThreshold,
        null);
  }

  void update(
      boolean enabled,
      boolean requireApproval,
      int editingWindowMinutes,
      double reviewSpamThreshold,
      double rejectSpamThreshold,
      int reportEscalationThreshold,
      UUID updatedBy) {
    validate(
        editingWindowMinutes, reviewSpamThreshold, rejectSpamThreshold, reportEscalationThreshold);
    this.enabled = enabled;
    this.requireApproval = requireApproval;
    this.editingWindowMinutes = editingWindowMinutes;
    this.reviewSpamThreshold = reviewSpamThreshold;
    this.rejectSpamThreshold = rejectSpamThreshold;
    this.reportEscalationThreshold = reportEscalationThreshold;
    this.updatedBy = updatedBy;
    this.updatedAt = Instant.now();
  }

  private static void validate(
      int editingWindowMinutes,
      double reviewSpamThreshold,
      double rejectSpamThreshold,
      int reportEscalationThreshold) {
    if (editingWindowMinutes < 0 || editingWindowMinutes > 10_080) {
      throw new IllegalArgumentException("Editing window must be between 0 and 10080 minutes");
    }
    if (!Double.isFinite(reviewSpamThreshold)
        || !Double.isFinite(rejectSpamThreshold)
        || reviewSpamThreshold < 0
        || reviewSpamThreshold > 1
        || rejectSpamThreshold < 0
        || rejectSpamThreshold > 1
        || reviewSpamThreshold > rejectSpamThreshold) {
      throw new IllegalArgumentException("Invalid spam thresholds");
    }
    if (reportEscalationThreshold < 1 || reportEscalationThreshold > 100) {
      throw new IllegalArgumentException("Invalid report escalation threshold");
    }
  }
}
