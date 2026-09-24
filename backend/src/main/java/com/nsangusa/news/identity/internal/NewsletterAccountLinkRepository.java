package com.nsangusa.news.identity.internal;

import com.nsangusa.news.identity.IdentityService;
import com.nsangusa.news.newsletter.NewsletterService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class NewsletterAccountLinkRepository {
  private final NewsletterService newsletter;

  NewsletterAccountLinkRepository(NewsletterService newsletter) {
    this.newsletter = newsletter;
  }

  Optional<IdentityService.NewsletterPreference> linkAndFind(UUID userId, String email) {
    return newsletter
        .linkAndFindAccount(userId, email)
        .map(NewsletterAccountLinkRepository::preference);
  }

  Optional<IdentityService.NewsletterPreference> find(UUID userId) {
    return newsletter.accountPreference(userId).map(NewsletterAccountLinkRepository::preference);
  }

  void updateFrequency(UUID userId, String frequency) {
    newsletter.updateAccountFrequency(userId, frequency);
  }

  void unsubscribeAndUnlink(UUID userId) {
    newsletter.unsubscribeAndUnlinkAccount(userId);
  }

  private static IdentityService.NewsletterPreference preference(
      NewsletterService.AccountPreference preference) {
    return new IdentityService.NewsletterPreference(
        preference.subscriptionId(), preference.status(), preference.frequency());
  }
}
