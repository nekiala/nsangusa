package com.nsangusa.news.identity.internal;

interface IdentityPrincipal {
  String accountSecurityStamp();

  static String securityStamp(UserAccount account) {
    return account.authenticationValidAfter == null
        ? "initial"
        : account.authenticationValidAfter.toString();
  }
}
