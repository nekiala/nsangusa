package com.nsangusa.news.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class RedisRateLimitFilter extends OncePerRequestFilter {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(RedisRateLimitFilter.class);
  private final StringRedisTemplate redis;
  private final int requestsPerMinute;
  private final InternalManagementRequests internalMetrics;

  RedisRateLimitFilter(StringRedisTemplate redis) {
    this(redis, 300);
  }

  RedisRateLimitFilter(StringRedisTemplate redis, int requestsPerMinute) {
    this(redis, requestsPerMinute, new StandardEnvironment());
  }

  @Autowired
  RedisRateLimitFilter(
      StringRedisTemplate redis,
      @Value("${news.identity.requests-per-minute:${IDENTITY_REQUESTS_PER_MINUTE:300}}")
          int requestsPerMinute,
      Environment environment) {
    if (requestsPerMinute < 1 || requestsPerMinute > 10_000) {
      throw new IllegalArgumentException(
          "Identity request limit must be between 1 and 10000 per minute");
    }
    this.redis = redis;
    this.requestsPerMinute = requestsPerMinute;
    this.internalMetrics = new InternalManagementRequests(environment);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return InternalManagementRequests.isHealthProbe(request) || internalMetrics.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String address = request.getRemoteAddr();
    String minute = Long.toString(System.currentTimeMillis() / 60_000);
    boolean identityAction = SetHolder.LIMITED_PATHS.contains(request.getRequestURI());
    String key = "rate:" + (identityAction ? "identity:" : "") + address + ":" + minute;
    try {
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1) {
        redis.expire(key, Duration.ofMinutes(2));
      }
      if (count != null
          && count > (identityAction ? Math.min(20, requestsPerMinute) : requestsPerMinute)) {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        response.getWriter().write("{\"title\":\"Rate limit exceeded\",\"status\":429}");
        return;
      }
    } catch (org.springframework.dao.DataAccessException exception) {
      log.warn("Rate limiting unavailable: {}", exception.getClass().getSimpleName());
      response.setStatus(503);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response.getWriter().write("{\"title\":\"Rate limiting unavailable\",\"status\":503}");
      return;
    }
    chain.doFilter(request, response);
  }

  private static class SetHolder {
    static final java.util.Set<String> LIMITED_PATHS =
        java.util.Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/verification/request",
            "/api/v1/auth/password-reset/request",
            "/api/v1/auth/password-reset/confirm",
            "/api/v1/newsletter/preferences/link");
  }
}
