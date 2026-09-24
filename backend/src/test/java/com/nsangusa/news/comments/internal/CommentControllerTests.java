package com.nsangusa.news.comments.internal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.comments.CommentService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.identity.IdentityService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = CommentController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.nsangusa\\.news\\.identity\\.internal\\..*"))
@Import(CommentControllerTests.Security.class)
class CommentControllerTests {
  @Autowired MockMvc mvc;
  @MockitoBean CommentService comments;
  @MockitoBean IdentityService identity;
  @MockitoBean DurableCommandExecutor commands;
  @MockitoBean ArticleService articles;
  @MockitoBean org.springframework.cache.CacheManager cacheManager;
  final UUID actor = UUID.randomUUID();
  final UUID article = UUID.randomUUID();
  final UUID comment = UUID.randomUUID();

  @BeforeEach
  void setup() {
    when(identity.profile(anyString()))
        .thenReturn(
            new IdentityService.UserProfile(
                actor,
                "reader@example.test",
                "Reader",
                Set.of("READER"),
                true,
                Instant.now(),
                null,
                null,
                0));
    when(identity.requireAnyRole(anyString(), anySet())).thenReturn(actor);
    when(commands.execute(any(), any(), any(), any(), any()))
        .thenAnswer(call -> call.<Supplier<String>>getArgument(4).get());
  }

  @Test
  void anonymousDiscussionIsPublicAndNeverCached() throws Exception {
    when(comments.discussion(article, null, false))
        .thenReturn(
            new CommentService.DiscussionView(
                List.of(), null, false, false, true, true, 15, 1, "allowed", null, false));
    mvc.perform(get("/api/v1/articles/{id}/discussion", article))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.authenticated").value(false))
        .andExpect(jsonPath("$.canComment").value(false));
  }

  @Test
  void commentSubmissionRequiresAuthenticationRoleAndCsrfAndUsesRealAccountIdentity()
      throws Exception {
    var request =
        post("/api/v1/articles/{id}/comments", article)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"Hello\"}");
    mvc.perform(request.with(csrf())).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/articles/{id}/comments", article)
                .with(user("reader").roles("READER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Hello\"}"))
        .andExpect(status().isForbidden());
    when(comments.submit(article, actor, "Hello", null)).thenReturn(comment);
    mvc.perform(
            post("/api/v1/articles/{id}/comments", article)
                .with(user("reader").roles("READER"))
                .with(csrf())
                .header("Idempotency-Key", "comment-key-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Hello\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(comment.toString()));
    verify(commands)
        .execute(
            eq(actor),
            eq("comment-key-001"),
            eq("POST /api/v1/articles/" + article + "/comments"),
            any(),
            any());
  }

  @Test
  void staffAccountsDoNotNeedAnAdditionalReaderRole() throws Exception {
    for (String role : List.of("MODERATOR", "EDITOR", "ADMINISTRATOR")) {
      when(identity.profile("staff"))
          .thenReturn(
              new IdentityService.UserProfile(
                  actor,
                  "staff@example.test",
                  "Staff",
                  Set.of(role),
                  true,
                  Instant.now(),
                  null,
                  null,
                  0));
      when(comments.submit(article, actor, "Hello", null)).thenReturn(comment);
      mvc.perform(
              post("/api/v1/articles/{id}/comments", article)
                  .with(user("staff").roles(role))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"body\":\"Hello\"}"))
          .andExpect(status().isAccepted());
    }
  }

