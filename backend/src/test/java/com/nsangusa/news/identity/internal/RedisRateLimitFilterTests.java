package com.nsangusa.news.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RedisRateLimitFilterTests {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  @Test
  void submissionsEditsAndReportsAllRetainTheGlobalRateLimit() throws Exception {
    when(redis.opsForValue()).thenReturn(values);
    when(values.increment(anyString())).thenReturn(301L);
    var filter = new RedisRateLimitFilter(redis);
    for (var request :
        List.of(
            new MockHttpServletRequest("POST", "/api/v1/articles/article-id/comments"),
            new MockHttpServletRequest("PUT", "/api/v1/comments/comment-id"),
            new MockHttpServletRequest("POST", "/api/v1/comments/comment-id/reports"))) {
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();
      filter.doFilter(request, response, chain);
      assertThat(response.getStatus()).isEqualTo(429);
      assertThat(response.getHeader("Retry-After")).isEqualTo("60");
      assertThat(chain.getRequest()).isNull();
    }
  }

  @Test
  void identityTokenActionsUseTheStricterSharedBucket() throws Exception {
    when(redis.opsForValue()).thenReturn(values);
    when(values.increment(anyString())).thenReturn(21L);
    var filter = new RedisRateLimitFilter(redis);
    for (String path :
        List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/verification/request",
            "/api/v1/auth/password-reset/request",
            "/api/v1/auth/password-reset/confirm",
            "/api/v1/newsletter/preferences/link")) {
      var response = new MockHttpServletResponse();
      filter.doFilter(new MockHttpServletRequest("POST", path), response, new MockFilterChain());
      assertThat(response.getStatus()).isEqualTo(429);
    }
  }

  @Test
  void unavailableRateLimitStorageFailsClosedForCommentEdits() throws Exception {
    when(redis.opsForValue()).thenReturn(values);
    when(values.increment(anyString()))
        .thenThrow(new RedisConnectionFailureException("Unavailable"));
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    new RedisRateLimitFilter(redis)
        .doFilter(
            new MockHttpServletRequest("PUT", "/api/v1/comments/comment-id"), response, chain);
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void aLargerFiniteQuotaAllowsValidationTrafficButStillRejectsExcess() throws Exception {
    when(redis.opsForValue()).thenReturn(values);
    when(values.increment(anyString())).thenReturn(301L, 1201L);
    var filter = new RedisRateLimitFilter(redis, 1200);
    var allowed = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/articles"), allowed, new MockFilterChain());
    assertThat(allowed.getStatus()).isEqualTo(200);
    var rejected = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/articles"), rejected, new MockFilterChain());
    assertThat(rejected.getStatus()).isEqualTo(429);
    assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
  }

  @Test
  void configurationCannotDisableProtectionOrRemoveTheTighterIdentityQuota() throws Exception {
    for (int invalid : new int[] {-1, 0, 10_001, Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> new RedisRateLimitFilter(redis, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    when(redis.opsForValue()).thenReturn(values);
    when(values.increment(anyString())).thenReturn(21L);
    var response = new MockHttpServletResponse();
    new RedisRateLimitFilter(redis, 1200)
        .doFilter(
            new MockHttpServletRequest("POST", "/api/v1/auth/password-reset/request"),
            response,
            new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(429);
  }

  @Test
  void springConfigurationSupportsTheEnvironmentAliasAndRejectsInvalidLimits() {
    var runner =
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean(StringRedisTemplate.class, () -> redis)
            .withUserConfiguration(RedisRateLimitFilter.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.increment(anyString())).thenReturn(301L);
    runner
        .withPropertyValues("IDENTITY_REQUESTS_PER_MINUTE=1200")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var response = new MockHttpServletResponse();
              context
                  .getBean(RedisRateLimitFilter.class)
                  .doFilter(
                      new MockHttpServletRequest("GET", "/api/v1/articles"),
                      response,
                      new MockFilterChain());
              assertThat(response.getStatus()).isEqualTo(200);
            });
    runner
        .withPropertyValues("news.identity.requests-per-minute=0")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues(
            "news.identity.requests-per-minute=300", "IDENTITY_REQUESTS_PER_MINUTE=1200")
        .run(
            context -> {
              var response = new MockHttpServletResponse();
              context
                  .getBean(RedisRateLimitFilter.class)
                  .doFilter(
                      new MockHttpServletRequest("GET", "/api/v1/articles"),
                      response,
                      new MockFilterChain());
              assertThat(response.getStatus()).isEqualTo(429);
            });
  }
}
