package com.nsangusa.news.identity.internal;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

@Component
class AuthenticationAuditListener {
  private final LoginSecurityService loginSecurity;

  AuthenticationAuditListener(LoginSecurityService loginSecurity) {
    this.loginSecurity = loginSecurity;
  }

  @EventListener
  void failed(AuthenticationFailureBadCredentialsEvent event) {
    loginSecurity.failed(event.getAuthentication().getName());
  }

  @EventListener
  void succeeded(AuthenticationSuccessEvent event) {
    if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken) {
      loginSecurity.succeeded(event.getAuthentication().getName(), "password");
    }
  }
}
