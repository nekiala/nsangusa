package com.nsangusa.news.sourceingestion.internal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Profile({"local", "test", "staging"})
@ConditionalOnExpression(
    "'${news.providers.mode:disabled}' == 'fake' and !${news.x.live-enabled:false}")
class FakeXSourceProvider implements XSourceProvider {
  @Override
  public AccountLookup lookupAccount(String handle) {
    String normalized = handle.replaceFirst("^@", "");
    var id =
        java.util.UUID.nameUUIDFromBytes(
            normalized
                .toLowerCase(java.util.Locale.ROOT)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new AccountLookup(
        Long.toUnsignedString(id.getMostSignificantBits()),
        normalized,
        "Local demonstration: " + normalized,
        true);
  }

  @Override
  public FetchResult fetchRecent(String accountId, String sincePostId) {
    return new FetchResult(List.of(), Instant.now().plusSeconds(900), 300, 300);
  }

  @Override
  public LookupResult lookupPosts(List<String> postIds) {
    return new LookupResult(
        postIds.stream()
            .map(
                postId ->
                    new LookupPost(
                        postId,
                        LookupState.UNKNOWN,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        "fake provider has no remote state"))
            .toList());
  }
}

// news.x.live-enabled selects the official API on its own, leaving AI, image and mail providers
// in their configured mode.
@Component
@ConditionalOnExpression(
    "'${news.providers.mode:disabled}' == 'production' or ${news.x.live-enabled:false}")
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
      throw new IllegalStateException("X_BEARER_TOKEN is required to use the official X API");
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
  public AccountLookup lookupAccount(String handle) {
    var response =
        client
            .get()
            .uri("/2/users/by/username/{username}", handle.replaceFirst("^@", ""))
            .retrieve()
            .body(UserResponse.class);
    if (response == null
        || response.data() == null
        || response.data().id() == null
        || !response.data().id().matches("\\d{1,30}")
        || response.data().username() == null
        || response.data().name() == null) {
      throw new IllegalStateException("Official X API returned no valid account");
    }
    return new AccountLookup(
        response.data().id(), response.data().username(), response.data().name(), false);
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
                        .queryParam(
                            "tweet.fields",
                            "created_at,conversation_id,edit_history_tweet_ids,referenced_tweets")
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
            : body.data().stream().map(OfficialXApiSourceProvider::toPost).toList();
    return new FetchResult(posts, Instant.ofEpochSecond(resetEpoch), rateLimit, rateRemaining);
  }

  @Override
  public LookupResult lookupPosts(List<String> postIds) {
    if (postIds.isEmpty()) {
      return new LookupResult(List.of());
    }
    var response =
        client
            .get()
            .uri(
                uri ->
                    uri.path("/2/tweets")
                        .queryParam("ids", String.join(",", postIds))
                        .queryParam(
                            "tweet.fields",
                            "created_at,conversation_id,edit_history_tweet_ids,referenced_tweets")
                        .build())
            .retrieve()
            .body(LookupResponse.class);
    Map<String, Tweet> postsById =
        response == null || response.data() == null
            ? Map.of()
            : response.data().stream()
                .collect(Collectors.toUnmodifiableMap(Tweet::id, Function.identity()));
    Map<String, ApiProblem> problemsById =
        response == null || response.errors() == null
            ? Map.of()
            : response.errors().stream()
                .filter(problem -> problem.identifier() != null)
                .collect(
                    Collectors.toMap(
                        ApiProblem::identifier, Function.identity(), (first, ignored) -> first));
    return new LookupResult(
        postIds.stream()
            .map(
                postId -> {
                  Tweet tweet = postsById.get(postId);
                  if (tweet != null) {
                    Post post = toPost(tweet);
                    return new LookupPost(
                        post.postId(),
                        LookupState.AVAILABLE,
                        post.text(),
                        post.publishedAt(),
                        post.conversationId(),
                        post.editHistoryPostIds(),
                        post.references(),
                        null);
                  }
                  ApiProblem problem = problemsById.get(postId);
                  LookupState state =
                      problem != null && problem.isNotFound()
                          ? LookupState.DELETED
                          : LookupState.UNKNOWN;
                  return new LookupPost(
                      postId,
                      state,
                      null,
                      null,
                      null,
                      List.of(),
                      List.of(),
                      problem == null ? "X lookup omitted the post" : problem.detail());
                })
            .toList());
  }

  private static Post toPost(Tweet tweet) {
    List<String> history =
        tweet.editHistoryPostIds() == null || tweet.editHistoryPostIds().isEmpty()
            ? List.of(tweet.id())
            : List.copyOf(tweet.editHistoryPostIds());
    List<Reference> references =
        tweet.referencedTweets() == null
            ? List.of()
            : tweet.referencedTweets().stream()
                .map(reference -> new Reference(reference.id(), reference.type()))
                .toList();
    return new Post(
        tweet.id(), tweet.text(), tweet.createdAt(), tweet.conversationId(), history, references);
  }

  private static Integer parseIntegerHeader(String value) {
    try {
      return value == null ? null : Integer.valueOf(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  record TimelineResponse(List<Tweet> data) {}

  record UserResponse(User data) {}

  record User(String id, String username, String name) {}

  record LookupResponse(List<Tweet> data, List<ApiProblem> errors) {}

  record Tweet(
      String id,
      String text,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("conversation_id") String conversationId,
      @JsonProperty("edit_history_tweet_ids") @JsonAlias("edit_history_post_ids")
          List<String> editHistoryPostIds,
      @JsonProperty("referenced_tweets") List<ReferencedTweet> referencedTweets) {}

  record ReferencedTweet(String id, String type) {}

  record ApiProblem(
      String type,
      String title,
      String detail,
      @JsonProperty("resource_id") String resourceId,
      String value) {
    String identifier() {
      return resourceId == null ? value : resourceId;
    }

    boolean isNotFound() {
      return type != null && type.endsWith("/resource-not-found");
    }
  }
}
