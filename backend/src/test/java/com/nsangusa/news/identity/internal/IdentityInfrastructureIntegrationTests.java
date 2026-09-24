package com.nsangusa.news.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.identity.IdentityService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "news.public-base-url=http://localhost:3000",
      "spring.session.timeout=20m"
    },
    showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  UserAdministrationService.class,
  IdentityAdministrationLock.class,
  IdentityApplicationService.class,
  IdentitySessionService.class,
  IdentitySessionConfiguration.class,
  IdentityInfrastructureIntegrationTests.Support.class,
  IdentityInfrastructureIntegrationTests.PublicServices.class
})
class IdentityInfrastructureIntegrationTests {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.10.0").withExposedPorts(6379);

  @Container
  static final GenericContainer<?> MAILPIT =
      new GenericContainer<>("axllent/mailpit:v1.27.8").withExposedPorts(1025, 8025);

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired UserAccountRepository users;
  @Autowired VerificationTokenRepository tokens;
  @Autowired ExternalIdentityRepository externalIdentities;
  @Autowired UserAdministrationService administration;
  @Autowired IdentityService identity;
  @Autowired RedisIndexedSessionRepository sessionRepository;
  @Autowired IdentitySessionService sessions;
  @Autowired PasswordEncoder passwords;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean NewsletterAccountLinkRepository newsletter;

  @BeforeEach
  void isolateFixtures() {
    jdbc.execute("truncate table users cascade");
  }

