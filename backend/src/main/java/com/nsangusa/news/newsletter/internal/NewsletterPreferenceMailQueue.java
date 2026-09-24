package com.nsangusa.news.newsletter.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class NewsletterPreferenceMailQueue {
  private final JdbcClient jdbc;

  NewsletterPreferenceMailQueue(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  List<UUID> pending() {
    jdbc.sql(
            """
        update newsletter_preference_links set status = 'reconciliation_required', failure_code = 'acceptance_unknown'
        where status = 'sending' and claimed_at < now() - interval '10 minutes'
        """)
        .update();
    jdbc.sql(
            """
        update newsletter_preference_links set status = 'expired'
        where status = 'pending' and expires_at <= now()
        """)
        .update();
    return jdbc.sql(
            """
        select id from newsletter_preference_links where status = 'pending' and expires_at > now()
        order by created_at limit 25
        """)
        .query(UUID.class)
        .list();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  Optional<Message> claim(UUID id) {
    return jdbc.sql(
            """
        update newsletter_preference_links l set status = 'sending', claimed_at = now()
        from newsletter_subscriptions s
        where l.id = :id and l.status = 'pending' and l.expires_at > now()
          and s.id = l.subscription_id and s.status = 'confirmed' and s.verified_at = l.verified_at
        returning l.id, l.subscription_id, l.expires_at, l.verified_at, s.email
        """)
        .param("id", id)
        .query(
            (rs, row) ->
                new Message(
                    NewsletterManagementRepository.uuid(rs, "id"),
                    NewsletterManagementRepository.uuid(rs, "subscription_id"),
                    rs.getString("email"),
                    NewsletterManagementRepository.instant(rs, "expires_at"),
                    NewsletterManagementRepository.instant(rs, "verified_at")))
        .optional();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void complete(UUID id, boolean accepted) {
    jdbc.sql(
            """
        update newsletter_preference_links set status = :status, completed_at = now(), failure_code = :failure
        where id = :id and status = 'sending'
        """)
        .param("id", id)
        .param("status", accepted ? "accepted" : "reconciliation_required")
        .param("failure", accepted ? null : "acceptance_unknown")
        .update();
  }

  record Message(
      UUID id, UUID subscriptionId, String email, Instant expiresAt, Instant verifiedAt) {}
}
