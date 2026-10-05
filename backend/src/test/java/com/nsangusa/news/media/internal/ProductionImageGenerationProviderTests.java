package com.nsangusa.news.media.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nsangusa.news.media.ImageGenerationProvider;
import java.net.URI;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ProductionImageGenerationProviderTests {
  private static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
  private final RestClient.Builder builder =
      RestClient.builder().baseUrl("https://images.example.test");
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

  @Test
  void liveGateSelectsTheLiveImageApiWithoutProductionProviderMode() {
    var runner =
        new ApplicationContextRunner()
            .withInitializer(
                context ->
                    context
                        .getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(JsonMapper.class, JsonMapper::new)
            .withBean(ImageProviderCallExecutor.class, () -> mock(ImageProviderCallExecutor.class))
            .withUserConfiguration(
                FakeImageGenerationProvider.class, ProductionImageGenerationProvider.class)
            .withPropertyValues(
                "spring.profiles.active=local",
                "news.providers.mode=fake",
                "news.providers.image.base-url=https://api.openai.com");

    runner.run(
        context ->
            assertThat(context.getBean(ImageGenerationProvider.class))
                .isInstanceOf(FakeImageGenerationProvider.class));
    runner
        .withPropertyValues(
            "news.providers.image.live-enabled=true", "news.providers.image.api-key=test-key")
        .run(
            context ->
                assertThat(context.getBean(ImageGenerationProvider.class))
                    .isInstanceOf(ProductionImageGenerationProvider.class));
    runner
        .withPropertyValues(
            "news.providers.image.live-enabled=true", "news.providers.image.api-key=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void gptImagesUseTheOfficialBase64ProtocolAndRecordProviderMetadata() {
    server
        .expect(requestTo("https://images.example.test/v1/images/generations"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
            {"model":"gpt-image-1","n":1,"size":"1536x1024","output_format":"png","moderation":"auto"}
            """))
        .andExpect(content().string(not(containsString("response_format"))))
        .andRespond(
            withSuccess(response(PNG), MediaType.APPLICATION_JSON)
                .header("x-request-id", "req-editorial-image"));

    var image =
        provider(10_000, 1000).generate("Community library opening", "A library illustration");

    assertThat(image.bytes()).containsExactly(PNG);
    assertThat(image.contentType()).isEqualTo("image/png");
    assertThat(image.provider()).isEqualTo("openai");
    assertThat(image.model()).isEqualTo("gpt-image-1");
    assertThat(image.renderedPrompt()).contains("non-photorealistic", "Community library opening");
    assertThat(image.promptVersion()).isEqualTo("editorial-illustration-v1");
    assertThat(image.providerRequestId()).isEqualTo("req-editorial-image");
    server.verify();
  }

  @Test
  void untrustedImageUrlsAreNeverDownloadedAsAFallback() {
    server
        .expect(requestTo("https://images.example.test/v1/images/generations"))
        .andRespond(
            withSuccess(
                """
            {"data":[{"url":"http://127.0.0.1/private"}]}
            """,
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> provider(10_000, 1000).generate("Library", "Illustration"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("base64");
    server.verify();
  }

  @Test
  void encodedAndDecodedPayloadsHaveIndependentLimits() {
    server
        .expect(requestTo("https://images.example.test/v1/images/generations"))
        .andRespond(withSuccess(response(PNG), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> provider(10_000, 8).generate("Library", "Illustration"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Decoded image");
    server.verify();
  }

  @Test
  void chunkedResponseCannotBypassTheResponseLimit() {
    server
        .expect(requestTo("https://images.example.test/v1/images/generations"))
        .andRespond(withSuccess(" ".repeat(1025), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> provider(1024, 1000).generate("Library", "Illustration"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size limits");
    server.verify();
  }

  @Test
  void textDisguisedAsPngIsRejected() {
    server
        .expect(requestTo("https://images.example.test/v1/images/generations"))
        .andRespond(
            withSuccess(
                response("<svg>unsafe</svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> provider(10_000, 1000).generate("Library", "Illustration"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PNG content");
    server.verify();
  }

  @Test
  void providerAuthenticationErrorsAreNotRetriedOrReplaced() {
    server
        .expect(requestTo("https://images.example.test/v1/images/generations"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    assertThatThrownBy(() -> provider(10_000, 1000).generate("Library", "Illustration"))
        .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    server.verify();
  }

  @Test
  void productionEndpointRejectsLocalAddressesAndAmbiguousUrlComponents() {
    for (String endpoint :
        new String[] {
          "http://api.openai.com",
          "https://127.0.0.1",
          "https://[::1]",
          "https://[fc00::1]",
          "https://user:secret@images.example.test",
          "https://images.example.test?credential=secret",
          "https://images.example.test/#fragment",
          "https://images.example.test:8443",
          "https://images.example.test/unrelated"
        }) {
      assertThatThrownBy(
              () ->
                  ProductionImageGenerationProvider.validatePublicHttpsEndpoint(
                      URI.create(endpoint)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void unresolvableEndpointIsARetryableNetworkFailure() {
    assertThatThrownBy(
            () ->
                ProductionImageGenerationProvider.validatePublicHttpsEndpoint(
                    URI.create("https://unresolvable.invalid")))
        .isInstanceOf(ResourceAccessException.class);
  }

  private ProductionImageGenerationProvider provider(int responseLimit, int imageLimit) {
    return new ProductionImageGenerationProvider(
        builder.build(),
        JsonMapper.builder().build(),
        ImageProviderCallExecutorTests.executor(3, 5),
        "gpt-image-1",
        responseLimit,
        imageLimit);
  }

  private static String response(byte[] bytes) {
    return "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}]}";
  }
}
