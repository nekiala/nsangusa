package com.nsangusa.news.publication.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class PublicationMigrationTests {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

  @Test
  void legacySchedulesFailClosedAndDatabaseEnforcesOneActiveVersionedSchedule() {
    String schema = "publication_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema="
                + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    var migrations =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .cleanDisabled(false);
    var jdbc = new JdbcTemplate(dataSource);
    try {
      migrations.target("11").load().migrate();
      UUID article = UUID.randomUUID();
      UUID actor = UUID.randomUUID();
      UUID older = UUID.randomUUID();
      UUID retained = UUID.randomUUID();
      jdbc.update(
          """
          insert into articles(id,slug,headline,summary,body,seo_title,seo_description,topic,tags,state,confidence,warnings,created_at,updated_at)
          values (?,?,'Headline','Summary','Body','Headline','Summary','world','','SCHEDULED',1,'',now(),now())
          """,
          article,
          "migration-" + article);
      jdbc.update(
          """
          insert into scheduled_publications(id,article_id,scheduled_for,status,idempotency_key,scheduled_by)
          values (?,?,now() + interval '1 minute','scheduled',?,?),
                 (?,?,now() + interval '2 minutes','scheduled',?,?)
          """,
          older,
          article,
          older.toString(),
          actor,
          retained,
          article,
          retained.toString(),
          actor);

      migrations.target("12").load().migrate();

      var legacy = jdbc.queryForMap("select * from scheduled_publications where id = ?", retained);
      assertThat(legacy.get("status")).isEqualTo("failed");
      assertThat(legacy.get("article_version")).isNull();
      assertThat(legacy.get("scheduled_by")).isEqualTo(actor);
      assertThat(legacy.get("updated_by")).isEqualTo(actor);
      assertThat(legacy.get("last_error").toString()).contains("no approved article version");
      assertThat(
              jdbc.queryForObject(
                  "select status from scheduled_publications where id = ?", String.class, older))
          .isEqualTo("cancelled");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "update scheduled_publications set status = 'scheduled' where id = ?",
                      retained))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "update scheduled_publications set status = 'scheduled', article_version = 0 where id = ?",
                      older))
          .isInstanceOf(DataIntegrityViolationException.class);

      jdbc.update("update scheduled_publications set status = 'cancelled' where id = ?", retained);
      assertThat(
              jdbc.queryForObject(
                  "select version from scheduled_publications where id = ?", Long.class, retained))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select completed_at is not null from scheduled_publications where id = ?",
                  Boolean.class,
                  retained))
          .isTrue();
      jdbc.update(
          "update scheduled_publications set status = 'scheduled', article_version = 0 where id = ?",
          older);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from scheduled_publications where status = 'scheduled'",
                  Integer.class))
          .isEqualTo(1);
      jdbc.update(
          "update scheduled_publications set status = 'cancelled', last_error = null where id = ?",
          older);
      assertThat(
              jdbc.queryForObject(
                  "select last_error from scheduled_publications where id = ?",
                  String.class,
                  older))
          .contains("source eligibility reconciliation");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from audit_records where target_id = ? and action = 'PUBLICATION_SCHEDULE_CANCELLED'",
                  Integer.class,
                  older))
          .isEqualTo(1);
    } finally {
      migrations.load().clean();
    }
  }
}
