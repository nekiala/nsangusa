package com.nsangusa.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.integration.EventTopics;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "news.local.seed=false",
      "spring.docker.compose.enabled=false",
      "management.tracing.enabled=false",
      "news.story.quiet-period=PT0S",
      "news.story.dispatch-interval=200",
      "news.outbox.poll-interval=100"
    })
@ActiveProfiles("local")
@Testcontainers
class EditorialBrokerIntegrationTests {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");
  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.10.0").withExposedPorts(6379);

  @Container
  static final GenericContainer<?> STORAGE =
      new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z")
          .withEnv("MINIO_ROOT_USER", "minioadmin")
          .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
          .withCommand("server", "/data")
          .withExposedPorts(9000)
          .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

  @Container
  static final GenericContainer<?> MAIL =
      new GenericContainer<>("axllent/mailpit:v1.27.8")
          .withExposedPorts(1025, 8025)
          .waitingFor(Wait.forHttp("/readyz").forPort(8025));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.mail.host", MAIL::getHost);
    registry.add("spring.mail.port", () -> MAIL.getMappedPort(1025));
    registry.add("news.storage.endpoint", EditorialBrokerIntegrationTests::storageUrl);
    registry.add("news.storage.bucket", () -> "news-media");
    registry.add("news.storage.access-key", () -> "minioadmin");
    registry.add("news.storage.secret-key", () -> "minioadmin");
  }

  @BeforeAll
  static void createBucket() {
    try (var client =
        S3Client.builder()
            .endpointOverride(URI.create(storageUrl()))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("minioadmin", "minioadmin")))
            .build()) {
      client.createBucket(request -> request.bucket("news-media"));
    }
  }

  private static String storageUrl() {
    return "http://" + STORAGE.getHost() + ":" + STORAGE.getMappedPort(9000);
  }

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<Object, Object> kafka;

  @Test
  void authenticatesReviewsAndPublishesAcrossKafkaWithOneConfirmedRecipient() throws Exception {
    var editor = new ApiClient("editor@example.test:editor-demo-password");
    var administrator = new ApiClient("admin@example.test:administrator-demo-password");
    var reader = new ApiClient(null);
    String recipient = "slice-" + UUID.randomUUID() + "@example.test";
    var subscribed =
        reader.mutate(
            "/api/v1/newsletter/subscriptions",
            Map.of(
                "email", recipient, "consentSource", "integration-test", "frequency", "immediate"));
    String subscriptionId = subscribed.path("subscriptionId").asText();
    assertThat(subscriptionId).isNotBlank();
    var confirmation = await(() -> messages(recipient), value -> value.size() == 1);
    String confirmationId = confirmation.get(0).path("ID").asText();
    var message = mail("/api/v1/message/" + confirmationId);
    var token =
        Pattern.compile("[?&]token=([A-Za-z0-9_-]+)").matcher(message.path("Text").asText());
    assertThat(token.find()).isTrue();
    reader.mutate(
        "/api/v1/newsletter/confirm?id=" + subscriptionId + "&token=" + token.group(1), null);

    String handle = "Slice" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String postId = Long.toString(System.currentTimeMillis());
    var account =
        administrator.mutate(
            "/api/v1/admin/x-accounts",
            Map.of(
                "accountId",
                postId,
                "handle",
                handle,
                "displayName",
                "Synthetic library source",
                "topics",
                new String[] {"culture"},
                "relevanceThreshold",
                0));
    var discovered =
        administrator.mutate(
            "/api/v1/admin/x-accounts/" + account.path("id").asText() + "/simulate-post",
            Map.of(
                "postId",
                postId,
                "canonicalUrl",
                "https://x.com/" + handle + "/status/" + postId,
                "permittedText",
                "Synthetic test: the library announces an evening reading programme. #culture",
                "publishedAt",
                Instant.now().toString()));
    String sourceId = discovered.path("id").asText();

    var queue =
        await(
            () -> editor.get("/api/v1/admin/articles?size=100").path("items"),
            value -> findArticle(value, sourceId) != null);
    var article = findArticle(queue, sourceId);
    assertThat(article).isNotNull();
    String articleId = article.path("id").asText();
    assertThat(article.path("state").asText()).isEqualTo("DRAFTING");
    var attempts =
        editor.get(
            "/api/v1/admin/ai-requests?storyCandidateId="
                + article.path("storyCandidateId").asText());
    assertThat(attempts.toString()).contains("completed", "claims", "promptVersion");
    assertThat(reader.raw("GET", "/api/v1/admin/articles", null).statusCode()).isEqualTo(401);

    var images =
        await(
            () -> editor.get("/api/v1/admin/articles/" + articleId + "/images"),
            value -> !value.isEmpty());
    String generationId = images.get(0).path("id").asText();
    var preview =
        editor.raw(
            "GET",
            "/api/v1/admin/image-generations/" + generationId + "/content?variant=thumbnail",
            null);
    assertThat(preview.statusCode()).isEqualTo(200);
    assertThat(preview.headers().firstValue("content-type").orElse("")).startsWith("image/");
    long previousVersion =
        editor.get("/api/v1/admin/articles/" + articleId).path("version").asLong();
    editor.mutate("/api/v1/admin/image-generations/" + generationId + "/approve", null);
    var reviewedImage =
        await(
            () -> editor.get("/api/v1/admin/articles/" + articleId),
            value -> "AWAITING_REVIEW".equals(value.path("state").asText()));
    assertThat(reviewedImage.path("version").asLong()).isGreaterThan(previousVersion);
    assertThat(
            editor
                .raw(
                    "POST",
                    "/api/v1/admin/articles/"
                        + articleId
                        + "/approve?expectedVersion="
                        + previousVersion,
                    null)
                .statusCode())
        .isEqualTo(409);
    editor.mutate(
        "/api/v1/admin/articles/"
            + articleId
            + "/approve?expectedVersion="
            + reviewedImage.path("version").asLong(),
        null);
    editor.mutate("/api/v1/admin/articles/" + articleId + "/publish", null);

    var published = editor.get("/api/v1/admin/articles/" + articleId);
    String slug = published.path("slug").asText();
    assertThat(reader.get("/api/v1/articles/" + slug).path("sources").size()).isEqualTo(1);
    assertThat(reader.raw("GET", "/api/v1/articles/" + slug + "/image", null).statusCode())
        .isEqualTo(200);
    await(() -> messages(recipient), value -> value.size() == 2);

    String event =
        jdbc.queryForObject(
            "select envelope_json from outbox_events where aggregate_id = ? and event_type = 'ArticlePublished'",
            String.class,
            UUID.fromString(articleId));
    kafka.send(EventTopics.PUBLICATION, articleId, event).get();
    var duplicate = kafka.send(EventTopics.PUBLICATION, articleId, event).get().getRecordMetadata();
    awaitCommitted("newsletter-request-v1", duplicate);
    assertThat(messages(recipient).size()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from newsletter_deliveries where article_id = ? and status = 'delivered'",
                Integer.class,
                UUID.fromString(articleId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from article_revisions where article_id = ? and ai_generation_result is not null",
                Integer.class,
                UUID.fromString(articleId)))
        .isEqualTo(1);
    verifyCorrectionsAndVisibility(editor, reader, articleId, slug, recipient);
    administrator.mutate(
        "/api/v1/admin/source-posts/" + sourceId + "/exclude",
        Map.of("reason", "Synthetic source retention verification"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from article_revisions where article_id = ? and ai_generation_result is not null",
                Integer.class,
                UUID.fromString(articleId)))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from article_revisions where article_id = ? and snapshot is not null",
                Integer.class,
                UUID.fromString(articleId)))
        .isZero();
  }

  private void verifyCorrectionsAndVisibility(
      ApiClient editor, ApiClient reader, String articleId, String slug, String recipient)
      throws Exception {
    String path = "/api/v1/admin/articles/" + articleId;
    var original = editor.get(path);
    var publicResponse = reader.raw("GET", "/api/v1/articles/" + slug, null);
    assertThat(publicResponse.headers().firstValue("cache-control").orElse(""))
        .contains("no-store");
    assertThat(reader.get("/api/v1/search?q=library").path("items").size()).isEqualTo(1);
    var correction =
        Map.of(
            "expectedVersion",
            original.path("version").asLong(),
            "note",
            "Corrected the library opening time.");
    String key = UUID.randomUUID().toString();
    editor.json(editor.raw("POST", path + "/corrections", correction, key));
    editor.json(editor.raw("POST", path + "/corrections", correction, key));
    assertThat(
            editor
                .raw(
                    "POST",
                    path + "/corrections",
                    Map.of(
                        "expectedVersion",
                        original.path("version").asLong(),
                        "note",
                        "A different request."),
                    key)
                .statusCode())
        .isEqualTo(409);
    assertThat(reader.raw("GET", "/api/v1/articles/" + slug, null).statusCode()).isEqualTo(404);
    assertThat(reader.raw("GET", "/api/v1/articles/" + slug + "/image", null).statusCode())
        .isEqualTo(404);
    assertThat(reader.get("/api/v1/search?q=library").path("items").size()).isZero();
    assertThat(editor.raw("POST", path + "/publish", null).statusCode()).isEqualTo(409);

    var withdrawn = editor.get(path);
    var edit = mapper.createObjectNode();
    for (String field :
        new String[] {
          "headline",
          "summary",
          "body",
          "editorialContext",
          "seoTitle",
          "seoDescription",
          "topic",
          "tags",
          "sources",
          "commentsEnabled"
        }) {
      edit.set(field, withdrawn.path(field));
    }
    edit.put("headline", "Corrected library report");
    edit.put("seoTitle", "Corrected library report");
    edit.put("slugSuggestion", "ignored-new-canonical");
    editor.json(
        editor.raw(
            "PUT",
            path + "?expectedVersion=" + withdrawn.path("version").asLong(),
            edit,
            UUID.randomUUID().toString()));
    editor.mutate(path + "/approve", null);
    editor.mutate(path + "/publish", null);
    var corrected = reader.get("/api/v1/articles/" + slug);
    assertThat(corrected.path("headline").asText()).isEqualTo("Corrected library report");
    assertThat(corrected.path("publishedAt")).isEqualTo(original.path("publishedAt"));
    assertThat(corrected.path("correctionNote").asText()).contains("opening time");
    var history = editor.get(path + "/revisions?size=100");
    assertThat(history.path("items").get(0).path("snapshot").path("correctionNote").asText())
        .contains("opening time");
    int last = history.path("items").get(0).path("revisionNumber").asInt();
    var comparison = editor.get(path + "/revisions/compare?from=1&to=" + last);
    assertThat(comparison.path("changedFields").toString()).contains("headline", "correctionNote");

    editor.mutate(path + "/unpublish", null);
    assertThat(reader.raw("GET", "/api/v1/articles/" + slug, null).statusCode()).isEqualTo(404);
    assertThat(reader.get("/api/v1/search?q=library").path("items").size()).isZero();
    String withdrawal =
        jdbc.queryForObject(
            "select envelope_json from outbox_events where aggregate_id = ? and event_type = 'ArticleUnpublished' order by created_at desc limit 1",
            String.class,
            UUID.fromString(articleId));
    editor.mutate(path + "/restore", null);
    assertThat(reader.get("/api/v1/search?q=library").path("items").size()).isEqualTo(1);
    var lateWithdrawal =
        kafka.send(EventTopics.PUBLICATION, articleId, withdrawal).get().getRecordMetadata();
    awaitCommitted("search-index-v1", lateWithdrawal);
    assertThat(reader.get("/api/v1/search?q=library").path("items").size()).isEqualTo(1);
    String restored =
        jdbc.queryForObject(
            "select envelope_json from outbox_events where aggregate_id = ? and event_type = 'ArticlePublished' order by created_at desc limit 1",
            String.class,
            UUID.fromString(articleId));
    var delivered =
        kafka.send(EventTopics.PUBLICATION, articleId, restored).get().getRecordMetadata();
    awaitCommitted("newsletter-request-v1", delivered);
    assertThat(messages(recipient).size()).isEqualTo(2);
    assertThat(reader.get("/api/v1/articles/" + slug).path("publishedAt"))
        .isEqualTo(original.path("publishedAt"));
  }

  private void awaitCommitted(String group, RecordMetadata record) throws Exception {
    var partition = new TopicPartition(record.topic(), record.partition());
    try (var admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      Instant deadline = Instant.now().plusSeconds(60);
      do {
        var offsets =
            admin
                .listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS);
        if (offsets.containsKey(partition) && offsets.get(partition).offset() > record.offset()) {
          return;
        }
        Thread.sleep(100);
      } while (Instant.now().isBefore(deadline));
    }
    throw new AssertionError("The newsletter consumer did not commit the duplicate event");
  }

  private JsonNode findArticle(JsonNode queue, String sourceId) {
    for (var article : queue) {
      for (var source : article.path("sources")) {
        if (sourceId.equals(source.path("sourcePostId").asText())) return article;
      }
    }
    return null;
  }

  private JsonNode messages(String recipient) {
    var selected = mapper.createArrayNode();
    for (var message : mail("/api/v1/messages").path("messages")) {
      for (var to : message.path("To")) {
        if (recipient.equals(to.path("Address").asText())) {
          selected.add(message);
          break;
        }
      }
    }
    return selected;
  }

  private JsonNode mail(String path) {
    try {
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create(
                              "http://" + MAIL.getHost() + ":" + MAIL.getMappedPort(8025) + path))
                      .timeout(Duration.ofSeconds(10))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      return mapper.readTree(response.body());
    } catch (java.io.IOException | InterruptedException exception) {
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("Cannot inspect local test email", exception);
    }
  }

  private JsonNode await(Supplier<JsonNode> read, Predicate<JsonNode> ready)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(120);
    do {
      var value = read.get();
      if (ready.test(value)) return value;
      Thread.sleep(250);
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("The durable workflow did not reach its expected state");
  }

  private final class ApiClient {
    private final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    private final String credentials;

    private ApiClient(String credentials) {
      this.credentials = credentials;
    }

    JsonNode get(String path) {
      return json(raw("GET", path, null));
    }

    JsonNode mutate(String path, Object body) {
      return json(raw("POST", path, body));
    }

    HttpResponse<byte[]> raw(String method, String path, Object body) {
      return raw(method, path, body, null);
    }

    HttpResponse<byte[]> raw(String method, String path, Object body, String key) {
      try {
        var request =
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (key != null) request.header("Idempotency-Key", key);
        if (credentials != null)
          request.header(
              "Authorization",
              "Basic "
                  + Base64.getEncoder()
                      .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        if (!"GET".equals(method)) {
          var csrf = get("/api/v1/auth/csrf");
          request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        }
        request.header("Content-Type", "application/json");
        request.method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      } catch (java.io.IOException | InterruptedException exception) {
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        throw new IllegalStateException("Test API request failed", exception);
      }
    }

    JsonNode json(HttpResponse<byte[]> response) {
      assertThat(response.statusCode())
          .as(new String(response.body(), StandardCharsets.UTF_8))
          .isBetween(200, 299);
      try {
        return response.body().length == 0
            ? mapper.createObjectNode()
            : mapper.readTree(response.body());
      } catch (java.io.IOException exception) {
        throw new IllegalStateException("Invalid API JSON", exception);
      }
    }
  }
}
