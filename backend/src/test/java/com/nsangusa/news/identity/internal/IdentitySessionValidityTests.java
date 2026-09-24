package com.nsangusa.news.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class IdentitySessionValidityTests {
  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void oldSessionsCannotRetainChangedRolesEvenWhenRedisCleanupFails() throws Exception {
    var user =
        UserAdministrationServiceTests.account("reader@example.test", UserAccount.Role.READER);
    var repository = mock(UserAccountRepository.class);
    when(repository.findByEmailIgnoreCase(user.email)).thenReturn(Optional.of(user));
    authenticate(user.email, "ROLE_ADMINISTRATOR");
    var request = new MockHttpServletRequest();
    var session = new MockHttpSession();
    request.setSession(session);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    new IdentitySessionValidityFilter(repository).doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(session.isInvalid()).isTrue();
    assertThat(chain.getRequest()).isNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void passwordResetCutoffRejectsOldSessionsButAllowsFreshAuthentication() throws Exception {
    var user =
        UserAdministrationServiceTests.account("reader@example.test", UserAccount.Role.READER);
    user.authenticationValidAfter = Instant.ofEpochMilli(2000);
    var repository = mock(UserAccountRepository.class);
    when(repository.findByEmailIgnoreCase(user.email)).thenReturn(Optional.of(user));
    var filter = new IdentitySessionValidityFilter(repository);
    for (long timestamp : new long[] {1000, 3000}) {
      authenticate(user.email, "ROLE_READER");
      var request = new MockHttpServletRequest();
      request.getSession().setAttribute(IdentityAuthenticationHandlers.AUTHENTICATED_AT, timestamp);
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, new MockFilterChain());
      assertThat(response.getStatus()).isEqualTo(timestamp < 2000 ? 401 : 200);
    }
  }

  @Test
  void apiLoginRecordsFreshAuthenticationTime() throws Exception {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    IdentityAuthenticationHandlers.apiSuccess().onAuthenticationSuccess(request, response, null);
    assertThat(request.getSession().getAttribute(IdentityAuthenticationHandlers.AUTHENTICATED_AT))
        .isInstanceOf(Long.class);
    assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void passwordResetDuringLoginCannotAuthenticateAnOldPasswordWithANewTimestamp() throws Exception {
    var user =
        UserAdministrationServiceTests.account("reader@example.test", UserAccount.Role.READER);
    var principal =
        new LocalAccountPrincipal(user, AuthorityUtils.createAuthorityList("ROLE_READER"));
    var authentication =
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    user.changePassword("new-hash");
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    IdentityAuthenticationHandlers.apiSuccess()
        .onAuthenticationSuccess(request, response, authentication);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var repository = mock(UserAccountRepository.class);
    when(repository.findByEmailIgnoreCase(user.email)).thenReturn(Optional.of(user));
    new IdentitySessionValidityFilter(repository)
        .doFilter(request, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(401);
  }

  private void authenticate(String email, String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                email, "unused", AuthorityUtils.createAuthorityList(role)));
  }
}
