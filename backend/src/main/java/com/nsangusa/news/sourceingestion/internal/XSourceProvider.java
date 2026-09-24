package com.nsangusa.news.sourceingestion.internal;

import java.time.Instant;
import java.util.List;

interface XSourceProvider {
  AccountLookup lookupAccount(String handle);

  FetchResult fetchRecent(String accountId, String sincePostId);

  LookupResult lookupPosts(List<String> postIds);

  record FetchResult(
      List<Post> posts,
      Instant rateLimitResetAt,
      Integer rateLimitLimit,
      Integer rateLimitRemaining) {}

  record LookupResult(List<LookupPost> posts) {}

  record LookupPost(
      String postId,
      LookupState state,
      String text,
      Instant publishedAt,
      String conversationId,
      List<String> editHistoryPostIds,
      List<Reference> references,
      String detail) {
    String editChainId() {
      return editHistoryPostIds == null || editHistoryPostIds.isEmpty()
          ? postId
          : editHistoryPostIds.getFirst();
    }

    String latestPostId() {
      return editHistoryPostIds == null || editHistoryPostIds.isEmpty()
          ? postId
          : editHistoryPostIds.getLast();
    }
  }

  enum LookupState {
    AVAILABLE,
    DELETED,
    UNKNOWN
  }

  record Post(
      String postId,
      String text,
      Instant publishedAt,
      String conversationId,
      List<String> editHistoryPostIds,
      List<Reference> references) {
    Post(String postId, String text, Instant publishedAt) {
      this(postId, text, publishedAt, null, List.of(postId), List.of());
    }

    String editChainId() {
      return editHistoryPostIds == null || editHistoryPostIds.isEmpty()
          ? postId
          : editHistoryPostIds.getFirst();
    }
  }

  record Reference(String postId, String type) {}

  record AccountLookup(String accountId, String handle, String displayName, boolean simulated) {}
}
