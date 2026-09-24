package com.nsangusa.news.media.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "MEDIA_MIGRATION_JDBC_URL",
    matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/[A-Za-z0-9_-]+")
class MediaDeliveryMigrationTests {
  @Test
  void migrationBackfillsExistingVariantsAndEnforcesTheirAssociation() throws Exception {
    String schema = "media_delivery_test_" + UUID.randomUUID().toString().replace("-", "");
    String url = System.getenv("MEDIA_MIGRATION_JDBC_URL");
    String user = System.getenv("DATABASE_USERNAME");
    String password = System.getenv("DATABASE_PASSWORD");
    var configuration =
        Flyway.configure()
            .dataSource(url, user, password)
            .schemas(schema)
            .defaultSchema(schema)
            .cleanDisabled(false);
    try {
      configuration.target("8").load().migrate();
      try (var connection = DriverManager.getConnection(url, user, password);
          var statement = connection.createStatement()) {
        statement.execute("set search_path to " + schema);
        statement.execute(
            """
            insert into articles(id,slug,headline,summary,body,seo_title,seo_description,topic,
                                 tags,state,confidence,warnings,created_at,updated_at)
            values ('10000000-0000-0000-0000-000000000001','media-fixture','Image','Summary','Body',
                    'Image','Summary','local','','AWAITING_REVIEW',1,'',now(),now())
            """);
        statement.execute(
            """
            insert into image_generations(id,article_id,prompt,alt_text,object_key,provider,model,
                                          safety_status,created_at)
            values ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001',
                    'Prompt','Alt','articles/a/hero-request.svg','fake','fixture','review_required',now())
            """);
        for (String variant : new String[] {"hero", "thumbnail", "social"}) {
          try (var insert =
              connection.prepareStatement(
                  """
              insert into media_assets(id,article_id,object_key,media_type,created_at)
              values (?, '10000000-0000-0000-0000-000000000001', ?, 'image/svg+xml', now())
              """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, "articles/a/" + variant + "-request.svg");
            insert.executeUpdate();
          }
        }
        configuration.target("9").load().migrate();
        try (var result =
            statement.executeQuery(
                """
            select count(distinct variant_name) from media_assets
             where generation_id = '20000000-0000-0000-0000-000000000001'
            """)) {
          assertThat(result.next()).isTrue();
          assertThat(result.getInt(1)).isEqualTo(3);
        }
        assertThatThrownBy(
                () ->
                    statement.executeUpdate(
                        "update media_assets set variant_name = null where variant_name = 'hero'"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                () ->
                    statement.executeUpdate(
                        "update media_assets set variant_name = 'arbitrary' where variant_name = 'hero'"))
            .isInstanceOf(SQLException.class);
      }
    } finally {
      configuration.load().clean();
    }
  }

  @Test
  void fallbackApprovalSetsProvenanceWithoutSelectingLaterUnapprovedProviderCandidates()
      throws Exception {
    String schema = "fallback_provenance_test_" + UUID.randomUUID().toString().replace("-", "");
    String url = System.getenv("MEDIA_MIGRATION_JDBC_URL");
    String user = System.getenv("DATABASE_USERNAME");
    String password = System.getenv("DATABASE_PASSWORD");
    var configuration =
        Flyway.configure()
            .dataSource(url, user, password)
            .schemas(schema)
            .defaultSchema(schema)
            .cleanDisabled(false);
    try {
      configuration.target("20").load().migrate();
      try (var connection = DriverManager.getConnection(url, user, password)) {
        try (var statement = connection.createStatement()) {
          statement.execute("set search_path to " + schema);
        }
        UUID articleId = insertArticle(connection, "DRAFTING");
        UUID original = insertGeneration(connection, articleId, "provider", "model", false);
        approve(connection, original);
        assertSelection(connection, articleId, original, true, "AWAITING_REVIEW", 1);

        configuration.target("21").load().migrate();
        assertSelection(connection, articleId, original, true, "AWAITING_REVIEW", 1);
        UUID fallback =
            insertGeneration(
                connection, articleId, "nsangusa-editorial", "neutral-illustration-v1", true);
        assertSelection(connection, articleId, original, true, "AWAITING_REVIEW", 1);
        approve(connection, fallback);
        assertSelection(connection, articleId, fallback, false, "AWAITING_REVIEW", 2);

        UUID lateProvider = insertGeneration(connection, articleId, "provider", "model", true);
        assertSelection(connection, articleId, fallback, false, "AWAITING_REVIEW", 2);
        approve(connection, original);
        assertSelection(connection, articleId, fallback, false, "AWAITING_REVIEW", 2);
        approve(connection, lateProvider);
        assertSelection(connection, articleId, lateProvider, true, "AWAITING_REVIEW", 3);
      }
    } finally {
      configuration.load().clean();
    }
  }

  @Test
  void fallbackRetainsApprovalEventTimingAndRejectedArticleGuards() throws Exception {
    String schema = "fallback_guards_test_" + UUID.randomUUID().toString().replace("-", "");
    String url = System.getenv("MEDIA_MIGRATION_JDBC_URL");
    String user = System.getenv("DATABASE_USERNAME");
    String password = System.getenv("DATABASE_PASSWORD");
    var configuration =
        Flyway.configure()
            .dataSource(url, user, password)
            .schemas(schema)
            .defaultSchema(schema)
            .cleanDisabled(false);
    try {
      configuration.target("21").load().migrate();
      try (var connection = DriverManager.getConnection(url, user, password)) {
        try (var statement = connection.createStatement()) {
          statement.execute("set search_path to " + schema);
        }
        for (boolean eventExpected : new boolean[] {true, false}) {
          UUID articleId = insertArticle(connection, "DRAFTING");
          UUID fallback =
              insertGeneration(
                  connection,
                  articleId,
                  "nsangusa-editorial",
                  "neutral-illustration-v1",
                  eventExpected);
          assertThatThrownBy(
                  () -> {
                    try (var update =
                        connection.prepareStatement(
                            "update articles set state = 'APPROVED' where id = ?")) {
                      update.setObject(1, articleId);
                      update.executeUpdate();
                    }
                  })
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("unapproved generated image");
          approve(connection, fallback);
          assertSelection(
              connection,
              articleId,
              fallback,
              false,
              eventExpected ? "DRAFTING" : "AWAITING_REVIEW",
              1);
          if (eventExpected) {
            try (var update =
                connection.prepareStatement(
                    "update articles set state = 'AWAITING_REVIEW' where id = ?")) {
              update.setObject(1, articleId);
              update.executeUpdate();
            }
            assertSelection(connection, articleId, fallback, false, "AWAITING_REVIEW", 1);
          }
        }
        UUID otherModelArticle = insertArticle(connection, "AWAITING_REVIEW");
        UUID otherModel =
            insertGeneration(
                connection, otherModelArticle, "nsangusa-editorial", "another-model", true);
        approve(connection, otherModel);
        assertSelection(connection, otherModelArticle, otherModel, true, "AWAITING_REVIEW", 1);
        for (String state : new String[] {"REJECTED", "ARCHIVED"}) {
          UUID articleId = insertArticle(connection, state);
          UUID fallback =
              insertGeneration(
                  connection, articleId, "nsangusa-editorial", "neutral-illustration-v1", true);
          assertThatThrownBy(() -> approve(connection, fallback))
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("Rejected or archived article");
          try (var query =
              connection.prepareStatement(
                  "select safety_status from image_generations where id = ?")) {
            query.setObject(1, fallback);
            try (var result = query.executeQuery()) {
              assertThat(result.next()).isTrue();
              assertThat(result.getString(1)).isEqualTo("review_required");
            }
          }
        }
      }
    } finally {
      configuration.load().clean();
    }
  }

  private static UUID insertArticle(java.sql.Connection connection, String state)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (var insert =
        connection.prepareStatement(
            """
        insert into articles(id,slug,headline,summary,body,seo_title,seo_description,topic,
                             tags,state,confidence,warnings,created_at,updated_at)
        values (?,?,'Image','Summary','Body','Image','Summary','local','',?,1,'',now(),now())
        """)) {
      insert.setObject(1, id);
      insert.setString(2, "image-" + id);
      insert.setString(3, state);
      insert.executeUpdate();
    }
    return id;
  }