  @Test
  void rolesCommitOneVersionOneAuditAndOneDurableReceiptAndRevokeRealIndexedSessions() {
    var admin = save("admin", UserAccount.Role.ADMINISTRATOR);
    var reader = save("reader", UserAccount.Role.READER);
    String sessionId = session(reader.email);
    assertThat(sessions.list(reader.email, sessionId))
        .singleElement()
        .satisfies(
            session -> {
              assertThat(session.current()).isTrue();
              assertThat(Duration.between(session.lastAccessedAt(), session.expiresAt()))
                  .isEqualTo(Duration.ofMinutes(20));
            });
    var command =
        new UserAdministrationService.RoleChange(
            Set.of(UserAccount.Role.READER, UserAccount.Role.EDITOR), reader.version, reader.email);
    administration.changeRoles(admin.email, reader.id, command, "roles-durable-infrastructure");
    administration.changeRoles(admin.email, reader.id, command, "roles-durable-infrastructure");

    var changed = users.findById(reader.id).orElseThrow();
    assertThat(changed.version).isEqualTo(reader.version + 1);
    assertThat(changed.roles)
        .containsExactlyInAnyOrder(UserAccount.Role.READER, UserAccount.Role.EDITOR);
    assertThat(changed.authenticationValidAfter).isNotNull();
    assertThat(sessionRepository.findById(sessionId)).isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where target_id = ? and action = 'IDENTITY_ROLES_CHANGED'",
                Integer.class,
                reader.id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from request_idempotency where actor_id = ? and idempotency_key = ?",
                Integer.class,
                admin.id,
                "roles-durable-infrastructure"))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                administration.changeRoles(
                    admin.email,
                    reader.id,
                    new UserAdministrationService.RoleChange(
                        Set.of(UserAccount.Role.MODERATOR), reader.version, reader.email),
                    "new-stale-request"))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThatThrownBy(
            () ->
                administration.changeRoles(
                    admin.email,
                    reader.id,
                    new UserAdministrationService.RoleChange(
                        Set.of(UserAccount.Role.MODERATOR), changed.version, reader.email),
                    "roles-durable-infrastructure"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void filteredPagesEscapeWildcardsAndDatabaseRejectsUnknownRoles() {
    var admin = save("admin", UserAccount.Role.ADMINISTRATOR);
    var reader = save("reader", UserAccount.Role.READER);
    reader.displayName = "Literal%Name";
    users.saveAndFlush(reader);
    assertThat(
            administration
                .list(
                    admin.email,
                    "%",
                    UserAccount.Role.READER,
                    UserAdministrationService.Status.active,
                    0,
                    1)
                .items())
        .singleElement()
        .extracting(UserAdministrationService.UserView::id)
        .isEqualTo(reader.id);
    assertThat(administration.list(admin.email, "missing", null, null, 0, 20).total()).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update("insert into user_roles(user_id, role) values (?, 'ROOT')", reader.id))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void profileVersionExportDeletionAndSessionOwnershipWorkAgainstPostgresAndRedis() {
    var reader = save("reader", UserAccount.Role.READER);
    var other = save("other", UserAccount.Role.READER);
    String current = session(reader.email);
    String otherSession = session(other.email);
    assertThatThrownBy(() -> identity.revokeSession(reader.email, otherSession))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(sessionRepository.findById(otherSession)).isNotNull();
    var profile = identity.profile(reader.email);
    var updated =
        identity.updateProfile(
            reader.email,
            new IdentityService.ProfileUpdate("Updated profile", null, profile.version()));
    assertThat(updated.version()).isEqualTo(profile.version() + 1);
    assertThatThrownBy(
            () ->
                identity.updateProfile(
                    reader.email,
                    new IdentityService.ProfileUpdate("Stale", null, profile.version())))
        .isInstanceOf(OptimisticLockingFailureException.class);
    externalIdentities.saveAndFlush(
        new ExternalIdentity(
            reader.id, "https://issuer.example", "subject", reader.email, Instant.now()));
    tokens.saveAndFlush(
        new VerificationToken(
            reader.id,
            UUID.randomUUID().toString(),
            "password_reset",
            Instant.now().plusSeconds(60)));
    var exported = identity.exportData(reader.email);
    assertThat(exported.profile().displayName()).isEqualTo("Updated profile");
    assertThat(exported.externalIdentities()).hasSize(1);
    identity.deleteAccount(reader.email, updated.version());
    var deleted = users.findById(reader.id).orElseThrow();
    assertThat(deleted.email).endsWith("@users.invalid");
    assertThat(deleted.enabled).isFalse();
    assertThat(deleted.roles).isEmpty();
    assertThat(externalIdentities.findByUserIdOrderByCreatedAt(reader.id)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from verification_tokens where user_id = ?",
                Integer.class,
                reader.id))
        .isZero();
    assertThat(sessionRepository.findById(current)).isNull();
    verify(newsletter).unsubscribeAndUnlink(reader.id);
  }

  @Test
  void concurrentAdministratorDeletionCannotRemoveTheLastAdministrator() throws Exception {
    var first = save("first-admin", UserAccount.Role.ADMINISTRATOR);
    var second = save("second-admin", UserAccount.Role.ADMINISTRATOR);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var results =
          List.of(first, second).stream()
              .map(
                  user ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            start.await(10, TimeUnit.SECONDS);
                            try {
                              identity.deleteAccount(user.email, user.version);
                              return "deleted";
                            } catch (IllegalStateException rejected) {
                              return rejected.getMessage();
                            }
                          }))
              .toList();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  results.get(0).get(20, TimeUnit.SECONDS),
                  results.get(1).get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(
              "deleted", "The last active administrator cannot delete their account");
      assertThat(users.countActiveWithRole(UserAccount.Role.ADMINISTRATOR)).isEqualTo(1);
    }
  }

  @Test
  void registrationAndPasswordRecoveryDeliverRealLocalMailWithSingleUseTokens() throws Exception {
    String email = "identity-" + UUID.randomUUID() + "@example.test";
    String oldPassword = "original-reader-password";
    UUID id = identity.register(email, oldPassword, "Mail reader");
    String verificationToken = mailToken(email, "Verify");
    assertThat(users.findById(id).orElseThrow().emailVerified).isFalse();
    identity.verifyEmail(verificationToken);
    assertThatThrownBy(() -> identity.verifyEmail(verificationToken))
        .isInstanceOf(IllegalArgumentException.class);
    String activeSession = session(email);
    identity.requestPasswordReset(email);
    String resetToken = mailToken(email, "Reset");
    assertThat(resetToken).isNotEqualTo(verificationToken);
    identity.resetPassword(resetToken, "replacement-reader-password");
    assertThat(
            passwords.matches(
                "replacement-reader-password", users.findById(id).orElseThrow().passwordHash))
        .isTrue();
    assertThat(sessionRepository.findById(activeSession)).isNull();
    assertThatThrownBy(() -> identity.resetPassword(resetToken, "another-reader-password"))
        .isInstanceOf(IllegalArgumentException.class);
    identity.requestPasswordReset("missing-" + UUID.randomUUID() + "@example.test");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from verification_tokens where token_hash in (?, ?)",
                Integer.class,
                verificationToken,
                resetToken))
        .isZero();
  }

  @Test
  void concurrentVerificationOnlyConsumesATokenOnceAndExpiredTokensStayInvalid() throws Exception {
    var reader = save("verify-reader", UserAccount.Role.READER);
    reader.emailVerified = false;
    users.saveAndFlush(reader);
    String raw = "v".repeat(43);
    String hash =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
    tokens.saveAndFlush(
        new VerificationToken(
            reader.id, hash, "email_verification", Instant.now().plusSeconds(60)));
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var results =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            start.await(10, TimeUnit.SECONDS);
                            try {
                              identity.verifyEmail(raw);
                              return "verified";
                            } catch (IllegalArgumentException invalid) {
                              return "invalid";
                            }
                          }))
              .toList();
      start.countDown();
      assertThat(
              List.of(
                  results.get(0).get(10, TimeUnit.SECONDS),
                  results.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("verified", "invalid");
    }
    String expired = "e".repeat(43);
    String expiredHash =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(expired.getBytes(StandardCharsets.UTF_8)));
    tokens.saveAndFlush(
        new VerificationToken(
            reader.id, expiredHash, "password_reset", Instant.now().minusSeconds(1)));
    assertThatThrownBy(() -> identity.resetPassword(expired, "new-irrelevant-password"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void communityDiscoveryUsesCurrentModeratorRolesAndReturnsOnlyBoundedPublicIdentityFields() {
    var moderator = save("moderator", UserAccount.Role.MODERATOR);
    var reader = save("visible-reader", UserAccount.Role.READER);
    assertThat(identity.requireAnyRole(moderator.email, Set.of("MODERATOR", "ADMINISTRATOR")))
        .isEqualTo(moderator.id);
    assertThat(identity.findCommunityUsers(moderator.email, reader.email, 20))
        .containsExactly(
            new IdentityService.CommunityUser(reader.id, reader.displayName, false, true));
    assertThat(identity.communityUser(moderator.email, moderator.id).staff()).isTrue();
    var firstPage = identity.findCommunityUsers(moderator.email, "example.test", 0, 1);
    var secondPage = identity.findCommunityUsers(moderator.email, "example.test", 1, 1);
    assertThat(firstPage.total()).isEqualTo(2);
    assertThat(secondPage.total()).isEqualTo(2);
    assertThat(firstPage.items()).hasSize(1);
    assertThat(secondPage.items()).hasSize(1).doesNotContain(firstPage.items().getFirst());
    assertThatThrownBy(() -> identity.communityUser(moderator.email, UUID.randomUUID()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(identity.displayNames(Set.of(reader.id, moderator.id)))
        .containsEntry(reader.id, reader.displayName);
    assertThatThrownBy(() -> identity.findCommunityUsers(reader.email, "reader", 20))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> identity.findCommunityUsers(moderator.email, "", 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> identity.findCommunityUsers(moderator.email, "reader", 21))
        .isInstanceOf(IllegalArgumentException.class);
    reader.softDelete("disabled-hash", Instant.now());
    users.saveAndFlush(reader);
    assertThat(identity.displayNames(Set.of(reader.id))).containsEntry(reader.id, "Deleted user");
    assertThat(identity.findCommunityUsers(moderator.email, "visible-reader", 20)).isEmpty();
    assertThat(identity.communityUser(moderator.email, reader.id).active()).isFalse();
  }

  private UserAccount save(String prefix, UserAccount.Role role) {
    return users.saveAndFlush(
        new UserAccount(
            UUID.randomUUID(),
            prefix + "-" + UUID.randomUUID() + "@example.test",
            prefix,
            "hash",
            true,
            Set.of(role)));
  }

  private String session(String email) {
    var session = sessionRepository.createSession();
    session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, email);
    sessionRepository.save(session);
    return session.getId();
  }

  private String mailToken(String email, String subject) throws Exception {
    String base = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String query = URLEncoder.encode("to:" + email + " subject:" + subject, StandardCharsets.UTF_8);
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/search?query=" + query))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var messages = JsonMapper.builder().build().readTree(response.body()).path("messages");
    assertThat(messages.size()).isEqualTo(1);
    String id = messages.get(0).path("ID").asString();
    var message =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/message/" + id))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String text = JsonMapper.builder().build().readTree(message.body()).path("Text").asString();
    var token = java.util.regex.Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(text);
    assertThat(token.find()).isTrue();
    return token.group(1);
  }

  static class PublicServices implements ImportSelector {
    @Override
    public String[] selectImports(AnnotationMetadata metadata) {
      return new String[] {
        "com.nsangusa.news.eventprocessing.internal.JpaDurableCommandExecutor",
        "com.nsangusa.news.audit.internal.AuditApplicationService"
      };
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Support {
    @Bean
    CacheManager cacheManager() {
      return new NoOpCacheManager();
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    IdentityProperties identityProperties() {
      return new IdentityProperties();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
      return new SecurityConfiguration().passwordEncoder();
    }

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
      return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    @Bean
    JavaMailSender javaMailSender() {
      var sender = new JavaMailSenderImpl();
      sender.setHost(MAILPIT.getHost());
      sender.setPort(MAILPIT.getMappedPort(1025));
      sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
      sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");
      return sender;
    }
  }
}
