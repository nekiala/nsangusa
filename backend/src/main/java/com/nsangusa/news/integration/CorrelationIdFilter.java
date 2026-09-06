package com.nsangusa.news.integration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = request.getHeader("X-Correlation-ID");
    if (correlationId == null || correlationId.length() > 100) {
      correlationId = UUID.randomUUID().toString();
    }
    try (var ignored = MDC.putCloseable("correlationId", correlationId)) {
      response.setHeader("X-Correlation-ID", correlationId);
      chain.doFilter(request, response);
    }
  }
}
