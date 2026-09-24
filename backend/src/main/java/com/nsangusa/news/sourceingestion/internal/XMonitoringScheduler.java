package com.nsangusa.news.sourceingestion.internal;

import com.nsangusa.news.integration.SystemActors;
import com.nsangusa.news.sourceingestion.SourceIngestionService;
import com.nsangusa.news.sourceingestion.SourceIngestionService.ProviderPostSnapshot;
import com.nsangusa.news.sourceingestion.SourceIngestionService.SourceRelationshipInput;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class XMonitoringScheduler {
  private static final Logger log = LoggerFactory.getLogger(XMonitoringScheduler.class);

  private final MonitoredXAccountRepository accounts;
  private final XSourceProvider provider;
  private final SourceIngestionService ingestion;
  private final SourcePostRepository posts;
  private final int reconciliationLimit;

  XMonitoringScheduler(
      MonitoredXAccountRepository accounts,
      XSourceProvider provider,
      SourceIngestionService ingestion,
      SourcePostRepository posts,
      @Value("${news.x.reconciliation-limit:100}") int reconciliationLimit) {
    this.accounts = accounts;
    this.provider = provider;
    this.ingestion = ingestion;
    this.posts = posts;
    this.reconciliationLimit = Math.max(1, Math.min(reconciliationLimit, 100));
  }

  @Scheduled(fixedDelayString = "${news.x.poll-interval:60000}")
  void synchronize() {
    var monitoredAccounts = accounts.findByMonitoringEnabledTrueAndRemovedAtIsNull();
    for (var account : monitoredAccounts) {
      try {
        var result = provider.fetchRecent(account.accountId, account.lastPostId);
        var ordered =
            result.posts().stream()
                .sorted(Comparator.comparing(XSourceProvider.Post::publishedAt))
                .toList();
        String lastPostId = account.lastPostId;
        for (var post : ordered) {
          reconcileTimelinePost(account, post);
          lastPostId = post.postId();
        }
        reconcileExisting(account);
        ingestion.markSync(
            account.id,
            lastPostId,
            result.rateLimitResetAt(),
            result.rateLimitLimit(),
            result.rateLimitRemaining(),
            null);
      } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests exception) {
        var headers = exception.getResponseHeaders();
        ingestion.markSync(
            account.id,
            account.lastPostId,
            parseReset(
                headers == null ? null : headers.getFirst("x-rate-limit-reset"),
                account.rateLimitResetAt),
            parseInteger(
                headers == null ? null : headers.getFirst("x-rate-limit-limit"),
                account.rateLimitLimit),
            0,
            "rate_limited");
      } catch (RuntimeException exception) {
        ingestion.markSync(
            account.id,
            account.lastPostId,
            account.rateLimitResetAt,
            account.rateLimitLimit,
            account.rateLimitRemaining,
            exception.getClass().getSimpleName());
      }
    }
    Set<java.util.UUID> monitoredIds =
        monitoredAccounts.stream()
            .map(account -> account.id)
            .collect(Collectors.toUnmodifiableSet());
    for (var account : accounts.findAllByOrderByHandleAsc()) {
      if (monitoredIds.contains(account.id)) {
        continue;
      }
      try {
        reconcileExisting(account);
      } catch (RuntimeException exception) {
        log.warn(
            "Unable to reconcile retained X posts for disabled account {}",
            account.accountId,
            exception);
      }
    }
  }

  private void reconcileExisting(MonitoredXAccount account) {
    var candidates = posts.findReconciliationCandidates(account.id, reconciliationLimit);
    if (candidates.isEmpty()) {
      return;
    }
    var results =
        provider.lookupPosts(candidates.stream().map(post -> post.postId).toList()).posts().stream()
            .collect(
                Collectors.toMap(
                    XSourceProvider.LookupPost::postId,
                    Function.identity(),
                    (first, ignored) -> first));
    var latestIds =
        results.values().stream()
            .filter(result -> result.state() == XSourceProvider.LookupState.AVAILABLE)
            .map(XSourceProvider.LookupPost::latestPostId)
            .filter(latestId -> !results.containsKey(latestId))
            .distinct()
            .toList();
    Map<String, XSourceProvider.LookupPost> latestResults =
        latestIds.isEmpty()
            ? Map.of()
            : provider.lookupPosts(latestIds).posts().stream()
                .collect(
                    Collectors.toMap(
                        XSourceProvider.LookupPost::postId,
                        Function.identity(),
                        (first, ignored) -> first));
    java.time.Instant checkedAt = java.time.Instant.now();
    Set<String> processedChains = new HashSet<>();
    for (var candidate : candidates) {
      var result = results.get(candidate.postId);
      if (result == null || result.state() == XSourceProvider.LookupState.UNKNOWN) {
        ingestion.markProviderChecked(candidate.id, checkedAt);
      } else if (result.state() == XSourceProvider.LookupState.DELETED) {
        ingestion.applyProviderDeletion(
            candidate.id,
            result.detail() == null ? "Official X API reported the post deleted" : result.detail(),
            checkedAt);
      } else {
        var latest = latestResults.getOrDefault(result.latestPostId(), result);
        String chainId = latest.editChainId();
        if (!processedChains.add(chainId)) {
          continue;
        }
        if (latest.state() == XSourceProvider.LookupState.DELETED) {
          for (var match : matchingPosts(chainId, latest.editHistoryPostIds(), candidate)) {
            ingestion.applyProviderDeletion(
                match.id,
                latest.detail() == null
                    ? "Official X API reported the edit chain deleted"
                    : latest.detail(),
                checkedAt);
          }
        } else if (latest.state() == XSourceProvider.LookupState.AVAILABLE) {
          reconcileAvailableLookup(account, candidate, latest, checkedAt);
        } else {
          ingestion.markProviderChecked(candidate.id, checkedAt);
        }
      }
    }
  }

  private void reconcileTimelinePost(MonitoredXAccount account, XSourceProvider.Post post) {
    var matches = matchingPosts(post.editChainId(), post.editHistoryPostIds(), null);
    String canonicalUrl = "https://x.com/" + account.handle + "/status/" + post.postId();
    if (matches.isEmpty()) {
      ingestion.discoverPost(
          account.id,
          post.postId(),
          post.editChainId(),
          post.conversationId(),
          canonicalUrl,
          post.text(),
          post.publishedAt(),
          relationships(post));
      return;
    }
    var canonical = canonical(matches, post.postId(), post.editHistoryPostIds());
    suppressLegacyDuplicates(matches, canonical, java.time.Instant.now());
    ingestion.reconcileProviderPost(
        canonical.id,
        new ProviderPostSnapshot(
            post.postId(),
            post.editChainId(),
            canonicalUrl,
            post.text(),
            post.conversationId(),
            java.time.Instant.now(),
            relationships(post)));
  }

  private void reconcileAvailableLookup(
      MonitoredXAccount account,
      SourcePost candidate,
      XSourceProvider.LookupPost result,
      java.time.Instant checkedAt) {
    var matches = matchingPosts(result.editChainId(), result.editHistoryPostIds(), candidate);
    var canonical = canonical(matches, result.postId(), result.editHistoryPostIds());
    suppressLegacyDuplicates(matches, canonical, checkedAt);
    ingestion.reconcileProviderPost(
        canonical.id,
        new ProviderPostSnapshot(
            result.postId(),
            result.editChainId(),
            "https://x.com/" + account.handle + "/status/" + result.postId(),
            result.text(),
            result.conversationId(),
            checkedAt,
            relationships(result.references())));
  }

  private List<SourcePost> matchingPosts(
      String editChainId, List<String> editHistoryPostIds, SourcePost fallback) {
    var matches = new LinkedHashMap<UUID, SourcePost>();
    posts.findByEditChainId(editChainId).ifPresent(post -> matches.put(post.id, post));
    var history =
        editHistoryPostIds == null || editHistoryPostIds.isEmpty()
            ? List.of(editChainId)
            : editHistoryPostIds;
    for (var post : posts.findByPostIdIn(history)) {
      matches.put(post.id, post);
    }
    if (fallback != null) {
      matches.put(fallback.id, fallback);
    }
    return List.copyOf(matches.values());
  }

  private static SourcePost canonical(
      List<SourcePost> matches, String latestPostId, List<String> editHistoryPostIds) {
    var positions = new HashMap<String, Integer>();
    if (editHistoryPostIds != null) {
      for (int index = 0; index < editHistoryPostIds.size(); index++) {
        positions.put(editHistoryPostIds.get(index), index);
      }
    }
    return matches.stream()
        .max(
            Comparator.comparingInt(
                post ->
                    post.postId.equals(latestPostId)
                        ? Integer.MAX_VALUE
                        : positions.getOrDefault(post.postId, -1)))
        .orElseThrow(() -> new IllegalStateException("Source edit chain match missing"));
  }

  private void suppressLegacyDuplicates(
      List<SourcePost> matches, SourcePost canonical, java.time.Instant checkedAt) {
    for (var duplicate : matches) {
      if (duplicate.id.equals(canonical.id) || "deleted".equals(duplicate.status)) {
        continue;
      }
      if (!"excluded_compliance_edit".equals(duplicate.status)) {
        ingestion.applyComplianceEdit(
            duplicate.id,
            duplicate.permittedText,
            "Duplicate legacy row from an official X edit chain",
            SystemActors.AUTOMATION);
        ingestion.reconcileCompliance(
            duplicate.id,
            true,
            "Canonicalized into the newest retained X edit-chain row",
            SystemActors.AUTOMATION);
      } else if (duplicate.reconciliationState != null
          && !"reconciled".equals(duplicate.reconciliationState)) {
        ingestion.reconcileCompliance(
            duplicate.id,
            true,
            "Canonicalized into the newest retained X edit-chain row",
            SystemActors.AUTOMATION);
      }
      ingestion.markProviderChecked(duplicate.id, checkedAt);
    }
  }

  private static List<SourceRelationshipInput> relationships(XSourceProvider.Post post) {
    return relationships(post.references());
  }

  private static List<SourceRelationshipInput> relationships(
      List<XSourceProvider.Reference> references) {
    return references == null
        ? List.of()
        : references.stream()
            .map(
                reference ->
                    new SourceRelationshipInput(
                        reference.postId(),
                        reference.type() == null
                            ? "unknown"
                            : reference.type().toLowerCase(java.util.Locale.ROOT)))
            .toList();
  }

  private static java.time.Instant parseReset(String value, java.time.Instant fallback) {
    try {
      return value == null ? fallback : java.time.Instant.ofEpochSecond(Long.parseLong(value));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static Integer parseInteger(String value, Integer fallback) {
    try {
      return value == null ? fallback : Integer.valueOf(value);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
