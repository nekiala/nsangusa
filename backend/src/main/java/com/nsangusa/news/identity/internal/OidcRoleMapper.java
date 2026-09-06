package com.nsangusa.news.identity.internal;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class OidcRoleMapper {
  private final IdentityProperties properties;

  OidcRoleMapper(IdentityProperties properties) {
    this.properties = properties;
  }

  Set<UserAccount.Role> map(Map<String, Object> claims) {
    var roles = new LinkedHashSet<UserAccount.Role>();
    roles.add(UserAccount.Role.READER);
    Object claim = claims.get(properties.getOidc().getRolesClaim());
    if (claim instanceof Collection<?> values) {
      values.forEach(value -> addMappedRole(roles, String.valueOf(value)));
    } else if (claim != null) {
      addMappedRole(roles, String.valueOf(claim));
    }
    return Set.copyOf(roles);
  }

  private void addMappedRole(Set<UserAccount.Role> roles, String externalRole) {
    String configured = properties.getOidc().getRoleMapping().get(externalRole);
    if (configured == null) {
      configured = properties.getOidc().getRoleMapping().get(externalRole.toLowerCase(Locale.ROOT));
    }
    if (configured == null) {
      return;
    }
    try {
      roles.add(UserAccount.Role.valueOf(configured.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException ignored) {
      // Unknown configured roles are deliberately ignored rather than granting authority.
    }
  }
}
