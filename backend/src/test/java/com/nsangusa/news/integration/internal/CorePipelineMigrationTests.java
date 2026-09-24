package com.nsangusa.news.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class CorePipelineMigrationTests {
  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine"));

  @Test
  void upgradesLegacyPipelineRowsAndKeepsOldWritersCompatible() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = new JdbcTemplate(dataSource);

    Flyway.configure()
        .dataSource(dataSource)
        .target(MigrationVersion.fromVersion("6"))
        .load()
        .migrate();

    UUID accountId = UUID.randomUUID();
    UUID sourcePostId = UUID.randomUUID();
    UUID storyId = UUID.randomUUID();
    UUID articleId = UUID.randomUUID();
    UUID generationId = UUID.randomUUID();
    UUID approvedArticleId = UUID.randomUUID();
    UUID approvedGenerationId = UUID.randomUUID();
    UUID scheduleId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    UUID campaignId = UUID.randomUUID();
    UUID deliveryId = UUID.randomUUID();

    jdbc.update(
        """
        insert into monitored_x_accounts
          (id, account_id, handle, display_name, topics, relevance_threshold,
           monitoring_enabled, created_at)
        values (?, 'x-account', 'source', 'Source', 'world', 0.5, true, now())
        """,
        accountId);
    jdbc.update(
        """
        insert into source_posts
          (id, monitored_account_id, post_id, account_id, handle, canonical_url,
           permitted_text, published_at, ingested_at, status, version)
        values (?, ?, 'x-post', 'x-account', 'source',
                'https://x.com/source/status/x-post', 'restricted legacy source text',
                now(), now(), 'active', 0)
        """,
        sourcePostId,
        accountId);
    jdbc.update(
        """
        insert into story_candidates
          (id, primary_source_post_id, topic, status, created_at, version)
        values (?, ?, 'world', 'collecting', now(), 0)
        """,
        storyId,
        sourcePostId);
    jdbc.update(
        """
        update source_posts
           set status = 'excluded_admin',
               compliance_reason = 'restricted before upgrade'
         where id = ?
        """,
        sourcePostId);
    jdbc.update(
        """
        insert into articles
          (id, slug, headline, summary, body, editorial_context, seo_title, seo_description,
           topic, tags, state, human_review_required, comments_enabled, generated_image,
           hero_object_key, image_alt_text, confidence, warnings, created_at, updated_at, version)
        values (?, 'legacy-headline', 'Headline', 'Summary', 'Body', '', 'SEO title',
                'SEO description', 'world', 'news', 'DRAFTING', true, true, true,
                'articles/legacy.webp', 'Legacy image', 0.8, '', now(), now(), 0)
        """,
        articleId);
    jdbc.update(
        """
        insert into image_generations
          (id, article_id, prompt, alt_text, object_key, provider, model, safety_status, created_at)
        values (?, ?, 'prompt', 'Legacy image', 'articles/legacy.webp',
                'provider', 'model', 'pending', now())
        """,
        generationId,
        articleId);
    jdbc.update(
        """
        insert into articles
          (id, slug, headline, summary, body, editorial_context, seo_title, seo_description,
           topic, tags, state, human_review_required, comments_enabled, generated_image,
           hero_object_key, image_alt_text, confidence, warnings, created_at, updated_at, version)
        values (?, 'approved-legacy-image', 'Approved image', 'Summary', 'Body', '',
                'SEO title', 'SEO description', 'world', 'news', 'DRAFTING', true, true,
                false, null, null, 0.8, '', now(), now(), 0)
        """,
        approvedArticleId);
    jdbc.update(
        """
        insert into image_generations
          (id, article_id, prompt, alt_text, object_key, provider, model, safety_status,
           created_at, approved_at, approved_by)
        values (?, ?, 'prompt', 'Approved legacy image', 'articles/approved-legacy.webp',
                'provider', 'model', 'approved', now(), now(), ?)
        """,
        approvedGenerationId,
        approvedArticleId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into scheduled_publications
          (id, article_id, scheduled_for, status, idempotency_key)
        values (?, ?, now() + interval '1 hour', 'pending', 'schedule:legacy')
        """,
        scheduleId,
        articleId);
    jdbc.update(
        """
        insert into newsletter_subscriptions
          (id, email, status, frequency, consent_source, consent_at, verification_token_hash,
           unsubscribe_token_hash, verified_at, version)
        values (?, 'reader@example.test', 'confirmed', 'immediate', 'footer', now(),
                'confirmation-hash', 'unsubscribe-hash', now(), 0)
        """,
        subscriptionId);
    jdbc.update(
        """
        insert into newsletter_campaigns
          (id, campaign_key, article_id, campaign_type, created_at)
        values (?, 'article-published:legacy', ?, 'immediate', now())
        """,
        campaignId,
        articleId);
    jdbc.update(
        """
        insert into newsletter_deliveries
          (id, subscription_id, article_id, campaign_key, status, attempt_count, created_at)
        values (?, ?, ?, 'article-published:legacy', 'pending', 0, now())
        """,
        deliveryId,
        subscriptionId,
        articleId);

    Flyway.configure().dataSource(dataSource).load().migrate();

    assertThat(
            jdbc.queryForObject(
                "select edit_chain_id from source_posts where id = ?", String.class, sourcePostId))
        .isEqualTo("x-post");
    assertThat(
            jdbc.queryForObject(
                "select normalized_text from story_candidate_sources where story_candidate_id = ?",
                String.class,
                storyId))
        .isEqualTo("[source restricted]");
    assertThat(
            jdbc.queryForObject(
                "select pending_image_generation_id from articles where id = ?",
                UUID.class,
                articleId))
        .isEqualTo(generationId);
    assertThat(
            jdbc.queryForObject(
                "select image_approval_required from articles where id = ?",
                Boolean.class,
                articleId))
        .isTrue();
    assertThatThrownBy(
            () -> jdbc.update("update articles set state = 'APPROVED' where id = ?", articleId))
        .rootCause()
        .hasMessageContaining("unapproved generated image");
    assertThat(
            jdbc.queryForObject(
                "select approved_image_generation_id from articles where id = ?",
                UUID.class,
                approvedArticleId))
        .isEqualTo(approvedGenerationId);
    assertThat(
            jdbc.queryForObject(
                "select state from articles where id = ?", String.class, approvedArticleId))
        .isEqualTo("AWAITING_REVIEW");
    assertThat(
            jdbc.queryForObject(
                "select image_approval_required from articles where id = ?",
                Boolean.class,
                approvedArticleId))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select scheduled_by from scheduled_publications where id = ?",
                UUID.class,
                scheduleId))
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    assertThat(
            jdbc.queryForObject(
                "select status from scheduled_publications where id = ?", String.class, scheduleId))
        .isEqualTo("failed");
    assertThat(
            jdbc.queryForObject(
                "select last_error from scheduled_publications where id = ?",
                String.class,
                scheduleId))
        .contains("previous status: pending", "cancel it");
    assertThat(
            jdbc.queryForObject(
                "select provider_idempotency_key from newsletter_deliveries where id = ?",
                String.class,
                deliveryId))
        .isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "select provider_idempotency_applied from newsletter_deliveries where id = ?",
                Boolean.class,
                deliveryId))
        .isFalse();

    jdbc.update(
        "update image_generations set safety_status = 'approved' where id = ?", generationId);
    assertThat(
            jdbc.queryForObject(
                "select approved_image_generation_id from articles where id = ?",
                UUID.class,
                articleId))
        .isEqualTo(generationId);
    assertThat(
            jdbc.queryForObject(
                "select image_approval_required from articles where id = ?",
                Boolean.class,
                articleId))
        .isFalse();
    assertThat(
            jdbc.queryForObject("select state from articles where id = ?", String.class, articleId))
        .isEqualTo("AWAITING_REVIEW");

    jdbc.update("update articles set state = 'REJECTED' where id = ?", articleId);
    UUID rejectedGenerationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into image_generations
          (id, article_id, prompt, alt_text, object_key, provider, model, safety_status, created_at)
        values (?, ?, 'replacement', 'Replacement image', 'articles/replacement.webp',
                'provider', 'model', 'review_required', now())
        """,
        rejectedGenerationId,
        articleId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update image_generations set safety_status = 'approved' where id = ?",
                    rejectedGenerationId))
        .rootCause()
        .hasMessageContaining("Rejected or archived article");

    UUID oldWriterSourceId = UUID.randomUUID();
    jdbc.update(
        """
        insert into source_posts
          (id, monitored_account_id, post_id, account_id, handle, canonical_url,
           permitted_text, published_at, ingested_at, status, version)
        values (?, ?, 'old-writer-post', 'x-account', 'source',
                'https://x.com/source/status/old-writer-post', 'content',
                now(), now(), 'active', 0)
        """,
        oldWriterSourceId,
        accountId);
    assertThat(
            jdbc.queryForObject(
                "select edit_chain_id from source_posts where id = ?",
                String.class,
                oldWriterSourceId))
        .isEqualTo("old-writer-post");

    UUID oldWriterStoryId = UUID.randomUUID();
    jdbc.update(
        """
        insert into story_candidates
          (id, primary_source_post_id, topic, status, created_at, version)
        values (?, ?, 'world', 'collecting', now(), 0)
        """,
        oldWriterStoryId,
        oldWriterSourceId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from story_candidate_sources where story_candidate_id = ?",
                Integer.class,
                oldWriterStoryId))
        .isEqualTo(1);

    UUID oldWriterCampaignId = UUID.randomUUID();
    UUID newWriterDeliveryId = UUID.randomUUID();
    jdbc.update(
        """
        insert into newsletter_campaigns
          (id, campaign_key, article_id, campaign_type, created_at)
        values (?, 'article-published:old-writer', ?, 'immediate', now())
        """,
        oldWriterCampaignId,
        articleId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into newsletter_deliveries
                      (id, subscription_id, article_id, campaign_key, status,
                       attempt_count, created_at)
                    values (?, ?, ?, 'article-published:old-writer', 'pending', 0, now())
                    """,
                    UUID.randomUUID(),
                    subscriptionId,
                    articleId))
        .rootCause()
        .hasMessageContaining("durable leased attempt");
    UUID attemptToken = UUID.randomUUID();
    jdbc.update(
        """
        insert into newsletter_deliveries
          (id, subscription_id, article_id, campaign_key, provider_idempotency_applied,
           attempt_token, attempt_started_at, status, attempt_count, created_at)
        values (?, ?, ?, 'article-published:old-writer', true, ?, now(), 'pending', 1, now())
        """,
        newWriterDeliveryId,
        subscriptionId,
        articleId,
        attemptToken);
    assertThat(
            jdbc.queryForObject(
                "select provider_idempotency_key from newsletter_deliveries where id = ?",
                String.class,
                newWriterDeliveryId))
        .isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "select provider_idempotency_applied from newsletter_deliveries where id = ?",
                Boolean.class,
                newWriterDeliveryId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from newsletter_campaigns
                 where status = 'reconciliation_required'
                   and campaign_type in ('daily', 'weekly')
                """,
                Integer.class))
        .isEqualTo(2);
  }
}
