package com.nsangusa.news.comments.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.articles.ArticleState;
import com.nsangusa.news.audit.AuditService;
import com.nsangusa.news.comments.CommentService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.eventprocessing.DurableEventPublisher;
import com.nsangusa.news.identity.IdentityService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  CommentApplicationService.class,
  HeuristicSpamDecisionSupport.class,
  CommentPersistenceTests.Services.class
})
class CommentPersistenceTests {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired CommentService service;
  @Autowired DurableCommandExecutor commands;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean ArticleService articles;
  @MockitoBean IdentityService identity;
  @MockitoBean DurableEventPublisher events;
  UUID articleId;
  UUID authorId;
  UUID reporterId;
  CommentController controller;

  @BeforeEach
  void fixture() {
    articleId = UUID.randomUUID();
    authorId = UUID.randomUUID();
    reporterId = UUID.randomUUID();
    for (UUID id : List.of(authorId, reporterId))
      jdbc.update(
          "insert into users(id,email,display_name,password_hash,email_verified,created_at) values (?,?,?,'unused',true,now())",
          id,
          id + "@example.test",
          "Reader");
    jdbc.update(
        """
        insert into articles(id,slug,headline,summary,body,seo_title,seo_description,topic,tags,state,confidence,warnings,created_at,updated_at,published_at)
        values (?,?,'Article','Summary','Body','Article','Summary','Community','','PUBLISHED',1,'',now(),now(),now())
        """,
        articleId,
        articleId.toString());
    when(articles.get(articleId))
        .thenReturn(
            new ArticleService.ArticleView(
                articleId,
                articleId.toString(),
                "Article",
                "Summary",
                "Body",
                null,
                "Community",
                Set.of(),
                ArticleState.PUBLISHED,
                null,
                null,
                false,
                true,
                Instant.now(),
                Instant.now(),
                0,
                List.of(),
                List.of(),
                1));
    when(identity.profile("reader"))
        .thenReturn(
            new IdentityService.UserProfile(
                authorId,
                "reader@example.test",
                "Reader",
                Set.of("READER"),
                true,
                Instant.now(),
                null,
                null,
                0));
    when(identity.requireAnyRole(anyString(), anySet())).thenReturn(authorId);
    controller = new CommentController(service, identity, commands, articles);
    jdbc.update("delete from comment_settings");
  }

  @Test
  void readerAndModeratorCommandsPersistAndReplayWithoutDuplicateEffects() {
    String submitKey = UUID.randomUUID().toString();
    var request = new CommentController.CommentRequest("A thoughtful comment.", null);
    UUID id = controller.submit(articleId, request, () -> "reader", submitKey).getBody().id();
    assertThat(controller.submit(articleId, request, () -> "reader", submitKey).getBody().id())
        .isEqualTo(id);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from comments where article_id = ?", Integer.class, articleId))
        .isEqualTo(1);
    var initial = service.moderationDetail(id);
    var moderate =
        new CommentController.ModerationRequest(
            "approved", "Reviewed against community policy", initial.version());
    String key = UUID.randomUUID().toString();
    controller.moderate(id, moderate, () -> "reader", key);
    controller.moderate(id, moderate, () -> "reader", key);
    assertThat(service.moderationHistory(id)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where target_id = ? and action='COMMENT_APPROVED'",
                Integer.class,
                id))
        .isEqualTo(1);
    assertThatThrownBy(
            () -> controller.moderate(id, moderate, () -> "reader", UUID.randomUUID().toString()))
        .isInstanceOf(OptimisticLockingFailureException.class);
    var approved = service.approvedForArticle(articleId).getFirst();
    assertThat(approved.version()).isEqualTo(initial.version() + 1);
    service.report(id, reporterId, "abuse", "Please investigate");
    assertThat(service.reportQueue(100))
        .extracting(CommentService.ReportView::commentId)
        .contains(id);
    controller.moderate(
        id,
        new CommentController.ModerationRequest("rejected", "Report upheld", approved.version()),
        () -> "reader",
        UUID.randomUUID().toString());
    assertThat(service.approvedForArticle(articleId)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select status from comment_reports where comment_id=?", String.class, id))
        .isEqualTo("resolved");
    assertThat(service.moderationPage("rejected", articleId, 0, 20).total()).isEqualTo(1);
  }