  private static UUID insertGeneration(
      java.sql.Connection connection,
      UUID articleId,
      String provider,
      String model,
      boolean eventExpected)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (var insert =
        connection.prepareStatement(
            """
        insert into image_generations(id,article_id,prompt,alt_text,object_key,provider,model,
                                      safety_status,created_at,approval_event_expected)
        values (?,?,'Original illustration','A neutral illustration',?,?,?,'review_required',now(),?)
        """)) {
      insert.setObject(1, id);
      insert.setObject(2, articleId);
      insert.setString(3, "articles/" + articleId + "/hero-" + id + ".png");
      insert.setString(4, provider);
      insert.setString(5, model);
      insert.setBoolean(6, eventExpected);
      insert.executeUpdate();
    }
    return id;
  }

  private static void approve(java.sql.Connection connection, UUID generationId)
      throws SQLException {
    try (var update =
        connection.prepareStatement(
            "update image_generations set safety_status='approved', approved_at=now() where id=?")) {
      update.setObject(1, generationId);
      update.executeUpdate();
    }
  }

  private static void assertSelection(
      java.sql.Connection connection,
      UUID articleId,
      UUID generationId,
      boolean generated,
      String state,
      long version)
      throws SQLException {
    try (var query =
        connection.prepareStatement(
            """
        select approved_image_generation_id,hero_object_key,image_alt_text,generated_image,
               image_approval_required,state,version
          from articles where id=?
        """)) {
      query.setObject(1, articleId);
      try (var result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getObject(1, UUID.class)).isEqualTo(generationId);
        assertThat(result.getString(2))
            .isEqualTo("articles/" + articleId + "/hero-" + generationId + ".png");
        assertThat(result.getString(3)).isEqualTo("A neutral illustration");
        assertThat(result.getBoolean(4)).isEqualTo(generated);
        assertThat(result.getBoolean(5)).isFalse();
        assertThat(result.getString(6)).isEqualTo(state);
        assertThat(result.getLong(7)).isEqualTo(version);
      }
    }
  }
}
