package com.nsangusa.news.media.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import javax.net.ssl.TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

class ObjectStorageTests {
  private Path root;

  @BeforeEach
  void createStorageRoot() throws Exception {
    root = Path.of("build", "media-storage-tests", UUID.randomUUID().toString());
    Files.createDirectories(root);
  }

  @AfterEach
  void removeStorageRoot() throws Exception {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  @Test
  void localContentSurvivesStorageRecreationAndCanBeDeleted() {
    byte[] bytes = {1, 2, 3};
    String key = "articles/story/hero.png";
    new LocalObjectStorage(root.toString()).put(key, bytes, "image/png");
    var storage = new LocalObjectStorage(root.toString());

    var stored = storage.read(key).orElseThrow();

    assertThat(stored.bytes()).containsExactly(bytes);
    assertThat(stored.contentType()).isEqualTo("image/png");
    storage.delete(key);
    assertThat(storage.read(key)).isEmpty();
  }

  @Test
  void localStorageRequiresADedicatedConfiguredRoot() {
    assertThatThrownBy(() -> new LocalObjectStorage(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LocalObjectStorage("/"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bothStorageAdaptersRejectPathsAndUrlsBeforeIo() {
    var client = mock(S3Client.class);
    var local = new LocalObjectStorage(root.toString());
    var s3 = new S3CompatibleObjectStorage(client, "media");
    for (String key :
        new String[] {
          "../image.png", "articles/../../image.png", "/image.png", "https://host/image.png",
          "articles//image.png", "articles/./image.png", "articles\\image.png", "image.png?key=x"
        }) {
      assertThatThrownBy(() -> local.read(key)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> local.put(key, new byte[] {1}, "image/png"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> local.delete(key)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> s3.read(key)).isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(client);
  }

  @Test
  void localStorageRejectsSymlinkReadsWritesAndDeletes() throws Exception {
    Files.write(root.resolve("original.png"), new byte[] {1});
    Files.createSymbolicLink(root.resolve("linked.png"), Path.of("original.png"));
    var storage = new LocalObjectStorage(root.toString());

    assertThatThrownBy(() -> storage.read("linked.png"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.put("linked.png", new byte[] {2}, "image/png"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.delete("linked.png"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Files.readAllBytes(root.resolve("original.png"))).containsExactly((byte) 1);
  }

  @Test
  void s3RetrievesOnlyTheRequestedKeyAndPreservesStoredContentType() {
    var client = mock(S3Client.class);
    var request = GetObjectRequest.builder().bucket("media").key("articles/a/hero.png").build();
    var response = GetObjectResponse.builder().contentLength(3L).contentType("image/png").build();
    when(client.getObject(request))
        .thenReturn(
            new ResponseInputStream<>(
                response,
                AbortableInputStream.create(new ByteArrayInputStream(new byte[] {4, 5, 6}))));

    var result = new S3CompatibleObjectStorage(client, "media").read("articles/a/hero.png");

    assertThat(result.orElseThrow().bytes()).containsExactly((byte) 4, (byte) 5, (byte) 6);
    assertThat(result.orElseThrow().contentType()).isEqualTo("image/png");
    verify(client).getObject(request);
  }

  @Test
  void s3MissingContentDoesNotBecomeAnIllustration() {
    var client = mock(S3Client.class);
    when(client.getObject(any(GetObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().build());

    assertThat(new S3CompatibleObjectStorage(client, "media").read("articles/a/hero.png"))
        .isEmpty();
  }

  @Test
  void s3RejectsOversizedContentBeforeReadingTheBody() throws Exception {
    var client = mock(S3Client.class);
    var body = mock(java.io.InputStream.class);
    var response =
        GetObjectResponse.builder()
            .contentLength((long) StoredImages.MAX_BYTES + 1)
            .contentType("image/png")
            .build();
    when(client.getObject(any(GetObjectRequest.class)))
        .thenReturn(new ResponseInputStream<>(response, AbortableInputStream.create(body)));

    assertThatThrownBy(
            () -> new S3CompatibleObjectStorage(client, "media").read("articles/a/hero.png"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size limits");
    verify(body, org.mockito.Mockito.never())
        .read(
            any(byte[].class),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void responseLimitIsEnforcedEvenWithoutAContentLength() {
    assertThatThrownBy(() -> StoredImages.readBounded(new ByteArrayInputStream(new byte[33]), 32))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size limits");
  }

  @Test
  void s3HttpClientUsesDefaultTrustWithoutAnSslBundle() {
    @SuppressWarnings("unchecked")
    ObjectProvider<SslBundles> bundles = mock(ObjectProvider.class);
    try (var client = S3CompatibleObjectStorage.httpClient("", bundles)) {
      assertThat(client).isNotNull();
    }
    verifyNoInteractions(bundles);
  }

  @Test
  void s3HttpClientRejectsAnUnknownSslBundle() {
    @SuppressWarnings("unchecked")
    ObjectProvider<SslBundles> bundles = mock(ObjectProvider.class);
    when(bundles.getObject()).thenReturn(new DefaultSslBundleRegistry());
    assertThatThrownBy(() -> S3CompatibleObjectStorage.httpClient("internal", bundles))
        .isInstanceOf(NoSuchSslBundleException.class);
  }

  @Test
  void s3HttpClientUsesTheConfiguredSslBundle() {
    @SuppressWarnings("unchecked")
    ObjectProvider<SslBundles> bundles = mock(ObjectProvider.class);
    SslBundle bundle = mock(SslBundle.class);
    SslManagerBundle managers = mock(SslManagerBundle.class);
    when(managers.getTrustManagers()).thenReturn(new TrustManager[0]);
    when(bundle.getManagers()).thenReturn(managers);
    when(bundles.getObject()).thenReturn(new DefaultSslBundleRegistry("internal", bundle));
    try (var client = S3CompatibleObjectStorage.httpClient("internal", bundles)) {
      assertThat(client).isNotNull();
    }
    verify(managers).getTrustManagers();
  }
}
