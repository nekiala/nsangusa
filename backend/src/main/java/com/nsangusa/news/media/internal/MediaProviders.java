package com.nsangusa.news.media.internal;

import com.nsangusa.news.media.ImageGenerationProvider;
import com.nsangusa.news.media.ObjectStorage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@Profile({"local", "test", "staging"})
@ConditionalOnExpression(
    "'${news.providers.mode:disabled}' == 'fake' and !${news.providers.image.live-enabled:false}")
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
@Profile({"local", "test"})
@ConditionalOnProperty(name = "news.storage.provider", havingValue = "local")
class LocalObjectStorage implements ObjectStorage {
  private final Path root;

  LocalObjectStorage(@Value("${news.storage.local-root:.local/media}") String localRoot) {
    if (localRoot == null || localRoot.isBlank()) {
      throw new IllegalArgumentException("Local media root must be configured");
    }
    root = Path.of(localRoot).toAbsolutePath().normalize();
    if (root.getParent() == null) {
      throw new IllegalArgumentException("The filesystem root cannot be used for local media");
    }
  }

  @Override
  public void put(String objectKey, byte[] bytes, String contentType) {
    StoredImages.validateImage(objectKey, bytes, contentType);
    try {
      var target = target(objectKey);
      Files.createDirectories(target.getParent());
      Files.write(
          target,
          bytes,
          LinkOption.NOFOLLOW_LINKS,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Unable to write local object", exception);
    }
  }

  @Override
  public Optional<StoredObject> read(String objectKey) {
    var target = target(objectKey);
    String contentType = StoredImages.contentType(objectKey);
    try (var stream = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.of(
          new StoredObject(StoredImages.readBounded(stream, StoredImages.MAX_BYTES), contentType));
    } catch (java.nio.file.NoSuchFileException exception) {
      return Optional.empty();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Unable to read local object", exception);
    }
  }

  @Override
  public void delete(String objectKey) {
    try {
      Files.deleteIfExists(target(objectKey));
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Unable to delete local object", exception);
    }
  }

  private Path target(String objectKey) {
    StoredImages.validateKey(objectKey);
    Path target = root.resolve(objectKey).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("Invalid object key");
    }
    for (Path path = target; path != null; path = path.getParent()) {
      if (Files.isSymbolicLink(path)) {
        throw new IllegalArgumentException("Symbolic links are not supported for local media");
      }
    }
    return target;
  }
}

@Component
@ConditionalOnProperty(name = "news.storage.provider", havingValue = "s3", matchIfMissing = true)
class S3CompatibleObjectStorage implements ObjectStorage, AutoCloseable {
  private final S3Client client;
  private final String bucket;
  private final String serverSideEncryption;

  @Autowired
  S3CompatibleObjectStorage(
      @Value("${news.storage.endpoint}") URI endpoint,
      @Value("${news.storage.region}") String region,
      @Value("${news.storage.bucket}") String bucket,
      @Value("${news.storage.access-key}") String accessKey,
      @Value("${news.storage.secret-key}") String secretKey,
      @Value("${news.storage.server-side-encryption:AES256}") String serverSideEncryption,
      @Value("${news.storage.ssl-bundle:}") String sslBundle,
      ObjectProvider<SslBundles> sslBundles) {
    this.bucket = bucket;
    this.serverSideEncryption = encryption(serverSideEncryption);
    this.client =
        S3Client.builder()
            .httpClient(httpClient(sslBundle, sslBundles))
            .endpointOverride(endpoint)
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .overrideConfiguration(
                configuration ->
                    configuration
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
            .forcePathStyle(true)
            .build();
  }

  S3CompatibleObjectStorage(S3Client client, String bucket) {
    this.client = client;
    this.bucket = bucket;
    this.serverSideEncryption = "AES256";
  }

  @Override
  public void put(String objectKey, byte[] bytes, String contentType) {
    StoredImages.validateImage(objectKey, bytes, contentType);
    client.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .contentType(contentType)
            .serverSideEncryption(serverSideEncryption)
            .build(),
        RequestBody.fromBytes(bytes));
  }

  @Override
  public Optional<StoredObject> read(String objectKey) {
    StoredImages.validateKey(objectKey);
    try (var stream =
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())) {
      Long contentLength = stream.response().contentLength();
      if (contentLength != null && contentLength > StoredImages.MAX_BYTES) {
        stream.abort();
        throw new IllegalArgumentException("Stored image exceeds size limits");
      }
      byte[] bytes;
      try {
        bytes = StoredImages.readBounded(stream, StoredImages.MAX_BYTES);
      } catch (RuntimeException | java.io.IOException exception) {
        stream.abort();
        throw exception;
      }
      return Optional.of(new StoredObject(bytes, stream.response().contentType()));
    } catch (NoSuchKeyException exception) {
      return Optional.empty();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Unable to read stored image", exception);
    }
  }

  @Override
  public void delete(String objectKey) {
    StoredImages.validateKey(objectKey);
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

  static SdkHttpClient httpClient(String sslBundle, ObjectProvider<SslBundles> sslBundles) {
    Apache5HttpClient.Builder builder = Apache5HttpClient.builder();
    if (!sslBundle.isBlank()) {
      var trustManagers =
          sslBundles.getObject().getBundle(sslBundle).getManagers().getTrustManagers();
      builder.tlsTrustManagersProvider(() -> trustManagers);
    }
    return builder.build();
  }

  private static String encryption(String value) {
    if (!"AES256".equals(value) && !"none".equals(value)) {
      throw new IllegalArgumentException("Unsupported storage encryption configuration");
    }
    return "none".equals(value) ? null : value;
  }
}
