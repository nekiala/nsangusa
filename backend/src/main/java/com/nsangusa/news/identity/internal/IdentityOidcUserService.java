package com.nsangusa.news.identity.internal;

import java.time.Instant;
import java.util.LinkedHashSet;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
class IdentityOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
  private final OidcUserService delegate = new OidcUserService();
  private final OidcAccountService accounts;

  IdentityOidcUserService(OidcAccountService accounts) {
    this.accounts = accounts;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
    OidcUser oidcUser = delegate.loadUser(request);
    UserAccount account =
        accounts.provision(
            oidcUser.getIssuer().toString(),
            oidcUser.getSubject(),
            oidcUser.getClaims(),
            Instant.now());
    var authorities = new LinkedHashSet<GrantedAuthority>(oidcUser.getAuthorities());
    account.roles.stream()
        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
        .forEach(authorities::add);
    return new AccountOidcPrincipal(account, authorities, oidcUser);
  }

  private static final class AccountOidcPrincipal extends DefaultOidcUser
      implements IdentityPrincipal {
    private final String accountEmail;
    private final String accountSecurityStamp;

    private AccountOidcPrincipal(
        UserAccount account,
        java.util.Collection<? extends GrantedAuthority> authorities,
        OidcUser user) {
      super(authorities, user.getIdToken(), user.getUserInfo(), "email");
      accountEmail = account.email;
      accountSecurityStamp = IdentityPrincipal.securityStamp(account);
    }

    @Override
    public String getName() {
      return accountEmail;
    }

    @Override
    public String accountSecurityStamp() {
      return accountSecurityStamp;
    }
  }
}
