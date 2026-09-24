package com.nsangusa.news.identity.internal;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

class LocalAccountPrincipal extends User implements IdentityPrincipal {
  private final String accountSecurityStamp;

  LocalAccountPrincipal(UserAccount account, Collection<? extends GrantedAuthority> authorities) {
    super(
        account.email,
        account.passwordHash,
        account.enabled && account.emailVerified && account.localCredentialsEnabled,
        true,
        true,
        !account.isLocked(java.time.Instant.now()),
        authorities);
    accountSecurityStamp = IdentityPrincipal.securityStamp(account);
  }

  @Override
  public String accountSecurityStamp() {
    return accountSecurityStamp;
  }
}
