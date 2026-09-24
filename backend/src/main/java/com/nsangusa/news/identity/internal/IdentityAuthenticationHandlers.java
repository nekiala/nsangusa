package com.nsangusa.news.identity.internal;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

final class IdentityAuthenticationHandlers {
  static final String AUTHENTICATED_AT = "identity.authenticatedAt";
  static final String SECURITY_STAMP = "identity.securityStamp";

  private IdentityAuthenticationHandlers() {}

  static AuthenticationFailureHandler apiFailure() {
    return (request, response, exception) -> {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response.getWriter().write("{\"title\":\"Authentication failed\",\"status\":401}");
    };
  }

  static AuthenticationSuccessHandler apiSuccess() {
    return (request, response, authentication) -> {
      request.getSession().setAttribute(AUTHENTICATED_AT, System.currentTimeMillis());
      captureStamp(request, authentication);
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    };
  }

  static AuthenticationSuccessHandler oidcSuccess(
      LoginSecurityService loginSecurity, String successUrl) {
    var delegate = new SavedRequestAwareAuthenticationSuccessHandler();
    delegate.setDefaultTargetUrl(successUrl);
    delegate.setAlwaysUseDefaultTargetUrl(true);
    return new AuthenticationSuccessHandler() {
      @Override
      public void onAuthenticationSuccess(
          HttpServletRequest request, HttpServletResponse response, Authentication authentication)
          throws IOException, ServletException {
        loginSecurity.succeeded(authentication.getName(), "oidc");
        request.getSession().setAttribute(AUTHENTICATED_AT, System.currentTimeMillis());
        captureStamp(request, authentication);
        delegate.onAuthenticationSuccess(request, response, authentication);
      }
    };
  }

  private static void captureStamp(HttpServletRequest request, Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof IdentityPrincipal principal) {
      request.getSession().setAttribute(SECURITY_STAMP, principal.accountSecurityStamp());
    }
  }
}
