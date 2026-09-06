package com.nsangusa.news.identity.internal;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class VerificationTokenCleanup {
  private final VerificationTokenRepository tokens;
  private final IdentityProperties properties;

  VerificationTokenCleanup(VerificationTokenRepository tokens, IdentityProperties properties) {
    this.tokens = tokens;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${news.identity.token-cleanup-interval:PT1H}")
  @Transactional
  void cleanup() {
    Instant now = Instant.now();
    tokens.deleteExpiredAndConsumed(now, now.minus(properties.getConsumedTokenRetention()));
  }
}
