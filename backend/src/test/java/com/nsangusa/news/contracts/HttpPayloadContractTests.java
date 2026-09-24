package com.nsangusa.news.contracts;

import static com.nsangusa.news.contracts.ContractSchemas.invalid;
import static com.nsangusa.news.contracts.ContractSchemas.valid;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsangusa.news.articles.ArticleService;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import com.nsangusa.news.identity.IdentityService;
import com.nsangusa.news.media.MediaService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HttpPayloadContractTests {
  private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID USER_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");
  private final IdentityService identity = mock(IdentityService.class);
  private final ArticleService articles = mock(ArticleService.class);
  private final MediaService media = mock(MediaService.class);
  private final DurableCommandExecutor commands = mock(DurableCommandExecutor.class);
  private JsonNode fixture;
  private MockMvc mvc;
  private Object administration;

  @BeforeEach
  void setUpRealControllersWithMockedApplicationPorts() throws Exception {
    fixture = ContractSchemas.fixture("http.json");
    Object adminUser =
        ContractSchemas.JSON.treeToValue(
            fixture.required("adminUser"),
            Class.forName(
                "com.nsangusa.news.identity.internal.UserAdministrationService$UserView"));
    Object page =
        ContractRuntime.instance(
            "com.nsangusa.news.identity.internal.UserAdministrationService$UserPage",
            List.of(adminUser),
            0,
            20,
            1L);
    administration =
        mock(
            Class.forName("com.nsangusa.news.identity.internal.UserAdministrationService"),
            invocation ->
                switch (invocation.getMethod().getName()) {
                  case "list" -> page;
                  case "get" -> adminUser;
                  default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                });
    Object authController =
        ContractRuntime.instance("com.nsangusa.news.identity.internal.AuthController", identity);
    Object adminController =
        ContractRuntime.instance(
            "com.nsangusa.news.identity.internal.UserAdministrationController", administration);
    Object articleController =
        ContractRuntime.instance(
            "com.nsangusa.news.articles.internal.ArticleController", articles, media, commands);
    mvc =
        MockMvcBuilders.standaloneSetup(authController, adminController, articleController)
            .setControllerAdvice(
                ContractRuntime.instance("com.nsangusa.news.integration.ApiExceptionHandler"))
            .build();
    when(commands.execute(any(), anyString(), anyString(), any(), any()))
        .thenAnswer(invocation -> invocation.<Supplier<String>>getArgument(4).get());
    when(articles.createManual(any(), any())).thenReturn(ARTICLE_ID);
  }

  @Test
  void identityProfileExportSessionsAndAdminPagesSerializeAgainstOperationSchemas()
      throws Exception {
    var profile =
        ContractSchemas.JSON.treeToValue(
            fixture.required("profile"), IdentityService.UserProfile.class);
    when(identity.profile("reader@example.test")).thenReturn(profile);
    when(identity.exportData("reader@example.test"))
        .thenReturn(new IdentityService.AccountDataExport(NOW, profile, List.of()));
    when(identity.sessions(eq("reader@example.test"), isNull()))
        .thenReturn(
            List.of(
                new IdentityService.UserSession(
                    "opaque-session", NOW, NOW, NOW.plusSeconds(60), false)));
    for (String path :
        List.of("/api/v1/auth/me", "/api/v1/auth/me/export", "/api/v1/auth/sessions")) {
      response(get(path), path, "get", 200);
    }
    response(get("/api/v1/admin/users"), "/api/v1/admin/users", "get", 200);
    response(get("/api/v1/admin/users/" + USER_ID), "/api/v1/admin/users/{id}", "get", 200);

    ObjectNode subscribed = fixture.required("profile").deepCopy();
    subscribed.set(
        "newsletter",
        ContractSchemas.JSON.valueToTree(
            new IdentityService.NewsletterPreference(USER_ID, "confirmed", "weekly")));
    subscribed.put("lastLoginAt", NOW.toString());
    profile = ContractSchemas.JSON.treeToValue(subscribed, IdentityService.UserProfile.class);
    when(identity.profile("reader@example.test")).thenReturn(profile);
    response(get("/api/v1/auth/me"), "/api/v1/auth/me", "get", 200);
  }

  @Test
  void identityAndRoleCommandsAreValidatedAndBoundByRealMvc() throws Exception {
    request(
        post("/api/v1/auth/register"),
        "/api/v1/auth/register",
        "post",
        fixture.required("registration"),
        202);
    verify(identity).register("reader@example.test", "fixture-password-123", "Synthetic reader");
    when(identity.updateProfile(eq("reader@example.test"), any()))
        .thenReturn(
            ContractSchemas.JSON.treeToValue(
                fixture.required("profile"), IdentityService.UserProfile.class));
    request(
        patch("/api/v1/auth/me"),
        "/api/v1/auth/me",
        "patch",
        fixture.required("profileUpdate"),
        200);
    verify(identity)
        .updateProfile(
            "reader@example.test",
            new IdentityService.ProfileUpdate("Updated reader", "weekly", 0));
    request(
        put("/api/v1/admin/users/" + USER_ID + "/roles"),
        "/api/v1/admin/users/{id}/roles",
        "put",
        fixture.required("roleChange"),
        204);
    assertTrue(
        mockingDetails(administration).getInvocations().stream()
            .anyMatch(invocation -> invocation.getMethod().getName().equals("changeRoles")));

    ObjectNode missingVersion = fixture.required("profileUpdate").deepCopy();
    missingVersion.remove("expectedVersion");
    invalid(ContractSchemas.request("/api/v1/auth/me", "patch"), missingVersion);
    problem(
        patch("/api/v1/auth/me").content(missingVersion.toString()),
        "/api/v1/auth/me",
        "patch",
        400);
    ObjectNode emptyRoles = fixture.required("roleChange").deepCopy();
    emptyRoles.putArray("roles");
    invalid(ContractSchemas.request("/api/v1/admin/users/{id}/roles", "put"), emptyRoles);
    problem(
        put("/api/v1/admin/users/" + USER_ID + "/roles").content(emptyRoles.toString()),
        "/api/v1/admin/users/{id}/roles",
        "put",
        400);
  }

  @Test
  void bodyOnlyNullableAndStructuredOnlyArticleCommandsRemainSupported() throws Exception {
    for (int variant = 0; variant < 4; variant++) {
      ObjectNode command = fixture.required("articleCommand").deepCopy();
      if (variant == 1) {
        command.putNull("content");
      } else if (variant >= 2) {
        command.set("content", fixture.required("content"));
        if (variant == 2) {
          command.remove("body");
        } else {
          command.put("body", "This old body is not authoritative.");
        }
      }
      request(post("/api/v1/admin/articles"), "/api/v1/admin/articles", "post", command, 201);
    }
    var captured = ArgumentCaptor.forClass(ArticleService.ManualArticleCommand.class);
    verify(articles, times(4)).createManual(captured.capture(), any());
    assertNull(captured.getAllValues().get(0).content());
    assertNull(captured.getAllValues().get(1).content());
    assertNull(captured.getAllValues().get(2).body());
    var structured = captured.getAllValues().get(3).content();
    assertEquals(
        "Synthetic heading\n\n"
            + "Synthetic paragraph.\n\n"
            + "Synthetic quotation.\n\n"
            + "First\n"
            + "Second\n\n"
            + "One\n"
            + "Two\n\n"
            + "Source (https://example.test/posts/1001)",
        structured.plainText());
    assertNotEquals(captured.getAllValues().get(3).body(), structured.plainText());
    JsonNode serialized = ContractRuntime.eventMapper().valueToTree(structured);
    valid(ContractSchemas.component("ArticleContent"), serialized);
    assertFalse(serialized.at("/blocks/0").has("url"));
    assertFalse(serialized.at("/blocks/0").has("items"));
    assertFalse(serialized.at("/blocks/3").has("text"));
  }

  @Test
  void actualArticleAndHistoricalRevisionDtosPreserveOptionalContentAndFallbackFlag()
      throws Exception {
    for (boolean structured : List.of(false, true)) {
      ObjectNode article = fixture.required("article").deepCopy();
      if (structured) {
        article.set("content", fixture.required("content"));
      }
      var view = ContractSchemas.JSON.treeToValue(article, ArticleService.ArticleView.class);
      when(articles.getPublishedBySlug("synthetic-headline")).thenReturn(view);
      JsonNode json =
          response(
              get("/api/v1/articles/synthetic-headline"), "/api/v1/articles/{slug}", "get", 200);
      assertFalse(json.required("generatedImage").asBoolean(), "Fallback is not AI-generated");
      assertEquals(
          structured, !json.path("content").isNull() && !json.path("content").isMissingNode());
      assertFalse(json.has("passwordHash"));

      ObjectNode snapshot = article.deepCopy();
      snapshot.remove(List.of("id", "version", "storyCandidateId"));
      snapshot.put("humanReviewRequired", true);
      var revisionSnapshot =
          ContractSchemas.JSON.treeToValue(snapshot, ArticleService.RevisionSnapshot.class);
      var revision =
          new ArticleService.RevisionView(
              ARTICLE_ID,
              1,
              "fixture",
              USER_ID,
              NOW,
              view.headline(),
              view.summary(),
              view.body(),
              revisionSnapshot,
              null,
              !structured);
      when(articles.revisions(ARTICLE_ID, 0, 20))
          .thenReturn(new ArticleService.RevisionPage(List.of(revision), 0, 20, 1));
      response(
          get("/api/v1/admin/articles/" + ARTICLE_ID + "/revisions"),
          "/api/v1/admin/articles/{id}/revisions",
          "get",
          200);
    }
  }

  @Test
  void fallbackCommandsAndConflictErrorsMatchDocumentedPayloads() throws Exception {
    var article =
        ContractSchemas.JSON.treeToValue(
            fixture.required("article"), ArticleService.ArticleView.class);
    when(articles.getLocked(ARTICLE_ID)).thenReturn(article);
    when(media.requestFallback(eq(ARTICLE_ID), anyString(), anyString(), any()))
        .thenReturn(USER_ID);
    String template = "/api/v1/admin/articles/{articleId}/images/fallback";
    String path = "/api/v1/admin/articles/" + ARTICLE_ID + "/images/fallback";
    request(post(path), template, "post", fixture.required("fallbackRequest"), 202);
    ObjectNode stale = fixture.required("fallbackRequest").deepCopy();
    stale.put("expectedVersion", 3);
    valid(ContractSchemas.request(template, "post"), stale);
    problem(post(path).content(stale.toString()), template, "post", 409);
    ObjectNode invalidRequest = fixture.required("fallbackRequest").deepCopy();
    invalidRequest.remove("expectedVersion");
    invalid(ContractSchemas.request(template, "post"), invalidRequest);
    problem(post(path).content(invalidRequest.toString()), template, "post", 400);
  }

  @Test
  void actualExceptionAdviceProducesDocumentedValidationConflictAndNotFoundProblems()
      throws Exception {
    String template = "/api/v1/articles/{slug}";
    for (var sample :
        List.of(
            Map.entry(400, new IllegalArgumentException("Invalid article selector")),
            Map.entry(409, new IllegalStateException("Article is not published")),
            Map.entry(409, new OptimisticLockingFailureException("Changed")),
            Map.entry(
                404,
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Article not found")))) {
      when(articles.getPublishedBySlug("missing")).thenThrow(sample.getValue());
      problem(get("/api/v1/articles/missing"), template, "get", sample.getKey());
      reset(articles);
    }
  }

  private JsonNode request(
      MockHttpServletRequestBuilder request,
      String template,
      String method,
      JsonNode json,
      int status)
      throws Exception {
    valid(ContractSchemas.request(template, method), json);
    return response(request.content(json.toString()), template, method, status);
  }

  private JsonNode response(
      MockHttpServletRequestBuilder request, String template, String method, int status)
      throws Exception {
    MvcResult result =
        mvc.perform(
                request
                    .principal(() -> "reader@example.test")
                    .header("Idempotency-Key", "compatibility-fixture")
                    .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is(status))
            .andReturn();
    String body = result.getResponse().getContentAsString();
    if (status == 204) {
      assertEquals("", body);
      return ContractSchemas.JSON.nullNode();
    }
    String mediaType = MediaType.parseMediaType(result.getResponse().getContentType()).toString();
    JsonNode json = ContractSchemas.JSON.readTree(body);
    valid(ContractSchemas.response(template, method, status, mediaType), json);
    return json;
  }

  private void problem(
      MockHttpServletRequestBuilder request, String template, String method, int status)
      throws Exception {
    JsonNode json = response(request, template, method, status);
    assertEquals(status, json.required("status").asInt());
    assertTrue(json.required("type").asText().endsWith("/" + status));
    assertFalse(json.required("instance").asText().isEmpty());
    assertTrue(json.has("traceId"));
    Instant.parse(json.required("timestamp").asText());
  }
}
