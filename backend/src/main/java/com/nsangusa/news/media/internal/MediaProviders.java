package com.nsangusa.news.media.internal;

import com.nsangusa.news.media.ImageGenerationProvider;
import com.nsangusa.news.media.ObjectStorage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@ConditionalOnProperty(name = "news.providers.mode", havingValue = "fake", matchIfMissing = true)
class FakeImageGenerationProvider implements ImageGenerationProvider {
  @Override
  public GeneratedImage generate(String prompt, String altText) {
    String safePrompt = prompt.replaceAll("[<>&\"']", " ").replaceAll("\\s+", " ").trim();
    String svg =
        """
        <svg xmlns="http://www.w3.org/2000/svg" width="1600" height="900" viewBox="0 0 1600 900">
          <rect width="1600" height="900" fill="#f7f2e8"/>
          <path d="M80 720 C360 180 700 780 980 260 S1420 180 1520 650" fill="none" stroke="#101827" stroke-width="34"/>
          <circle cx="430" cy="360" r="130" fill="none" stroke="#101827" stroke-width="24"/>
          <path d="M1180 210 l180 310 h-360z" fill="#101827" opacity=".82"/>
          <metadata>%s</metadata>
        </svg>
        """
            .formatted(safePrompt);
    return new GeneratedImage(
        svg.getBytes(StandardCharsets.UTF_8),
        "image/svg+xml",
        "fake",
        "deterministic-illustration-v1",
        altText);
  }
}

@Component
@ConditionalOnProperty(name = "news.providers.mode", havingValue = "fake", matchIfMissing = true)
class LocalObjectStorage implements ObjectStorage {
  private final java.nio.file.Path root =
      java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "nsangusa-media");

  @Override
  public void put(String objectKey, byte[] bytes, String contentType) {
    try {
      var target = root.resolve(objectKey).normalize();
      if (!target.startsWith(root)) {
        throw new IllegalArgumentException("Invalid object key");
      }
      java.nio.file.Files.createDirectories(target.getParent());
      java.nio.file.Files.write(target, bytes);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Unable to write local object", exception);
    }
  }

  @Override
  public void delete(String objectKey) {
    try {
      var target = root.resolve(objectKey).normalize();
      if (!target.startsWith(root)) {
        throw new IllegalArgumentException("Invalid object key");
      }
      java.nio.file.Files.deleteIfExists(target);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Unable to delete local object", exception);
    }
  }
}

@Component
@ConditionalOnProperty(name = "news.providers.mode", havingValue = "production")
class S3CompatibleObjectStorage implements ObjectStorage, AutoCloseable {
  private final S3Client client;
  private final String bucket;

  S3CompatibleObjectStorage(
      @Value("${news.storage.endpoint}") URI endpoint,
      @Value("${news.storage.region}") String region,
      @Value("${news.storage.bucket}") String bucket,
      @Value("${news.storage.access-key}") String accessKey,
      @Value("${news.storage.secret-key}") String secretKey) {
    this.bucket = bucket;
    this.client =
        S3Client.builder()
            .endpointOverride(endpoint)
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true)
            .build();
  }

  @Override
  public void put(String objectKey, byte[] bytes, String contentType) {
    client.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .contentType(contentType)
            .serverSideEncryption("AES256")
            .build(),
        RequestBody.fromBytes(bytes));
  }

  @Override
  public void delete(String objectKey) {
    client.deleteObject(
        software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .build());
  }

  @Override
  public void close() {
    client.close();
  }
}
