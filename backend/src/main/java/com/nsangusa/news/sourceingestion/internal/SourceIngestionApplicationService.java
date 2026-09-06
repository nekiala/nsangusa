package com.nsangusa.news.sourceingestion.internal;

import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.eventprocessing.ReplaySafetyRegistry;
import com.nsangusa.news.integration.NewsEvents.XPostDiscovered;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SourceIngestionApplicationService implements SourceIngestionService {
  private final MonitoredXAccountRepository accounts;
  private final SourcePostRepository posts;
  private final BlockedSourceAccountRepository blockedAccounts;
  private final SourceTombstoneRepository tombstones;
  private final SourceComplianceActionRepository complianceActions;
  private final SourceRelationshipRepository relationships;
  private final DurableEventPublisher events;
  private final ReplaySafetyRegistry replaySafety;

  SourceIngestionApplicationService(
      MonitoredXAccountRepository accounts,
      SourcePostRepository posts,
      BlockedSourceAccountRepository blockedAccounts,
      SourceTombstoneRepository tombstones,
      SourceComplianceActionRepository complianceActions,
      SourceRelationshipRepository relationships,
      DurableEventPublisher events,
      ReplaySafetyRegistry replaySafety) {
    this.accounts = accounts;
    this.posts = posts;
    this.blockedAccounts = blockedAccounts;
    this.tombstones = tombstones;
    this.complianceActions = complianceActions;
    this.relationships = relationships;
    this.events = events;
    this.replaySafety = replaySafety;
  }

  @Override
  @Transactional
  public UUID addAccount(
      String accountId,
      String handle,
      String displayName,
      Set<String> topics,
      double relevanceThreshold) {
    if (!accountId.matches("\\d{1,30}")) {
      throw new IllegalArgumentException("X accountId must be the official numeric account ID");
    }
    String normalizedHandle = normalizeHandle(handle);
    if (!normalizedHandle.matches("[A-Za-z0-9_]{1,15}")) {
      throw new IllegalArgumentException("X handle has an invalid format");
    }
    if (accounts.findByHandleIgnoreCase(normalizedHandle).isPresent()
        || accounts.findByAccountId(accountId).isPresent()) {
      throw new IllegalArgumentException("Handle is already monitored");
    }
    requireNotBlocked(accountId);
    UUID id = UUID.randomUUID();
    accounts.save(
        new MonitoredXAccount(
            id, accountId, normalizedHandle, displayName, joinTopics(topics), relevanceThreshold));
    return id;
  }

  @Override
  @Transactional
  public void setMonitoring(UUID accountId, boolean enabled) {
    var account =
        accounts
            .findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown account"));
    if (account.removedAt != null && enabled) {
      throw new IllegalStateException("Removed accounts cannot be monitored");
    }
    if (enabled) {
      requireNotBlocked(account.accountId);
    }
    account.monitoringEnabled = enabled;
    account.updatedAt = Instant.now();
  }

  @Override
  @Transactional(readOnly = true)
  public List<AccountView> listAccounts(boolean includeRemoved) {
    var result =
        includeRemoved
            ? accounts.findAllByOrderByHandleAsc()
            : accounts.findByRemovedAtIsNullOrderByHandleAsc();
    return result.stream().map(SourceIngestionApplicationService::toView).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public AccountView getAccount(UUID accountId) {
    return toView(requireAccount(accountId));
  }

  @Override
  @Transactional
  public void updateAccount(
      UUID accountId,
      String displayName,
      Set<String> topics,
      double relevanceThreshold,
      boolean monitoringEnabled,
      UUID actorId) {
    var account = requireAccount(accountId);
    if (account.removedAt != null) {
      throw new IllegalStateException("Removed accounts cannot be edited");
    }
    if (monitoringEnabled) {
      requireNotBlocked(account.accountId);
    }
    account.displayName = displayName.trim();
    account.topics = joinTopics(topics);
    account.relevanceThreshold = relevanceThreshold;
    account.monitoringEnabled = monitoringEnabled;
    account.updatedAt = Instant.now();
    audit(
        "account_updated",
        "monitored_x_account",
        account.accountId,
        null,
        actorId,
        "administrative update",
        "{\"monitoringEnabled\":" + monitoringEnabled + "}");
  }

  @Override
  @Transactional
  public void removeAccount(UUID accountId, String reason, UUID actorId) {
    var account = requireAccount(accountId);
    account.monitoringEnabled = false;
    account.removedAt = Instant.now();
    account.updatedAt = account.removedAt;
    audit("account_removed", "monitored_x_account", account.accountId, null, actorId, reason, "{}");
  }

  @Override
  @Transactional(readOnly = true)
  public List<BlockedAccountView> listBlockedAccounts() {
    return blockedAccounts.findAllByOrderByCreatedAtDesc().stream()
        .map(
            blocked ->
                new BlockedAccountView(
                    blocked.accountId, blocked.reason, blocked.createdAt, blocked.actorId))
        .toList();
  }

  @Override
  @Transactional
  public void blockAccount(String accountId, String reason, UUID actorId) {
    String normalizedAccountId = accountId.trim();
    if (!normalizedAccountId.matches("\\d{1,30}")) {
      throw new IllegalArgumentException("X accountId must be the official numeric account ID");
    }
    blockedAccounts.save(new BlockedSourceAccount(normalizedAccountId, reason.trim(), actorId));
    accounts
        .findByAccountId(normalizedAccountId)
        .ifPresent(
            account -> {
              account.monitoringEnabled = false;
              account.updatedAt = Instant.now();
            });
    for (var post : posts.findByAccountIdAndStatusNot(normalizedAccountId, "deleted")) {
      exclude(post, "blocked_account:" + reason, actorId, "excluded_blocked");
    }
    audit("account_blocked", "x_account", normalizedAccountId, null, actorId, reason, "{}");
  }

  @Override
  @Transactional
  public void unblockAccount(String accountId, UUID actorId) {
    if (!blockedAccounts.existsById(accountId)) {
      throw new IllegalArgumentException("Source account is not blocked");
    }
    blockedAccounts.deleteById(accountId);
    audit(
        "account_unblocked", "x_account", accountId, null, actorId, "administrative unblock", "{}");
  }

  @Override
  @Transactional(readOnly = true)
  public SourceView getSource(UUID sourcePostId) {
    var post =
        posts
            .findById(sourcePostId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown source post"));
    var associations =
        posts.findAssociations(sourcePostId).stream()
            .map(
                item ->
                    new SourceAssociation(
                        item.getAssociationType(), item.getAssociatedId(), item.getState()))
            .toList();
    var relationshipViews =
        relationships.findBySourcePostIdOrderByRelationshipTypeAsc(sourcePostId).stream()
            .map(item -> new SourceRelationship(item.id, item.relatedPostId, item.relationshipType))
            .toList();
    return new SourceView(
        post.id,
        post.monitoredAccountId,
        post.postId,
        post.accountId,
        post.handle,
        post.canonicalUrl,
        post.permittedText,
        post.publishedAt,
        post.ingestedAt,
        post.status,
        post.complianceAction,
        post.reconciliationState,
        post.complianceRequestedAt,
        post.complianceReconciledAt,
        post.complianceReason,
        post.complianceError,
        post.excludedAt,
        post.contentDeletedAt,
        associations,
        relationshipViews,
        post.version);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SourceSummary> listSources(String accountId, String status, int limit) {
    var page = PageRequest.of(0, Math.max(1, Math.min(limit, 100)));
    List<SourcePost> result;
    if (accountId != null && !accountId.isBlank() && status != null && !status.isBlank()) {
      result =
          posts.findByAccountIdAndStatusOrderByPublishedAtDesc(
              accountId.trim(), status.trim(), page);
    } else if (accountId != null && !accountId.isBlank()) {
      result = posts.findByAccountIdOrderByPublishedAtDesc(accountId.trim(), page);
    } else if (status != null && !status.isBlank()) {
      result = posts.findByStatusOrderByPublishedAtDesc(status.trim(), page);
    } else {
      result = posts.findAllByOrderByPublishedAtDesc(page);
    }
    return result.stream()
        .map(
            post ->
                new SourceSummary(
                    post.id,
                    post.postId,
                    post.accountId,
                    post.handle,
                    post.status,
                    post.reconciliationState,
                    post.publishedAt,
                    post.ingestedAt))
        .toList();
  }

  @Override
  @Transactional
  public void excludeSource(UUID sourcePostId, String reason, UUID actorId) {
    exclude(requireLockedPost(sourcePostId), reason, actorId, "excluded_manual");
  }

  @Override
  @Transactional
  public void applyComplianceEdit(
      UUID sourcePostId, String permittedText, String reason, UUID actorId) {
    var post = requireLockedPost(sourcePostId);
    if ("deleted".equals(post.status)) {
      throw new IllegalStateException("Deleted source content cannot be edited");
    }
    post.permittedText = permittedText.trim();
    post.status = "excluded_compliance_edit";
    markCompliancePending(post, "edit", reason);
    suppress(post, "compliance edit pending reconciliation", actorId);
    audit(
        "source_compliance_edit",
        "source_post",
        post.postId,
        post.id,
        actorId,
        reason,
        "{\"reconciliationState\":\"pending\"}");
  }

  @Override
  @Transactional
  public void applyComplianceDeletion(UUID sourcePostId, String reason, UUID actorId) {
    var post = requireLockedPost(sourcePostId);
    if (!tombstones.existsByPostId(post.postId)) {
      tombstones.save(
          new SourceTombstone(
              UUID.randomUUID(), post.id, post.postId, post.accountId, reason.trim(), actorId));
    }
    post.permittedText = "[deleted by source]";
    post.status = "deleted";
    post.contentDeletedAt = Instant.now();
    post.excludedAt = post.contentDeletedAt;
    markCompliancePending(post, "delete", reason);
    suppress(post, "source content deleted", actorId);
    audit(
        "source_compliance_deletion",
        "source_post",
        post.postId,
        post.id,
        actorId,
        reason,
        "{\"tombstoned\":true}");
  }

  @Override
  @Transactional
  public void reconcileCompliance(
      UUID sourcePostId, boolean successful, String notes, UUID actorId) {
    var post = requireLockedPost(sourcePostId);
    if (post.reconciliationState == null) {
      throw new IllegalStateException("Source has no pending compliance action");
    }
    post.reconciliationState = successful ? "reconciled" : "failed";
    post.complianceReconciledAt = Instant.now();
    post.complianceError = successful ? null : notes.trim();
    audit(
        "source_compliance_reconciled",
        "source_post",
        post.postId,
        post.id,
        actorId,
        successful ? "reconciled" : notes,
        "{\"successful\":" + successful + "}");
  }

  @Override
  @Transactional
  public void markSync(
      UUID accountId,
      String lastPostId,
      Instant rateLimitResetAt,
      Integer rateLimitLimit,
      Integer rateLimitRemaining,
      String error) {
    var account =
        accounts
            .findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown account"));
    account.lastPostId = lastPostId;
    account.lastSyncAttemptAt = Instant.now();
    account.rateLimitResetAt = rateLimitResetAt;
    account.rateLimitLimit = rateLimitLimit;
    account.rateLimitRemaining = rateLimitRemaining;
    account.lastError = error;
    if (error == null) {
      account.lastSuccessfulSyncAt = account.lastSyncAttemptAt;
      account.consecutiveErrors = 0;
    } else {
      account.consecutiveErrors++;
    }
    account.updatedAt = account.lastSyncAttemptAt;
  }

  @Override
  @Transactional
  public UUID discoverPost(
      UUID monitoredAccountId,
      String postId,
      String canonicalUrl,
      String permittedText,
      Instant publishedAt) {
    var account =
        accounts
            .findById(monitoredAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown monitored account"));
    if (!account.monitoringEnabled || account.removedAt != null) {
      throw new IllegalStateException("Monitoring is paused");
    }
    requireNotBlocked(account.accountId);
    if (postId == null || !postId.matches("\\d{1,30}")) {
      throw new IllegalArgumentException("X postId must be the official numeric post ID");
    }
    validateOfficialCanonicalUrl(canonicalUrl, account.handle, postId);
    if (publishedAt == null || publishedAt.isAfter(Instant.now().plusSeconds(300))) {
      throw new IllegalArgumentException("A valid X publication timestamp is required");
    }
    if (permittedText == null || permittedText.isBlank()) {
      throw new IllegalArgumentException("Permitted X content is required");
    }
    String normalizedPermittedText = SourceNormalizationConsumer.normalize(permittedText);
    if (posts.existsByPostId(postId) || tombstones.existsByPostId(postId)) {
      throw new IllegalArgumentException("Post already ingested");
    }
    UUID sourceId = UUID.randomUUID();
    posts.save(
        new SourcePost(
            sourceId,
            monitoredAccountId,
            postId,
            account.accountId,
            account.handle,
            canonicalUrl,
            normalizedPermittedText,
            publishedAt));
    account.lastSuccessfulSyncAt = Instant.now();
    var payload =
        new XPostDiscovered(
            monitoredAccountId,
            postId,
            account.accountId,
            account.handle,
            canonicalUrl,
            normalizedPermittedText,
            publishedAt);
    events.enqueue(
        "XPostDiscovered",
        sourceId,
        sourceId,
        null,
        "x-post-discovered:" + account.accountId + ":" + postId,
        payload);
    return sourceId;
  }

  private static String normalizeHandle(String handle) {
    String normalized = handle.trim();
    return normalized.startsWith("@") ? normalized.substring(1) : normalized;
  }

  private static void validateOfficialCanonicalUrl(
      String canonicalUrl, String handle, String postId) {
    try {
      URI uri = URI.create(canonicalUrl);
      String expectedPath = "/" + handle + "/status/" + postId;
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || !"x.com".equalsIgnoreCase(uri.getHost())
          || !expectedPath.equalsIgnoreCase(uri.getPath())
          || uri.getUserInfo() != null
          || uri.getPort() != -1
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("Source URL must be the official canonical X post URL");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Source URL must be the official canonical X post URL");
    }
  }

  private MonitoredXAccount requireAccount(UUID accountId) {
    return accounts
        .findById(accountId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown account"));
  }

  private SourcePost requireLockedPost(UUID sourcePostId) {
    return posts
        .findLockedById(sourcePostId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown source post"));
  }

  private void requireNotBlocked(String accountId) {
    if (blockedAccounts.existsById(accountId)) {
      throw new IllegalStateException("Source account is blocked");
    }
  }

  private void exclude(SourcePost post, String reason, UUID actorId, String status) {
    if ("deleted".equals(post.status)) {
      return;
    }
    post.status = status;
    post.excludedAt = Instant.now();
    post.complianceReason = reason.trim();
    suppress(post, reason, actorId);
    audit("source_excluded", "source_post", post.postId, post.id, actorId, reason, "{}");
  }

  private void markCompliancePending(SourcePost post, String action, String reason) {
    post.complianceAction = action;
    post.reconciliationState = "pending";
    post.complianceRequestedAt = Instant.now();
    post.complianceReconciledAt = null;
    post.complianceReason = reason.trim();
    post.complianceError = null;
  }

  private void suppress(SourcePost post, String reason, UUID actorId) {
    replaySafety.suppressAggregate(post.id, reason, actorId);
  }

  private void audit(
      String action,
      String targetType,
      String targetKey,
      UUID sourcePostId,
      UUID actorId,
      String reason,
      String metadata) {
    complianceActions.save(
        new SourceComplianceAction(
            action, targetType, targetKey, sourcePostId, actorId, reason.trim(), metadata));
  }

  private static String joinTopics(Set<String> topics) {
    return topics.stream()
        .map(String::trim)
        .filter(topic -> !topic.isBlank())
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static AccountView toView(MonitoredXAccount account) {
    Set<String> topics =
        Arrays.stream(account.topics.split(","))
            .map(String::trim)
            .filter(topic -> !topic.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    String health;
    Instant now = Instant.now();
    if (account.removedAt != null) {
      health = "removed";
    } else if (!account.monitoringEnabled) {
      health = "paused";
    } else if (account.rateLimitResetAt != null
        && account.rateLimitResetAt.isAfter(now)
        && "rate_limited".equals(account.lastError)) {
      health = "rate_limited";
    } else if (account.consecutiveErrors >= 3) {
      health = "unhealthy";
    } else if (account.lastError != null) {
      health = "degraded";
    } else if (account.lastSuccessfulSyncAt == null) {
      health = "pending";
    } else {
      health = "healthy";
    }
    return new AccountView(
        account.id,
        account.accountId,
        account.handle,
        account.displayName,
        Set.copyOf(topics),
        account.relevanceThreshold,
        account.monitoringEnabled,
        account.createdAt,
        account.removedAt,
        account.lastSuccessfulSyncAt,
        account.lastSyncAttemptAt,
        account.rateLimitResetAt,
        account.rateLimitLimit,
        account.rateLimitRemaining,
        account.lastPostId,
        account.lastError,
        account.consecutiveErrors,
        health,
        account.version);
  }
}
