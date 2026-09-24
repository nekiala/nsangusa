package com.nsangusa.news.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalManagementRequestsTests {
  @Test
  void optInAndAnActuallySeparateLocalListenerAreRequired() {
    var environment =
        new MockEnvironment()
            .withProperty("server.port", "8080")
            .withProperty("management.server.port", "8081");
    var matcher = new InternalManagementRequests(environment);
    var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
    request.setLocalPort(8081);
    assertThat(matcher.matches(request)).isFalse();
    environment.setProperty("news.operations.internal-metrics-enabled", "true");
    assertThat(matcher.matches(request)).isTrue();
    request.setLocalPort(8080);
    request.addHeader("Host", "localhost:8081");
    request.addHeader("X-Forwarded-Port", "8081");
    assertThat(matcher.matches(request)).isFalse();
    environment.setProperty("management.server.port", "8080");
    assertThat(matcher.matches(request)).isFalse();
    environment.setProperty("management.server.port", "8081");
    request.setLocalPort(8081);
    request.setMethod("POST");
    assertThat(matcher.matches(request)).isFalse();
    request.setMethod("GET");
    request.setRequestURI("/actuator/metrics");
    assertThat(matcher.matches(request)).isFalse();
  }

  @Test
  void dynamicallyAllocatedManagementPortUsesItsActualBoundPort() {
    var environment =
        new MockEnvironment()
            .withProperty("server.port", "0")
            .withProperty("management.server.port", "0")
            .withProperty("local.server.port", "33000")
            .withProperty("local.management.port", "33001")
            .withProperty("news.operations.internal-metrics-enabled", "true");
    var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
    request.setLocalPort(33001);
    assertThat(new InternalManagementRequests(environment).matches(request)).isTrue();
    request.setLocalPort(33000);
    assertThat(new InternalManagementRequests(environment).matches(request)).isFalse();
  }

  @Test
  void healthAndInternalScrapingDoNotDependOnTheRedisRateLimiter() throws Exception {
    var redis = mock(StringRedisTemplate.class);
    var environment =
        new MockEnvironment()
            .withProperty("management.server.port", "8081")
            .withProperty("news.operations.internal-metrics-enabled", "true");
    var filter = new RedisRateLimitFilter(redis, 300, environment);
    for (String path :
        java.util.List.of(
            "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus")) {
      var request = new MockHttpServletRequest("GET", path);
      request.setLocalPort(8081);
      var chain = new MockFilterChain();
      filter.doFilter(request, new MockHttpServletResponse(), chain);
      assertThat(chain.getRequest()).isSameAs(request);
    }
    verifyNoInteractions(redis);
  }
}
