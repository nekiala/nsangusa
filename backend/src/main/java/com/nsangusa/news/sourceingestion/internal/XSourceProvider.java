package com.nsangusa.news.sourceingestion.internal;

import java.time.Instant;
import java.util.List;

interface XSourceProvider {
  FetchResult fetchRecent(String accountId, String sincePostId);

  record FetchResult(
      List<Post> posts,
      Instant rateLimitResetAt,
      Integer rateLimitLimit,
      Integer rateLimitRemaining) {}

  record Post(String postId, String text, Instant publishedAt) {}
}
