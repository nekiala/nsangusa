package com.nsangusa.news.identity.internal;

import com.nsangusa.news.identity.IdentityService;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NewsletterAccountLinkRepository {
  private final JdbcClient jdbc;

  NewsletterAccountLinkRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<IdentityService.NewsletterPreference> linkAndFind(UUID userId, String email) {
    jdbc.sql(
            """
            update newsletter_subscriptions
               set user_id = :userId
             where lower(email) = :email
               and (user_id is null or user_id = :userId)
            """)
        .param("userId", userId)
        .param("email", email.trim().toLowerCase(Locale.ROOT))
        .update();
    return find(userId);
  }

  Optional<IdentityService.NewsletterPreference> find(UUID userId) {
    return jdbc.sql(
            """
            select id, status, frequency
              from newsletter_subscriptions
             where user_id = :userId
            """)
        .param("userId", userId)
        .query(
            (result, row) ->
                new IdentityService.NewsletterPreference(
                    result.getObject("id", UUID.class),
                    result.getString("status"),
                    result.getString("frequency")))
        .optional();
  }

  void updateFrequency(UUID userId, String frequency) {
    int updated =
        jdbc.sql(
                """
                update newsletter_subscriptions
                   set frequency = :frequency
                 where user_id = :userId
                   and status in ('pending', 'confirmed')
                """)
            .param("frequency", frequency)
            .param("userId", userId)
            .update();
    if (updated == 0) {
      throw new IllegalArgumentException("No active newsletter subscription is linked");
    }
  }

  void unlink(UUID userId) {
    jdbc.sql("update newsletter_subscriptions set user_id = null where user_id = :userId")
        .param("userId", userId)
        .update();
  }
}
