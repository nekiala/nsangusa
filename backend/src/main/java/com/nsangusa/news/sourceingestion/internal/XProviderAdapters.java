package com.nsangusa.news.sourceingestion.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "news.providers.mode", havingValue = "fake", matchIfMissing = true)
class FakeXSourceProvider implements XSourceProvider {
  @Override
  public FetchResult fetchRecent(String accountId, String sincePostId) {
    return new FetchResult(List.of(), Instant.now().plusSeconds(900), 300, 300);
  }
}

@Component
@ConditionalOnProperty(name = "news.providers.mode", havingValue = "production")
class OfficialXApiSourceProvider implements XSourceProvider {
  private final RestClient client;

  OfficialXApiSourceProvider(
      RestClient.Builder builder,
      @Value("${news.x.api-base-url}") URI baseUrl,
      @Value("${news.x.bearer-token}") String bearerToken) {
    if (!"https".equalsIgnoreCase(baseUrl.getScheme())
        || baseUrl.getHost() == null
        || !(baseUrl.getHost().equals("api.x.com")
            || baseUrl.getHost().equals("api.twitter.com"))) {
      throw new IllegalArgumentException("X API base URL must be an official HTTPS API host");
    }
    if (bearerToken.isBlank()) {
      throw new IllegalStateException("X_BEARER_TOKEN is required in production provider mode");
    }
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    requestFactory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        builder
            .baseUrl(baseUrl.toString())
            .requestFactory(requestFactory)
            .defaultHeader("Authorization", "Bearer " + bearerToken)
            .build();
  }

  @Override
  public FetchResult fetchRecent(String accountId, String sincePostId) {
    var response =
        client
            .get()
            .uri(
                uri ->
                    uri.path("/2/users/{id}/tweets")
                        .queryParam("max_results", 100)
                        .queryParam("tweet.fields", "created_at")
                        .queryParamIfPresent("since_id", java.util.Optional.ofNullable(sincePostId))
                        .build(accountId))
            .retrieve()
            .toEntity(TimelineResponse.class);
    long resetEpoch =
        java.util.Optional.ofNullable(response.getHeaders().getFirst("x-rate-limit-reset"))
            .map(Long::parseLong)
            .orElseGet(() -> Instant.now().plusSeconds(900).getEpochSecond());
    Integer rateLimit = parseIntegerHeader(response.getHeaders().getFirst("x-rate-limit-limit"));
    Integer rateRemaining =
        parseIntegerHeader(response.getHeaders().getFirst("x-rate-limit-remaining"));
    var body = response.getBody();
    List<Post> posts =
        body == null || body.data() == null
            ? List.of()
            : body.data().stream()
                .map(tweet -> new Post(tweet.id(), tweet.text(), tweet.createdAt()))
                .toList();
    return new FetchResult(posts, Instant.ofEpochSecond(resetEpoch), rateLimit, rateRemaining);
  }

  private static Integer parseIntegerHeader(String value) {
    try {
      return value == null ? null : Integer.valueOf(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  record TimelineResponse(List<Tweet> data) {}

  record Tweet(String id, String text, @JsonProperty("created_at") Instant createdAt) {}
}
