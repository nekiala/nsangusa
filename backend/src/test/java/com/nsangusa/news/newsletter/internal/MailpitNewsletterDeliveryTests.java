package com.nsangusa.news.newsletter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "MAILPIT_SMTP_PORT", matches = "\\d+")
class MailpitNewsletterDeliveryTests {
  @Test
  void localProviderDeliversInspectableMultipartMailToConfiguredMailpit() throws Exception {
    String apiBase = System.getenv().getOrDefault("MAILPIT_API_BASE_URL", "http://localhost:8025");
    var sender = new JavaMailSenderImpl();
    sender.setHost(System.getenv().getOrDefault("MAILPIT_SMTP_HOST", "localhost"));
    sender.setPort(Integer.parseInt(System.getenv("MAILPIT_SMTP_PORT")));
    sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
    sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");
    String fixture = "nsangusa-mail-smoke-" + UUID.randomUUID();
    var provider = new JavaMailDeliveryProvider(sender, "editorial@example.test", "local-smtp");
    provider.send(
        fixture,
        "reader@example.test",
        fixture,
        "Local newsletter transport verification",
        "<p>Local newsletter transport verification</p>");

    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    var search =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        apiBase
                            + "/api/v1/search?query="
                            + URLEncoder.encode("subject:" + fixture, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(search.statusCode()).isEqualTo(200);
    var messages = JsonMapper.builder().build().readTree(search.body()).path("messages");
    assertThat(messages.size()).isEqualTo(1);
    String messageId = messages.get(0).path("ID").asString();
    try {
      var raw =
          client.send(
              HttpRequest.newBuilder(URI.create(apiBase + "/api/v1/message/" + messageId + "/raw"))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(raw.statusCode()).isEqualTo(200);
      assertThat(raw.body())
          .contains(
              "editorial@example.test",
              "multipart/alternative",
              "text/plain",
              "text/html",
              "Local newsletter transport verification")
          .doesNotContain("Resend-Idempotency-Key");
    } finally {
      var removed =
          client.send(
              HttpRequest.newBuilder(URI.create(apiBase + "/api/v1/messages"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Content-Type", "application/json")
                  .method(
                      "DELETE",
                      HttpRequest.BodyPublishers.ofString("{\"IDs\":[\"" + messageId + "\"]}"))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertThat(removed.statusCode()).isBetween(200, 299);
    }
  }
}
