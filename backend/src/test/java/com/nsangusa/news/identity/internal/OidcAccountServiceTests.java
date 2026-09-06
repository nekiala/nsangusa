package com.nsangusa.news.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nsangusa.news.audit.AuditService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

@ExtendWith(MockitoExtension.class)
class OidcAccountServiceTests {
  @Mock UserAccountRepository users;
  @Mock ExternalIdentityRepository identities;
  @Mock PasswordEncoder passwords;
  @Mock NewsletterAccountLinkRepository newsletter;
  @Mock AuditService audit;

  @Test
  void provisionsVerifiedAccountAndPersistsExternalIdentityWithoutRealProvider() {
    var properties = properties();
    var service = service(properties);
    when(identities.findByIssuerAndSubject("https://issuer.example", "subject-1"))
        .thenReturn(Optional.empty());
    when(users.findByEmailIgnoreCase("person@example.test")).thenReturn(Optional.empty());
    when(passwords.encode(anyString())).thenReturn("disabled-local-password");
    when(users.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
    var identity = ArgumentCaptor.forClass(ExternalIdentity.class);

    UserAccount account =
        service.provision(
            "https://issuer.example",
            "subject-1",
            Map.of(
                "email",
                "Person@Example.Test",
                "email_verified",
                true,
                "name",
                "Person",
                "groups",
                Set.of("editors")),
            Instant.parse("2026-09-02T12:00:00Z"));

    assertThat(account.email).isEqualTo("person@example.test");
    assertThat(account.localCredentialsEnabled).isFalse();
    assertThat(account.roles)
        .containsExactlyInAnyOrder(UserAccount.Role.READER, UserAccount.Role.EDITOR);
    verify(identities).save(identity.capture());
    assertThat(identity.getValue().issuer).isEqualTo("https://issuer.example");
    assertThat(identity.getValue().subject).isEqualTo("subject-1");
    verify(newsletter).linkAndFind(account.id, account.email);
    verify(audit)
        .record(
            eq(account.id),
            eq("IDENTITY_OIDC_LINKED"),
            eq("user"),
            eq(account.id),
            eq(Map.of("issuer", "https://issuer.example")));
  }

  @Test
  void refusesToLinkExistingAccountWhenProviderEmailIsUnverified() {
    var properties = properties();
    properties.getOidc().setRequireVerifiedEmail(false);
    var service = service(properties);
    var existing =
        new UserAccount(
            java.util.UUID.randomUUID(),
            "person@example.test",
            "Person",
            "hash",
            true,
            Set.of(UserAccount.Role.READER));
    when(identities.findByIssuerAndSubject("https://issuer.example", "subject-2"))
        .thenReturn(Optional.empty());
    when(users.findByEmailIgnoreCase("person@example.test")).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.provision(
                    "https://issuer.example",
                    "subject-2",
                    Map.of("email", "person@example.test", "email_verified", false),
                    Instant.now()))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("verified identity-provider email");
  }

  @Test
  void existingExternalIdentityCannotReviveDeletedAccount() {
    var properties = properties();
    var service = service(properties);
    var deleted =
        new UserAccount(
            java.util.UUID.randomUUID(),
            "person@example.test",
            "Person",
            "hash",
            true,
            Set.of(UserAccount.Role.READER));
    deleted.softDelete("disabled", Instant.now());
    var linked =
        new ExternalIdentity(
            deleted.id,
            "https://issuer.example",
            "subject-3",
            "person@example.test",
            Instant.now());
    when(identities.findByIssuerAndSubject("https://issuer.example", "subject-3"))
        .thenReturn(Optional.of(linked));
    when(users.findById(deleted.id)).thenReturn(Optional.of(deleted));

    assertThatThrownBy(
            () ->
                service.provision(
                    "https://issuer.example",
                    "subject-3",
                    Map.of("email", "person@example.test", "email_verified", true),
                    Instant.now()))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("unavailable");
  }

  private OidcAccountService service(IdentityProperties properties) {
    return new OidcAccountService(
        users,
        identities,
        passwords,
        new OidcRoleMapper(properties),
        newsletter,
        properties,
        audit);
  }

  private static IdentityProperties properties() {
    var properties = new IdentityProperties();
    properties.getOidc().setRolesClaim("groups");
    properties.getOidc().setRoleMapping(Map.of("editors", "EDITOR"));
    return properties;
  }
}
