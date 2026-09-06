package com.nsangusa.news.sourceingestion.internal;

import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.util.Comparator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class XMonitoringScheduler {
  private final MonitoredXAccountRepository accounts;
  private final XSourceProvider provider;
  private final SourceIngestionService ingestion;

  XMonitoringScheduler(
      MonitoredXAccountRepository accounts,
      XSourceProvider provider,
      SourceIngestionService ingestion) {
    this.accounts = accounts;
    this.provider = provider;
    this.ingestion = ingestion;
  }

  @Scheduled(fixedDelayString = "${news.x.poll-interval:60000}")
  void synchronize() {
    for (var account : accounts.findByMonitoringEnabledTrueAndRemovedAtIsNull()) {
      try {
        var result = provider.fetchRecent(account.accountId, account.lastPostId);
        var ordered =
            result.posts().stream()
                .sorted(Comparator.comparing(XSourceProvider.Post::publishedAt))
                .toList();
        String lastPostId = account.lastPostId;
        for (var post : ordered) {
          ingestion.discoverPost(
              account.id,
              post.postId(),
              "https://x.com/" + account.handle + "/status/" + post.postId(),
              post.text(),
              post.publishedAt());
          lastPostId = post.postId();
        }
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
