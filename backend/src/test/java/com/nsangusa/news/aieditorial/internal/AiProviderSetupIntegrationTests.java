package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration;
import com.nsangusa.news.audit.AuditService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "news.providers.mode=fake",
      "news.providers.ai.live-enabled=true",
      "news.providers.ai.credential-master-key=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
    },
    showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@WithMockUser(roles = "ADMINISTRATOR")
@Import({
  AiProviderSetupService.class,
  AiCredentialCipher.class,
  DeployedEditorialCatalog.class,
  AiAdministrationIntegrationTests.Configuration.class
})
class AiProviderSetupIntegrationTests {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired AiProviderSetupService setup;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired DeployedEditorialCatalog catalog;
  @Autowired com.nsangusa.news.eventprocessing.DurableCommandExecutor commands;
  @Autowired jakarta.persistence.EntityManager entityManager;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  @Test
  void draftCredentialAndAcknowledgedActivationAreSeparateAndNeverExposeSecrets() throws Exception {
    UUID actor = UUID.randomUUID();
    String key = "sk-test-only-not-an-external-credential";
    setup.save(0, input(), actor);
    assertThat(setup.status().liveActive()).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from ai_configuration_versions", Integer.class))
        .isZero();
    assertThat(setup.status().canActivate()).isFalse();
    setup.rotate(1, key, "Store project credential", actor);
    assertThat(setup.status().credentialStatus()).isEqualTo("CONFIGURED");
    assertThat(setup.status().canActivate()).isTrue();
    assertThat(mapper.writeValueAsString(setup.status()))
        .doesNotContain(key, "ciphertext", "apiKey");
    assertThat(
            jdbc.queryForObject(
                "select credential_ciphertext from ai_provider_setup", String.class))
        .doesNotContain(key);
    assertThatThrownBy(() -> setup.activate(2, 0, false, "Missing acknowledgement", actor))
        .isInstanceOf(IllegalArgumentException.class);
    setup.activate(2, 0, true, "Rights and cost reviewed", actor);
    assertThat(setup.status().liveActive()).isTrue();
    assertThat(setup.status().imageProvider()).contains("fake", "simulated");
    assertThat(setup.status().sourceProvider()).contains("fake", "simulated");
    var configuration =
        new ProviderConfiguration("openai", "gpt-5-mini", "editorial-v1", 1, "", "encrypted:ai:1");
    assertThat(setup.runtimeSettings(configuration).maxOutputTokens()).isEqualTo(1024);
    assertThat(setup.credential()).isEqualTo(key);
    // AuditService buffers JPA inserts; JDBC assertions must flush the shared test transaction.
    entityManager.flush();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where actor_id = ?", Integer.class, actor))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForList("select * from audit_records where actor_id = ?", actor).toString())
        .doesNotContain(key, "credential_ciphertext");
    setup.remove(3, "Revoke access", actor);
    assertThat(setup.status().credentialStatus()).isEqualTo("NOT_CONFIGURED");
    assertThat(setup.status().liveActive()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select credential_ciphertext from ai_provider_setup", String.class))
        .isNull();
    assertThatThrownBy(() -> setup.runtimeSettings(configuration))
        .isInstanceOf(AiProviderException.class);
  }

  @Test
  void rejectsStaleVersionsUnauthorizedModelsBoundsAndOperatorGateWithoutChangingActiveSelection() {
    UUID actor = UUID.randomUUID();
    setup.save(0, input(), actor);
    assertThatThrownBy(() -> setup.save(0, input(), actor))
        .isInstanceOf(OptimisticLockingFailureException.class);
    var bad =
        new AiProviderSetupService.SettingsInput(
            "openai", "unapproved", "editorial-v1", 10, 1024, 500000, "Invalid model");
    assertThatThrownBy(() -> setup.save(1, bad, actor))
        .isInstanceOf(IllegalArgumentException.class);
    var over =
        new AiProviderSetupService.SettingsInput(
            "openai", "gpt-5-mini", "editorial-v1", 121, 1024, 500000, "Too slow");
    assertThatThrownBy(() -> setup.save(1, over, actor))
        .isInstanceOf(IllegalArgumentException.class);
    setup.rotate(1, "sk-test-only-not-an-external-credential", "Store credential", actor);
    assertThatThrownBy(() -> setup.activate(2, 99, true, "Stale selection", actor))
        .isInstanceOf(OptimisticLockingFailureException.class);
    var gated =
        new AiProviderSetupService(
            jdbc,
            catalog,
            mock(AuditService.class),
            new AiCredentialCipher(AiCredentialCipherTests.KEY),
            new MockEnvironment());
    assertThat(gated.status().canActivate()).isFalse();
    assertThat(gated.status().activationBlockers())
        .contains("Operator must enable AI_LIVE_ENABLED");
    assertThatThrownBy(() -> gated.activate(2, 0, true, "Gate cannot be bypassed", actor))
        .isInstanceOf(IllegalArgumentException.class);
    var missingKey =
        new AiProviderSetupService(
            jdbc,
            catalog,
            mock(AuditService.class),
            new AiCredentialCipher(""),
            new MockEnvironment().withProperty("news.providers.ai.live-enabled", "true"));
    assertThat(missingKey.status().credentialStatus()).isEqualTo("MASTER_KEY_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                missingKey.rotate(
                    2, "sk-test-only-not-an-external-credential", "Fail closed", actor))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void credentialCommandsReplayWithoutRotatingAgainAndRejectChangedKeyReuse() {
    UUID actor = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    String apiKey = "sk-test-only-not-an-external-credential";
    var payload =
        java.util.Map.of(
            "expectedVersion", 0, "credentialDigest", "test-digest", "reason", "Rotate");
    String result =
        commands.execute(
            actor,
            key,
            "PUT /api/v1/admin/ai-configuration/setup/credential",
            payload,
            () -> {
              setup.rotate(0, apiKey, "Rotate", actor);
              return "applied";
            });
    String encrypted =
        jdbc.queryForObject("select credential_ciphertext from ai_provider_setup", String.class);
    assertThat(
            commands.execute(
                actor,
                key,
                "PUT /api/v1/admin/ai-configuration/setup/credential",
                payload,
                () -> {
                  throw new AssertionError("Successful rotation must not execute again");
                }))
        .isEqualTo(result);
    assertThat(
            jdbc.queryForObject(
                "select credential_ciphertext from ai_provider_setup", String.class))
        .isEqualTo(encrypted);
    assertThat(setup.status().version()).isEqualTo(1);
    entityManager.flush();
    assertThat(
            jdbc.queryForMap("select * from request_idempotency where actor_id = ?", actor)
                .toString())
        .doesNotContain(apiKey, encrypted);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where actor_id = ?", Integer.class, actor))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                commands.execute(
                    actor,
                    key,
                    "PUT /api/v1/admin/ai-configuration/setup/credential",
                    java.util.Map.of("credentialDigest", "changed-digest"),
                    () -> "not-executed"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void setupAuditAndCommandReceiptRollBackInTheSameRealTransaction() {
    UUID actor = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    transaction.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        commands.execute(
                            actor,
                            key,
                            "PUT /api/v1/admin/ai-configuration/setup",
                            input(),
                            () -> {
                              setup.save(0, input(), actor);
                              entityManager.flush();
                              assertThat(
                                      jdbc.queryForObject(
                                          "select count(*) from audit_records where actor_id = ?",
                                          Integer.class,
                                          actor))
                                  .isEqualTo(1);
                              throw new IllegalStateException("Simulated command failure");
                            })))
        .hasMessage("Simulated command failure");
    assertThat(setup.status().version()).isZero();
    assertThat(jdbc.queryForObject("select count(*) from ai_provider_settings", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_records where actor_id = ?", Integer.class, actor))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from request_idempotency where actor_id = ?",
                Integer.class,
                actor))
        .isZero();
  }

  @Test
  @WithMockUser(roles = "EDITOR")
  void serviceAuthorizationCannotBeBypassed() {
    assertThatThrownBy(setup::status)
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> setup.save(0, input(), UUID.randomUUID()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  private AiProviderSetupService.SettingsInput input() {
    return new AiProviderSetupService.SettingsInput(
        "openai", "gpt-5-mini", "editorial-v1", 10, 1024, 500000, "Reviewed draft");
  }
}
