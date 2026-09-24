package com.nsangusa.news.newsletter.internal;

import com.nsangusa.news.newsletter.NewsletterManagementService.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NewsletterManagementRepository {
  private final JdbcClient jdbc;

  NewsletterManagementRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Page<SubscriptionView> subscriptions(String status, String frequency, int page, int size) {
    var params = new HashMap<String, Object>();
    String from = "from newsletter_subscriptions s where true";
    from += filter("s.status", "status", status, params);
    from += filter("s.frequency", "frequency", frequency, params);
    return page(
        "s.id, s.status, s.frequency, s.consent_source, s.consent_at, s.verified_at, "
            + "s.unsubscribed_at, s.user_id is not null as account_linked",
        from,
        "s.consent_at desc, s.id",
        params,
        (rs, row) ->
            new SubscriptionView(
                uuid(rs, "id"),
                rs.getString("status"),
                rs.getString("frequency"),
                rs.getString("consent_source"),
                instant(rs, "consent_at"),
                instant(rs, "verified_at"),
                instant(rs, "unsubscribed_at"),
                rs.getBoolean("account_linked")),
        page,
        size);
  }

  Page<ConsentView> consent(UUID subscriptionId, int page, int size) {
    return page(
        "id, action, source, occurred_at",
        "from consent_records where subscription_id = :id",
        "occurred_at desc, id",
        Map.of("id", subscriptionId),
        (rs, row) ->
            new ConsentView(
                uuid(rs, "id"),
                rs.getString("action"),
                rs.getString("source"),
                instant(rs, "occurred_at")),
        page,
        size);
  }

  Page<CampaignView> campaigns(String type, String status, int page, int size) {
    var params = new HashMap<String, Object>();
    String from = "from newsletter_campaigns c where true";
    from += filter("c.campaign_type", "type", type, params);
    from += filter("c.status", "status", status, params);
    return page(
        """
        c.id, c.campaign_key, c.article_id, c.campaign_type, c.status, c.created_at, c.completed_at,
        (select count(*) from newsletter_deliveries d where d.campaign_key = c.campaign_key) recipient_count,
        (select count(*) from newsletter_deliveries d where d.campaign_key = c.campaign_key and d.status = 'delivered') accepted_count,
        (select count(*) from newsletter_deliveries d where d.campaign_key = c.campaign_key and d.status in ('failed', 'bounced')) failed_count,
        (select count(*) from newsletter_deliveries d where d.campaign_key = c.campaign_key and d.status = 'reconciliation_required') reconciliation_count
        """,
        from,
        "c.created_at desc, c.id",
        params,
        (rs, row) ->
            new CampaignView(
                uuid(rs, "id"),
                rs.getString("campaign_key"),
                uuid(rs, "article_id"),
                rs.getString("campaign_type"),
                rs.getString("status"),
                instant(rs, "created_at"),
                instant(rs, "completed_at"),
                rs.getLong("recipient_count"),
                rs.getLong("accepted_count"),
                rs.getLong("failed_count"),
                rs.getLong("reconciliation_count")),
        page,
        size);
  }

  Page<DeliveryView> deliveries(
      String status, UUID subscriptionId, String campaignKey, int page, int size) {
    var params = new HashMap<String, Object>();
    String from = "from newsletter_deliveries d where true";
    if ("delivery_confirmed".equals(status)) {
      from += " and d.status = 'delivered' and d.delivery_confirmed_at is not null";
    } else if ("provider_accepted".equals(status)) {
      from += " and d.status = 'delivered' and d.delivery_confirmed_at is null";
    } else {
      from += filter("d.status", "status", status, params);
    }
    from += filter("d.subscription_id", "subscriptionId", subscriptionId, params);
    from += filter("d.campaign_key", "campaignKey", campaignKey, params);
    return page(
        "d.*",
        from,
        "d.created_at desc, d.id",
        params,
        (rs, row) ->
            new DeliveryView(
                uuid(rs, "id"),
                uuid(rs, "subscription_id"),
                uuid(rs, "article_id"),
                rs.getString("campaign_key"),
                deliveryStatus(rs),
                rs.getInt("attempt_count"),
                rs.getString("failure_code"),
                instant(rs, "created_at"),
                instant(rs, "delivered_at"),
                instant(rs, "delivery_confirmed_at"),
                instant(rs, "attempt_started_at"),
                rs.getString("provider_message_id"),
                rs.getBoolean("provider_idempotency_applied"),
                instant(rs, "reconciled_at"),
                rs.getString("reconciliation_outcome"),
                rs.getString("reconciliation_evidence")),
        page,
        size);
  }

  Page<AttemptView> attempts(UUID deliveryId, int page, int size) {
    return page(
        "*",
        "from newsletter_delivery_attempts where delivery_id = :id",
        "attempt_number desc, id",
        Map.of("id", deliveryId),
        (rs, row) ->
            new AttemptView(
                uuid(rs, "id"),
                rs.getInt("attempt_number"),
                rs.getString("status"),
                instant(rs, "started_at"),
                instant(rs, "completed_at"),
                rs.getString("failure_code"),
                rs.getString("provider_message_id")),
        page,
        size);
  }

  Page<SuppressionView> suppressions(String reason, int page, int size) {
    var params = new HashMap<String, Object>();
    String from =
        """
        from newsletter_suppressions s left join newsletter_subscriptions n
        on s.email_hash = encode(sha256(convert_to(n.email, 'UTF8')), 'hex') where true
        """;
    from += filter("s.reason", "reason", reason, params);
    return page(
        "s.id, s.reason, s.created_at, n.id as subscription_id",
        from,
        "s.created_at desc, s.id",
        params,
        (rs, row) ->
            new SuppressionView(
                uuid(rs, "id"),
                rs.getString("reason"),
                instant(rs, "created_at"),
                uuid(rs, "subscription_id")),
        page,
        size);
  }

  boolean enqueueLink(NewsletterSubscription subscription, Instant now) {
    return jdbc.sql(
                """
        insert into newsletter_preference_links(id, subscription_id, verified_at, created_at, expires_at)
        select :id, :subscriptionId, :verifiedAt, :createdAt, :expiresAt
        where not exists(select 1 from newsletter_preference_links
          where subscription_id = :subscriptionId and created_at > :cooldown)
        """)
            .param("id", UUID.randomUUID())
            .param("subscriptionId", subscription.id)
            .param("verifiedAt", java.sql.Timestamp.from(subscription.verifiedAt))
            .param("createdAt", java.sql.Timestamp.from(now))
            .param("expiresAt", java.sql.Timestamp.from(now.plusSeconds(1800)))
            .param("cooldown", java.sql.Timestamp.from(now.minusSeconds(600)))
            .update()
        == 1;
  }

  Optional<PreferenceLink> preferenceLink(UUID requestId, UUID subscriptionId) {
    return jdbc.sql(
            """
        select id, subscription_id, verified_at, expires_at from newsletter_preference_links
        where id = :id and subscription_id = :subscriptionId
        """)
        .param("id", requestId)
        .param("subscriptionId", subscriptionId)
        .query(
            (rs, row) ->
                new PreferenceLink(
                    uuid(rs, "id"),
                    uuid(rs, "subscription_id"),
                    instant(rs, "verified_at"),
                    instant(rs, "expires_at")))
        .optional();
  }

  void recordReconciliation(UUID id, UUID actor, Reconciliation request) {
    jdbc.sql(
            """
        update newsletter_deliveries
        set status = 'reconciled', reconciled_at = now(), reconciled_by = :actor,
            reconciliation_outcome = :outcome, reconciliation_evidence = :evidence,
            provider_message_id = coalesce(:messageId, provider_message_id)
        where id = :id
        """)
        .param("id", id)
        .param("actor", actor)
        .param("outcome", request.outcome())
        .param("evidence", request.evidenceReference())
        .param("messageId", request.providerMessageId())
        .update();
  }

  private <T> Page<T> page(
      String select,
      String from,
      String order,
      Map<String, ?> params,
      RowMapper<T> mapper,
      int page,
      int size) {
    long total = jdbc.sql("select count(*) " + from).params(params).query(Long.class).single();
    var items =
        jdbc.sql(
                "select "
                    + select
                    + " "
                    + from
                    + " order by "
                    + order
                    + " limit :limit offset :offset")
            .params(params)
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(mapper)
            .list();
    return new Page<>(items, page, size, total);
  }

  private static String filter(
      String column, String name, Object value, Map<String, Object> params) {
    if (value == null || "".equals(value)) return "";
    params.put(name, value);
    return " and " + column + " = :" + name;
  }

  static Instant instant(ResultSet rs, String name) throws SQLException {
    var value = rs.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }

  static UUID uuid(ResultSet rs, String name) throws SQLException {
    return rs.getObject(name, UUID.class);
  }

  private static String deliveryStatus(ResultSet rs) throws SQLException {
    if ("delivered".equals(rs.getString("status"))) {
      return instant(rs, "delivery_confirmed_at") == null
          ? "provider_accepted"
          : "delivery_confirmed";
    }
    return rs.getString("status");
  }

  record PreferenceLink(UUID id, UUID subscriptionId, Instant verifiedAt, Instant expiresAt) {}
}
