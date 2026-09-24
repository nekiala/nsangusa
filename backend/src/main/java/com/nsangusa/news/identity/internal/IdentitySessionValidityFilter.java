package com.nsangusa.news.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class IdentitySessionValidityFilter extends OncePerRequestFilter {
  private final UserAccountRepository users;

  IdentitySessionValidityFilter(UserAccountRepository users) {
    this.users = users;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    var session = request.getSession(false);
    if (session != null
        && authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)) {
      var account = users.findByEmailIgnoreCase(authentication.getName());
      Object timestamp = session.getAttribute(IdentityAuthenticationHandlers.AUTHENTICATED_AT);
      long authenticatedAt = timestamp instanceof Long value ? value : session.getCreationTime();
      Object stamp = session.getAttribute(IdentityAuthenticationHandlers.SECURITY_STAMP);
      var granted =
          authentication.getAuthorities().stream()
              .map(authority -> authority.getAuthority())
              .filter(authority -> authority.startsWith("ROLE_"))
              .collect(Collectors.toSet());
      boolean valid =
          account
              .filter(
                  user ->
                      user.enabled
                          && user.emailVerified
                          && user.deletedAt == null
                          && (stamp instanceof String
                              ? stamp.equals(IdentityPrincipal.securityStamp(user))
                              : user.authenticationValidAfter == null
                                  || user.authenticationValidAfter.toEpochMilli() < authenticatedAt)
                          && user.roles.stream()
                              .map(role -> "ROLE_" + role.name())
                              .collect(Collectors.toSet())
                              .equals(granted))
              .isPresent();
      if (!valid) {
        SecurityContextHolder.clearContext();
        session.invalidate();
        response.setStatus(401);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response
            .getWriter()
            .write(
                "{\"title\":\"Sign in required\",\"status\":401,\"detail\":\"Your account or permissions changed. Sign in again.\"}");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
