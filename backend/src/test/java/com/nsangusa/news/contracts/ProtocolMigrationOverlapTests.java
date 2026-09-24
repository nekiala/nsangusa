package com.nsangusa.news.contracts;

import static org.junit.jupiter.api.Assertions.*;

import com.nsangusa.news.articles.ArticleContent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ProtocolMigrationOverlapTests {
  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine"));

  @Test
  void additiveV20V21PreserveLegacyRowsAndExposeUnsupportedMixedBodyWriters() throws Exception {
    String schema = "protocol_overlap_" + UUID.randomUUID().toString().replace("-", "");
    String url = POSTGRES.getJdbcUrl();
    String user = POSTGRES.getUsername();
    String password = POSTGRES.getPassword();
    var flyway =
        Flyway.configure()
            .dataSource(url, user, password)
            .schemas(schema)
            .defaultSchema(schema)
            .cleanDisabled(false);
    UUID articleId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    try {
      // V18 is the last migration before V20; there is intentionally no V19 in this history.
      flyway.target("18").load().migrate();
      try (Connection connection = DriverManager.getConnection(url, user, password);
          var statement = connection.createStatement()) {
        statement.execute("set search_path to " + schema);
        insertOldShape(connection, articleId);
        try (var insert =
            connection.prepareStatement(
                """
                insert into article_revisions
                  (id, article_id, revision_number, headline, summary, body, reason, created_at, snapshot)
                values (?, ?, 1, 'Headline', 'Summary', 'Legacy body', 'manual', now(),
                        '{"body":"Legacy body","legacyMarker":"preserve"}'::jsonb)
                """)) {
          insert.setObject(1, revisionId);
          insert.setObject(2, articleId);
          insert.executeUpdate();
        }
        String before =
            scalar(
                connection,
                "select snapshot::text from article_revisions where id = ?",
                revisionId);
        flyway.target("20").load().migrate();
        flyway.target("21").load().migrate();
        assertNull(
            scalar(connection, "select content::text from articles where id = ?", articleId));
        assertEquals(
            before,
            scalar(
                connection,
                "select snapshot::text from article_revisions where id = ?",
                revisionId));
        assertEquals(
            "Legacy body",
            ArticleContent.fromBody(
                    scalar(connection, "select body from articles where id = ?", articleId))
                .plainText());

        // A pre-content SQL writer can still create and update legacy rows after expansion.
        UUID lateOldWriter = UUID.randomUUID();
        insertOldShape(connection, lateOldWriter);
        try (var update =
            connection.prepareStatement(
                "update articles set body = 'Old writer update' where id = ?")) {
          update.setObject(1, lateOldWriter);
          assertEquals(1, update.executeUpdate());
        }
        assertNull(
            scalar(connection, "select content::text from articles where id = ?", lateOldWriter));

        String structured =
            """
            {"version":1,"blocks":[{"type":"paragraph","text":"Structured wins"}]}
            """;
        try (var update =
            connection.prepareStatement(
                "update articles set content = ?::jsonb, body = 'Structured wins' where id = ?")) {
          update.setString(1, structured);
          update.setObject(2, articleId);
          assertEquals(1, update.executeUpdate());
        }
        var decoded =
            ContractRuntime.eventMapper()
                .readValue(
                    scalar(
                        connection, "select content::text from articles where id = ?", articleId),
                    ArticleContent.class);
        assertEquals("Structured wins", decoded.plainText());

        // This is intentionally NOT certified overlap: an old writer cannot keep content in sync.
        try (var update =
            connection.prepareStatement(
                "update articles set body = 'Stale old writer' where id = ?")) {
          update.setObject(1, articleId);
          update.executeUpdate();
        }
        assertNotEquals(
            decoded.plainText(),
            scalar(connection, "select body from articles where id = ?", articleId));
        assertEquals(
            before,
            scalar(
                connection,
                "select snapshot::text from article_revisions where id = ?",
                revisionId));
      }
    } finally {
      flyway.load().clean();
    }
  }

  private static void insertOldShape(Connection connection, UUID id) throws Exception {
    try (var insert =
        connection.prepareStatement(
            """
            insert into articles
              (id, slug, headline, summary, body, seo_title, seo_description, topic, tags,
               state, confidence, warnings, created_at, updated_at)
            values (?, ?, 'Headline', 'Summary', 'Legacy body', 'Headline', 'Summary',
                    'science', '', 'AWAITING_REVIEW', 1, '', now(), now())
            """)) {
      insert.setObject(1, id);
      insert.setString(2, "compatibility-" + id);
      insert.executeUpdate();
    }
  }

  private static String scalar(Connection connection, String sql, UUID id) throws Exception {
    try (var query = connection.prepareStatement(sql)) {
      query.setObject(1, id);
      try (var result = query.executeQuery()) {
        assertTrue(result.next());
        return result.getString(1);
      }
    }
  }
}