  @Test
  void unverifiedAccountsCannotSubmitEvenWithAReaderAuthority() throws Exception {
    when(identity.profile("unverified"))
        .thenReturn(
            new IdentityService.UserProfile(
                actor,
                "unverified@example.test",
                "Unverified reader",
                Set.of("READER"),
                false,
                Instant.now(),
                null,
                null,
                0));
    mvc.perform(
            post("/api/v1/articles/{id}/comments", article)
                .with(user("unverified").roles("READER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Hello\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/articles/{id}/discussion", article)
                .with(user("unverified").roles("READER")))
        .andExpect(status().isOk());
    verify(comments).discussion(article, actor, false);
    verifyNoInteractions(commands);
  }

  @Test
  void moderationQueuesAndMutationsRejectReadersAndEditors() throws Exception {
    for (String role : List.of("READER", "EDITOR")) {
      mvc.perform(get("/api/v1/admin/comments").with(user("user").roles(role)))
          .andExpect(status().isForbidden());
      mvc.perform(get("/api/v1/admin/comment-reports").with(user("user").roles(role)))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/api/v1/admin/comments/{id}/moderate", comment)
                  .with(user("user").roles(role))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"decision\":\"approved\",\"reason\":\"Reviewed\",\"expectedVersion\":0}"))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(comments);
  }

  @Test
  void moderatorCanModerateButOnlyAdministratorMayChangePolicy() throws Exception {
    mvc.perform(
            post("/api/v1/admin/comments/{id}/moderate", comment)
                .with(user("moderator").roles("MODERATOR"))
                .with(csrf())
                .header("Idempotency-Key", "moderate-key-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"rejected\",\"reason\":\"Harassment\",\"expectedVersion\":3}"))
        .andExpect(status().isNoContent());
    verify(comments).moderate(comment, "rejected", "Harassment", actor, 3);
    for (String path :
        List.of(
            "/api/v1/admin/comment-settings",
            "/api/v1/admin/articles/" + article + "/comment-settings")) {
      mvc.perform(
              put(path)
                  .with(user("moderator").roles("MODERATOR"))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"enabled\":true,\"requireApproval\":true,\"editingWindowMinutes\":15,\"reviewSpamThreshold\":0.5,\"rejectSpamThreshold\":0.9,\"reportEscalationThreshold\":3,\"expectedVersion\":-1}"))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void mutationVersionsAndReasonsAreRequiredBeforeExecutingCommands() throws Exception {
    mvc.perform(
            put("/api/v1/comments/{id}", comment)
                .with(user("reader").roles("READER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Edit\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            delete("/api/v1/comments/{id}", comment)
                .with(user("reader").roles("READER"))
                .with(csrf()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/admin/comments/{id}/moderate", comment)
                .with(user("moderator").roles("MODERATOR"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"approved\",\"expectedVersion\":0}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(commands);
  }

  @Test
  void accountDiscoveryUsesThePublicIdentityBoundaryAndDoesNotExposeEmail() throws Exception {
    when(identity.findCommunityUsers("moderator", "Alex", 20))
        .thenReturn(List.of(new IdentityService.CommunityUser(actor, "Alex Reader", false, true)));
    mvc.perform(
            get("/api/v1/admin/commenting-users")
                .param("query", "Alex")
                .with(user("moderator").roles("MODERATOR")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$[0].displayName").value("Alex Reader"))
        .andExpect(jsonPath("$[0].email").doesNotExist());
    mvc.perform(
            get("/api/v1/admin/commenting-users")
                .param("query", "Alex")
                .with(user("reader").roles("READER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void aStaleStaffSessionCannotReplayAMutationAfterItsRoleIsRevoked() throws Exception {
    when(identity.requireAnyRole(eq("former-moderator"), anySet()))
        .thenThrow(new org.springframework.security.access.AccessDeniedException("Role revoked"));
    mvc.perform(
            post("/api/v1/admin/comments/{id}/moderate", comment)
                .with(user("former-moderator").roles("MODERATOR"))
                .with(csrf())
                .header("Idempotency-Key", "previous-command-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"approved\",\"reason\":\"Reviewed\",\"expectedVersion\":0}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(commands);
  }

  @TestConfiguration
  @EnableMethodSecurity
  static class Security {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(
              auth ->
                  auth.requestMatchers(HttpMethod.GET, "/api/v1/articles/**")
                      .permitAll()
                      .anyRequest()
                      .authenticated())
          .httpBasic(basic -> {})
          .build();
    }
  }
}
