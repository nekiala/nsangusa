package com.nsangusa.news.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class RedisRateLimitFilter extends OncePerRequestFilter {
  private final StringRedisTemplate redis;

  RedisRateLimitFilter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String address = request.getRemoteAddr();
    String minute = Long.toString(System.currentTimeMillis() / 60_000);
    String key = "rate:" + address + ":" + minute;
    try {
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1) {
        redis.expire(key, Duration.ofMinutes(2));
      }
      if (count != null && count > 300) {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        response.getWriter().write("{\"title\":\"Rate limit exceeded\",\"status\":429}");
        return;
      }
    } catch (org.springframework.dao.DataAccessException exception) {
      response.setStatus(503);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response.getWriter().write("{\"title\":\"Rate limiting unavailable\",\"status\":503}");
      return;
    }
    chain.doFilter(request, response);
  }
}
