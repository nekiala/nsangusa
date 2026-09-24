package com.nsangusa.news.sourceingestion.internal;

import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.time.Instant;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@ConditionalOnProperty(name = "news.providers.mode", havingValue = "fake")
class LocalEditorialSeed implements ApplicationRunner {
  private final MonitoredXAccountRepository accounts;
  private final SourcePostRepository posts;
  private final SourceIngestionService ingestion;
  private final boolean enabled;

  LocalEditorialSeed(
      MonitoredXAccountRepository accounts,
      SourcePostRepository posts,
      SourceIngestionService ingestion,
      @org.springframework.beans.factory.annotation.Value("${news.local.seed:true}")
          boolean enabled) {
    this.accounts = accounts;
    this.posts = posts;
    this.ingestion = ingestion;
    this.enabled = enabled;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (!enabled) {
      return;
    }
    String accountId = "990000000000000001";
    var account = accounts.findByAccountId(accountId);
    var monitoredId =
        account
            .map(value -> value.id)
            .orElseGet(
                () ->
                    ingestion.addAccount(
                        accountId,
                        "NsangusaDemo",
                        "Local demonstration source",
                        Set.of("culture"),
                        0));
    String postId = "990000000000000002";
    if (!posts.existsByPostId(postId)
        && account.map(value -> value.removedAt == null && value.monitoringEnabled).orElse(true)) {
      ingestion.discoverPost(
          monitoredId,
          postId,
          "https://x.com/NsangusaDemo/status/" + postId,
          "Synthetic local demonstration: the community library announces an evening reading programme. #culture",
          Instant.parse("2026-09-01T12:00:00Z"));
    }
  }
}
