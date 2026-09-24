package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpenAiHttpTransportTests {
  @Test
  void readsRealHttpAndBoundsChunkedBodiesBeforeFullBuffering() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/responses",
        exchange -> {
          byte[] content = "x".repeat(4096).getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, 0);
          try (var stream = exchange.getResponseBody()) {
            stream.write(content);
          }
        });
    server.start();
    try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"))
              .GET()
              .build();
      var response = OpenAiHttpTransport.exchange(client, request, 8192, Duration.ofSeconds(2));
      assertThat(response.body()).hasSize(4096);
      assertThatThrownBy(
              () -> OpenAiHttpTransport.exchange(client, request, 1024, Duration.ofSeconds(2)))
          .isInstanceOf(AiProviderException.class)
          .hasMessageContaining("response_too_large");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void deadlineIncludesResponseBodyNotJustHeaders() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/slow",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (var stream = exchange.getResponseBody()) {
            stream.write('a');
            stream.flush();
            try {
              Thread.sleep(500);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
          }
        });
    server.start();
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"))
              .GET()
              .build();
      assertThatThrownBy(
              () -> OpenAiHttpTransport.exchange(client, request, 1024, Duration.ofMillis(100)))
          .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
          .hasMessageContaining("deadline");
    } finally {
      server.stop(0);
    }
  }
}
