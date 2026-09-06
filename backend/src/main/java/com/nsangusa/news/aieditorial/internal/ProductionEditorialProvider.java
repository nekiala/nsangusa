package com.nsangusa.news.aieditorial.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisRequest;
import com.nsangusa.news.aieditorial.EditorialProviders.AnalysisResult;
import com.nsangusa.news.aieditorial.EditorialProviders.ArticleDraftProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.ContentSafetyProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.DraftRequest;
import com.nsangusa.news.aieditorial.EditorialProviders.EditorialAnalysisProvider;
import com.nsangusa.news.aieditorial.EditorialProviders.SafetyResult;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "news.providers.mode", havingValue = "production")
class ProductionEditorialProvider
    implements EditorialAnalysisProvider, ArticleDraftProvider, ContentSafetyProvider {
  private final RestClient client;
  private final ObjectMapper mapper;
  private final Semaphore bulkhead = new Semaphore(8);
  private final String model;
  private final String promptVersion;

  ProductionEditorialProvider(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${news.providers.ai.base-url}") URI baseUrl,
      @Value("${news.providers.ai.api-key}") String apiKey,
      @Value("${news.providers.ai.model}") String model,
      @Value("${news.providers.ai.prompt-version}") String promptVersion,
      @Value("${news.providers.ai.timeout:20s}") Duration timeout) {
    validatePublicHttpsEndpoint(baseUrl);
    if (apiKey.isBlank()) {
      throw new IllegalStateException("AI_API_KEY is required in production provider mode");
    }
    var httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(timeout);
    this.client =
        builder
            .baseUrl(baseUrl.toString())
            .requestFactory(requestFactory)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    this.mapper = mapper;
    this.model = model;
    this.promptVersion = promptVersion;
  }

  @Override
  public AnalysisResult analyze(AnalysisRequest request) {
    return guarded(
        () ->
            postWithRetry(
                "/v1/editorial/analyze",
                Map.of(
                    "model",
                    model,
                    "promptVersion",
                    promptVersion,
                    "systemInstructions",
                    PromptBoundary.SYSTEM_RULES,
                    "sourceData",
                    PromptBoundary.untrustedSourceJson(mapper, request.sourceMaterial()),
                    "sources",
                    request.sources()),
                AnalysisResult.class));
  }

  @Override
  public ArticleDraftGenerated draft(DraftRequest request) {
    return guarded(
        () ->
            postWithRetry(
                "/v1/editorial/draft",
                Map.of(
                    "model", model,
                    "promptVersion", promptVersion,
                    "systemInstructions", PromptBoundary.SYSTEM_RULES,
                    "validatedAnalysis", request),
                ArticleDraftGenerated.class));
  }

  @Override
  public SafetyResult evaluate(String content) {
    return guarded(
        () ->
            postWithRetry(
                "/v1/editorial/safety",
                Map.of(
                    "model",
                    model,
                    "systemInstructions",
                    PromptBoundary.SYSTEM_RULES,
                    "sourceData",
                    PromptBoundary.untrustedSourceJson(mapper, content)),
                SafetyResult.class));
  }

  private <T> T postWithRetry(String path, Object request, Class<T> responseType) {
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= 2; attempt++) {
      try {
        T response = client.post().uri(path).body(request).retrieve().body(responseType);
        if (response == null) {
          throw new IllegalStateException("AI provider returned an empty response");
        }
        return response;
      } catch (org.springframework.web.client.ResourceAccessException
          | org.springframework.web.client.HttpServerErrorException exception) {
        lastFailure = exception;
        if (attempt < 2) {
          try {
            Thread.sleep(Duration.ofMillis(250L * attempt));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI provider call interrupted", interrupted);
          }
        }
      }
    }
    throw new IllegalStateException("AI provider unavailable after bounded retries", lastFailure);
  }

  private <T> T guarded(java.util.concurrent.Callable<T> action) {
    if (!bulkhead.tryAcquire()) {
      throw new IllegalStateException("AI provider bulkhead is full");
    }
    try {
      return action.call();
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("AI provider call failed", exception);
    } finally {
      bulkhead.release();
    }
  }

  static void validatePublicHttpsEndpoint(URI endpoint) {
    if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
      throw new IllegalArgumentException("Production provider endpoint must use HTTPS");
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(endpoint.getHost())) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
          throw new IllegalArgumentException("Provider endpoint resolves to a private address");
        }
      }
    } catch (java.net.UnknownHostException exception) {
      throw new IllegalArgumentException("Provider endpoint cannot be resolved", exception);
    }
  }
}
