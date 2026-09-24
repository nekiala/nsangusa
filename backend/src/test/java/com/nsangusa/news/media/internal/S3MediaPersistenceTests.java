package com.nsangusa.news.media.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "S3_SMOKE_ENDPOINT",
    matches = "http://(localhost|127\\.0\\.0\\.1):\\d+")
class S3MediaPersistenceTests {
  @Test
  void configuredComposeS3PersistsContentAcrossClientRecreation() {
    String key = "articles/media-smoke-" + UUID.randomUUID() + "/hero.svg";
    byte[] bytes =
        """
        <svg xmlns="http://www.w3.org/2000/svg" width="1600" height="900"></svg>
        """
            .getBytes(StandardCharsets.UTF_8);
    try (var storage = storage()) {
      storage.put(key, bytes, "image/svg+xml");
    }
    try (var storage = storage()) {
      try {
        var content = storage.read(key).orElseThrow();
        assertThat(content.bytes()).containsExactly(bytes);
        assertThat(content.contentType()).isEqualTo("image/svg+xml");
      } finally {
        storage.delete(key);
      }
      assertThat(storage.read(key)).isEmpty();
    }
  }

  private S3CompatibleObjectStorage storage() {
    return new S3CompatibleObjectStorage(
        URI.create(System.getenv("S3_SMOKE_ENDPOINT")),
        System.getenv().getOrDefault("S3_REGION", "us-east-1"),
        System.getenv("S3_BUCKET"),
        System.getenv("S3_ACCESS_KEY"),
        System.getenv("S3_SECRET_KEY"),
        "none");
  }
}
