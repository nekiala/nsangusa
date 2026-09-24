package com.nsangusa.news.newsletter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NewsletterWorkflowStorageFixtureTests {
  @Test
  void workflowStoragePreservesSvgMetadataAndDoesNotExposeMutableStoredBytes() {
    var storage = new EditorialPublicationWorkflowIntegrationTests.InMemoryObjectStorage();
    byte[] source = {1, 2, 3};
    storage.put("articles/story/hero.svg", source, "image/svg+xml");
    source[0] = 9;

    var content = storage.read("articles/story/hero.svg").orElseThrow();

    assertThat(content.contentType()).isEqualTo("image/svg+xml");
    assertThat(content.bytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
    content.bytes()[0] = 8;
    assertThat(storage.read("articles/story/hero.svg").orElseThrow().bytes())
        .containsExactly((byte) 1, (byte) 2, (byte) 3);
  }

  @Test
  void deletedAndUnknownFixtureObjectsAreAbsent() {
    var storage = new EditorialPublicationWorkflowIntegrationTests.InMemoryObjectStorage();
    assertThat(storage.read("articles/story/hero.png")).isEmpty();
    storage.put("articles/story/hero.png", new byte[] {1}, "image/png");
    storage.delete("articles/story/hero.png");
    assertThat(storage.read("articles/story/hero.png")).isEmpty();
    assertThat(storage.keys()).isEmpty();
  }
}
