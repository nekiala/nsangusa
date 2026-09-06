package com.nsangusa.news.media.internal;

import com.nsangusa.news.media.ImageGenerationProvider;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "news.providers.mode", havingValue = "production")
class ProductionImageGenerationProvider implements ImageGenerationProvider {
  private final RestClient client;
  private final String model;

  ProductionImageGenerationProvider(
      RestClient.Builder builder,
      @Value("${news.providers.image.base-url}") URI baseUrl,
      @Value("${news.providers.image.api-key}") String apiKey,
      @Value("${news.providers.image.model}") String model) {
    validatePublicHttpsEndpoint(baseUrl);
    if (apiKey.isBlank()) {
      throw new IllegalStateException("IMAGE_API_KEY is required in production provider mode");
    }
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    requestFactory.setReadTimeout(Duration.ofSeconds(45));
    this.client =
        builder
            .baseUrl(baseUrl.toString())
            .requestFactory(requestFactory)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    this.model = model;
  }

  @Override
  public GeneratedImage generate(String prompt, String altText) {
    var response =
        client
            .post()
            .uri("/v1/images/generations")
            .body(
                Map.of(
                    "model",
                    model,
                    "prompt",
                    prompt,
                    "size",
                    "1536x1024",
                    "response_format",
                    "b64_json"))
            .retrieve()
            .body(ImageResponse.class);
    if (response == null || response.data() == null || response.data().isEmpty()) {
      throw new IllegalStateException("Image provider returned no image");
    }
    return new GeneratedImage(
        Base64.getDecoder().decode(response.data().getFirst().b64Json()),
        "image/png",
        "configured-http-provider",
        model,
        altText);
  }

  record ImageResponse(java.util.List<ImageData> data) {}

  record ImageData(@com.fasterxml.jackson.annotation.JsonProperty("b64_json") String b64Json) {}

  private static void validatePublicHttpsEndpoint(URI endpoint) {
    if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
      throw new IllegalArgumentException("Production image endpoint must use HTTPS");
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(endpoint.getHost())) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
          throw new IllegalArgumentException("Image endpoint resolves to a private address");
        }
      }
    } catch (java.net.UnknownHostException exception) {
      throw new IllegalArgumentException("Image endpoint cannot be resolved", exception);
    }
  }
}