  @Test
  void firstPolicyAndPrivilegeWritesHaveUnambiguousAbsentVersions() {
    assertThat(service.globalSettings().version()).isEqualTo(-1);
    var global = new CommentService.GlobalSettingsCommand(false, true, 15, .55, .9, 3);
    service.updateGlobalSettings(global, authorId, -1);
    assertThat(service.globalSettings().version()).isEqualTo(0);
    assertThatThrownBy(() -> service.updateGlobalSettings(global, authorId, -1))
        .isInstanceOf(OptimisticLockingFailureException.class);
    service.updateArticleSettings(
        articleId, new CommentService.ArticleSettingsCommand(true, false), authorId, -1);
    assertThat(service.articleSettings(articleId).version()).isEqualTo(0);
    assertThat(service.discussion(articleId, authorId, true).canComment()).isFalse();
    assertThatThrownBy(() -> service.submit(articleId, authorId, "Disabled", null))
        .isInstanceOf(IllegalStateException.class);
    service.suspend(authorId, null, "Repeated abuse", reporterId, -1);
    assertThat(service.privilege(authorId).version()).isEqualTo(0);
    service.restorePrivilege(authorId, "Appeal upheld", reporterId, 0);
    assertThat(service.privilege(authorId).status()).isEqualTo("allowed");
    assertThat(service.privilege(authorId).version()).isEqualTo(1);
    assertThat(service.privilegeHistory(authorId)).hasSize(2);
  }

  @Test
  void editingAndSoftDeletionPersistThreadSemanticsAndAudit() {
    service.updateGlobalSettings(
        new CommentService.GlobalSettingsCommand(true, false, 15, .55, .9, 3), authorId, -1);
    UUID parent = service.submit(articleId, authorId, "Parent", null);
    UUID reply = service.submit(articleId, reporterId, "Reply", parent);
    service.edit(parent, authorId, "Edited parent", 0);
    assertThat(service.moderationDetail(parent).version()).isEqualTo(1);
    service.deleteByAuthor(parent, authorId, 1);
    var visible = service.approvedForArticle(articleId);
    assertThat(visible).hasSize(2);
    assertThat(
            visible.stream()
                .filter(item -> item.id().equals(parent))
                .findFirst()
                .orElseThrow()
                .body())
        .isEqualTo("[deleted]");
    assertThat(
            visible.stream()
                .filter(item -> item.id().equals(reply))
                .findFirst()
                .orElseThrow()
                .parentId())
        .isEqualTo(parent);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where target_id=? and action in ('COMMENT_EDITED','COMMENT_DELETED_BY_AUTHOR')",
                Integer.class,
                parent))
        .isEqualTo(2);
  }

  @Test
  void realHttpPayloadsReadAndWriteThePersistentDiscussion() throws Exception {
    service.updateGlobalSettings(
        new CommentService.GlobalSettingsCommand(true, false, 15, .55, .9, 3), authorId, -1);
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    String key = UUID.randomUUID().toString();
    String response =
        mvc.perform(
                post("/api/v1/articles/{id}/comments", articleId)
                    .principal(() -> "reader")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"body\":\"HTTP comment\",\"parentId\":null}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = new ObjectMapper().readTree(response).get("id").asText();
    mvc.perform(get("/api/v1/articles/{id}/discussion", articleId).principal(() -> "reader"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.canComment").value(true))
        .andExpect(jsonPath("$.comments[0].body").value("HTTP comment"))
        .andExpect(jsonPath("$.comments[0].version").value(0));
    mvc.perform(
            put("/api/v1/comments/{id}", id)
                .principal(() -> "reader")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Updated via HTTP\",\"expectedVersion\":0}"))
        .andExpect(status().isNoContent());
    mvc.perform(
            delete("/api/v1/comments/{id}", id)
                .param("expectedVersion", "1")
                .principal(() -> "reader")
                .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/articles/{id}/discussion", articleId))
        .andExpect(jsonPath("$.comments[0].body").value("[deleted]"))
        .andExpect(jsonPath("$.comments[0].deleted").value(true))
        .andExpect(jsonPath("$.comments[0].version").value(2));
  }

  @TestConfiguration
  @ComponentScan(
      basePackageClasses = {DurableCommandExecutor.class, AuditService.class},
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.REGEX,
              pattern = ".*\\.(JpaDurableCommandExecutor|AuditApplicationService)"))
  static class Services {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    CacheManager cacheManager() {
      return new NoOpCacheManager();
    }
  }
}
