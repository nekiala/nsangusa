package com.nsangusa.news.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.RequestMatcher;

final class InternalManagementRequests implements RequestMatcher {
  private final Environment environment;

  InternalManagementRequests(Environment environment) {
    this.environment = environment;
  }

  @Override
  public boolean matches(HttpServletRequest request) {
    if (!"GET".equals(request.getMethod())
        || !"/actuator/prometheus".equals(request.getRequestURI())
        || !environment.getProperty(
            "news.operations.internal-metrics-enabled", Boolean.class, false)) {
      return false;
    }
    Integer configuredManagementPort =
        environment.getProperty("management.server.port", Integer.class);
    if (configuredManagementPort == null) {
      return false;
    }
    int applicationPort =
        environment.getProperty(
            "local.server.port",
            Integer.class,
            environment.getProperty("server.port", Integer.class, 8080));
    int managementPort =
        environment.getProperty("local.management.port", Integer.class, configuredManagementPort);
    // The socket's local port, not Host/forwarded headers, defines the private listener.
    return managementPort > 0
        && managementPort != applicationPort
        && request.getLocalPort() == managementPort;
  }

  static boolean isHealthProbe(HttpServletRequest request) {
    String path = request.getRequestURI();
    return "GET".equals(request.getMethod())
        && ("/actuator/health".equals(path)
            || "/actuator/health/liveness".equals(path)
            || "/actuator/health/readiness".equals(path));
  }
}
