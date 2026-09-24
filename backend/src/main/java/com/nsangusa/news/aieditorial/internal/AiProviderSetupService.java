package com.nsangusa.news.aieditorial.internal;

import com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration;
import com.nsangusa.news.audit.AuditService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AiProviderSetupService {
  private final JdbcTemplate jdbc;
  private final DeployedEditorialCatalog catalog;
  private final AuditService audit;
  private final AiCredentialCipher cipher;
  private final Environment environment;

  AiProviderSetupService(
      JdbcTemplate jdbc,
      DeployedEditorialCatalog catalog,
      AuditService audit,
      AiCredentialCipher cipher,
      Environment environment) {
    this.jdbc = jdbc;
    this.catalog = catalog;
    this.audit = audit;
    this.cipher = cipher;
    this.environment = environment;
  }

  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional(readOnly = true)
  public Status status() {
    var state = state();
    var draft = settings(state.draftVersion());
    String credentialStatus = credentialStatus(state);
    List<String> blockers =
        draft == null
            ? List.of("Save a provider draft before activation")
            : blockers(credentialStatus, draft);
    return new Status(
        state.version(),
        draft,
        settings(state.activeVersion()),
        state.liveActive() && liveEnabled() && "CONFIGURED".equals(credentialStatus),
        liveEnabled(),
        cipher.available(),
        credentialStatus,
        draft != null && blockers.isEmpty(),
        blockers,
        limits(),
        "https://api.openai.com/v1/responses",
        independentProviderMode(),
        independentProviderMode());
  }

  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional
  public void save(long expectedVersion, SettingsInput input, UUID actor) {
    validateActorReason(actor, input.reason());
    catalog.validate(input.provider(), input.model());
    if ("fake".equals(input.provider()) && !fakeAllowed())
      throw new IllegalArgumentException("Simulation is disabled in production");
    validateBounds(input.timeoutSeconds(), input.maxOutputTokens(), input.dailyTokenBudget());
    if (input.promptVersion() == null
        || !input.promptVersion().matches("[a-zA-Z0-9._-]{1,100}")
        || jdbc.queryForObject(
                "select count(*) from ai_prompt_versions where version = ?",
                Integer.class,
                input.promptVersion())
            != 1) {
      throw new IllegalArgumentException("Select an existing prompt version");
    }
    var state = lock(expectedVersion);
    long next = Math.addExact(state.version(), 1);
    jdbc.update(
        """
        insert into ai_provider_settings(version, provider, model, prompt_version, timeout_seconds,
          max_output_tokens, daily_token_budget, changed_by, reason) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        next,
        input.provider(),
        input.model(),
        input.promptVersion(),
        input.timeoutSeconds(),
        input.maxOutputTokens(),
        input.dailyTokenBudget(),
        actor,
        input.reason().trim());
    jdbc.update(
        "update ai_provider_setup set version = ?, draft_version = ? where id = 1", next, next);
    record(actor, "AI_PROVIDER_DRAFT_SAVED", next, input.reason());
  }

  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional
  public void rotate(long expectedVersion, String apiKey, String reason, UUID actor) {
    validateActorReason(actor, reason);
    if (apiKey == null || !apiKey.matches("[!-~]{20,512}")) {
      throw new IllegalArgumentException(
          "An API key of 20–512 printable non-whitespace characters is required");
    }
    var state = lock(expectedVersion);
    UUID id = UUID.randomUUID();
    String encrypted = cipher.encrypt(id, apiKey);
    jdbc.update(
        "update ai_provider_setup set version = ?, credential_id = ?, credential_ciphertext = ?, live_active = false where id = 1",
        state.version() + 1,
        id,
        encrypted);
    record(actor, "AI_CREDENTIAL_ROTATED", state.version() + 1, reason);
  }

  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional
  public void remove(long expectedVersion, String reason, UUID actor) {
    validateActorReason(actor, reason);
    var state = lock(expectedVersion);
    jdbc.update(
        "update ai_provider_setup set version = ?, credential_id = null, credential_ciphertext = null, live_active = false where id = 1",
        state.version() + 1);
    record(actor, "AI_CREDENTIAL_REMOVED", state.version() + 1, reason);
  }

  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional
  public void activate(
      long expectedVersion,
      long expectedConfigurationVersion,
      boolean acknowledgeOutboundDataAndCost,
      String reason,
      UUID actor) {
    validateActorReason(actor, reason);
    var state = lock(expectedVersion);
    var draft = settings(state.draftVersion());
    if (draft == null)
      throw new IllegalArgumentException("Save a provider draft before activation");
    catalog.validate(draft.provider(), draft.model());
    validateBounds(draft.timeoutSeconds(), draft.maxOutputTokens(), draft.dailyTokenBudget());
    boolean live = "openai".equals(draft.provider());
    if (!live && !fakeAllowed())
      throw new IllegalArgumentException("Simulation is disabled in production");
    if (live
        && (!acknowledgeOutboundDataAndCost
            || !blockers(credentialStatus(state), draft).isEmpty())) {
      throw new IllegalArgumentException(
          "Live activation requires operator permission, a decryptable credential, approved settings, and acknowledgement of outbound data rights and costs");
    }
    long current =
        jdbc.queryForObject(
            "select coalesce(max(version), 0) from ai_configuration_versions", Long.class);
    if (current != expectedConfigurationVersion) {
      throw new OptimisticLockingFailureException(
          "AI configuration has changed; refresh before activation");
    }
    jdbc.update(
        """
        insert into ai_configuration_versions(version, provider, model, prompt_version, secret_reference, changed_by, reason)
        values (?, ?, ?, ?, ?, ?, ?)
        """,
        current + 1,
        draft.provider(),
        draft.model(),
        draft.promptVersion(),
        live ? reference(draft.version()) : "none:local-fake",
        actor,
        reason.trim());
    jdbc.update(
        "update ai_provider_setup set version = ?, active_version = ?, live_active = ? where id = 1",
        state.version() + 1,
        draft.version(),
        live);
    audit.record(
        actor,
        live ? "AI_LIVE_PROVIDER_ACTIVATED" : "AI_FAKE_PROVIDER_ACTIVATED",
        "ai_provider_setup",
        new UUID(0, 1),
        Map.of(
            "version",
            Long.toString(state.version() + 1),
            "settingsVersion",
            Long.toString(draft.version()),
            "configurationVersion",
            Long.toString(current + 1),
            "provider",
            draft.provider(),
            "model",
            draft.model(),
            "acknowledgeOutboundDataAndCost",
            Boolean.toString(acknowledgeOutboundDataAndCost),
            "reason",
            reason.trim()));
  }

  Settings runtimeSettings(ProviderConfiguration configuration) {
    var state = state();
    if (!liveEnabled() || !state.liveActive() || !"openai".equals(configuration.provider()))
      throw new AiProviderException("live_ai_not_activated");
    long version;
    try {
      String reference = configuration.secretReference();
      if (reference == null || !reference.startsWith("encrypted:ai:"))
        throw new NumberFormatException();
      version = Long.parseLong(reference.substring("encrypted:ai:".length()));
    } catch (NumberFormatException exception) {
      throw new AiProviderException("live_ai_not_configured");
    }
    var settings = settings(version);
    if (settings == null || !"openai".equals(settings.provider()))
      throw new AiProviderException("live_ai_not_configured");
    catalog.validate(configuration.provider(), configuration.model());
    validateBounds(
        settings.timeoutSeconds(), settings.maxOutputTokens(), settings.dailyTokenBudget());
    return settings;
  }

  String credential() {
    var state = state();
    if (!liveEnabled() || !state.liveActive() || state.ciphertext() == null) {
      throw new AiProviderException("live_ai_not_configured");
    }
    try {
      return cipher.decrypt(state.credentialId(), state.ciphertext());
    } catch (IllegalStateException exception) {
      throw new AiProviderException("credential_unavailable");
    }
  }

  String activeReference() {
    var state = state();
    if (!liveEnabled()
        || !state.liveActive()
        || state.activeVersion() == null
        || !"CONFIGURED".equals(credentialStatus(state))) {
      throw new IllegalArgumentException("Save and explicitly activate live AI settings first");
    }
    return reference(state.activeVersion());
  }

  boolean fakeAllowed() {
    return !"production".equals(environment.getProperty("news.providers.mode", "fake"))
        && !List.of(environment.getActiveProfiles()).contains("production");
  }

  private boolean liveEnabled() {
    return environment.getProperty("news.providers.ai.live-enabled", Boolean.class, false);
  }

  private String independentProviderMode() {
    return "production".equals(environment.getProperty("news.providers.mode", "fake"))
        ? "production (operator-configured; account access not verified)"
        : "fake (simulated; unchanged by text AI activation)";
  }

  private List<String> blockers(String credentialStatus, Settings draft) {
    try {
      catalog.validate(draft.provider(), draft.model());
      validateBounds(draft.timeoutSeconds(), draft.maxOutputTokens(), draft.dailyTokenBudget());
    } catch (IllegalArgumentException exception) {
      return List.of("Saved settings exceed current operator authorizations; save a new draft");
    }
    if ("fake".equals(draft.provider())) {
      return fakeAllowed() ? List.of() : List.of("Simulation is disabled in production");
    }
    if (!liveEnabled()) return List.of("Operator must enable AI_LIVE_ENABLED");
    if (!cipher.available())
      return List.of("Operator must configure AI_CREDENTIAL_MASTER_KEY (base64, 32 bytes)");
    if (!"CONFIGURED".equals(credentialStatus))
      return List.of(
          "UNREADABLE".equals(credentialStatus)
              ? "Restore the deployment master key or rotate the API key"
              : "Add an OpenAI API key");
    return List.of();
  }

  private String credentialStatus(State state) {
    if (state.ciphertext() == null) return "NOT_CONFIGURED";
    if (!cipher.available()) return "MASTER_KEY_UNAVAILABLE";
    try {
      cipher.decrypt(state.credentialId(), state.ciphertext());
      return "CONFIGURED";
    } catch (IllegalStateException exception) {
      return "UNREADABLE";
    }
  }

  private void validateBounds(int timeout, int output, long daily) {
    var limits = limits();
    if (timeout < 1
        || timeout > limits.timeoutSeconds()
        || output < 256
        || output > limits.maxOutputTokens()
        || daily < 1
        || daily > limits.dailyTokenBudget()) {
      throw new IllegalArgumentException(
          "AI timeout, output tokens, and daily token budget must stay within operator bounds");
    }
  }

  private Limits limits() {
    Duration timeout =
        org.springframework.boot.convert.DurationStyle.detectAndParse(
            environment.getProperty("news.providers.ai.timeout", "20s"));
    return new Limits(
        (int) Math.min(120, timeout.toSeconds()),
        Math.min(
            16384,
            environment.getProperty("news.providers.ai.max-output-tokens", Integer.class, 4096)),
        Math.min(
            1_000_000_000L,
            environment.getProperty(
                "news.providers.ai.daily-token-budget", Long.class, 1_000_000L)));
  }

  private State lock(long expectedVersion) {
    jdbc.execute("select pg_advisory_xact_lock(1936613736)");
    var state = state();
    if (expectedVersion != state.version())
      throw new OptimisticLockingFailureException(
          "AI provider setup has changed; refresh before saving");
    return state;
  }

  private State state() {
    return jdbc.queryForObject(
        "select * from ai_provider_setup where id = 1",
        (row, index) ->
            new State(
                row.getLong("version"),
                row.getObject("draft_version", Long.class),
                row.getObject("active_version", Long.class),
                row.getBoolean("live_active"),
                row.getObject("credential_id", UUID.class),
                row.getString("credential_ciphertext")));
  }

  private Settings settings(Long version) {
    if (version == null) return null;
    return jdbc
        .query("select * from ai_provider_settings where version = ?", this::settingsRow, version)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Settings settingsRow(ResultSet row, int index) throws SQLException {
    return new Settings(
        row.getLong("version"),
        row.getString("provider"),
        row.getString("model"),
        row.getString("prompt_version"),
        row.getInt("timeout_seconds"),
        row.getInt("max_output_tokens"),
        row.getLong("daily_token_budget"));
  }

  private void record(UUID actor, String action, long version, String reason) {
    audit.record(
        actor,
        action,
        "ai_provider_setup",
        new UUID(0, 1),
        Map.of("version", Long.toString(version), "reason", reason.trim()));
  }

  private static void validateActorReason(UUID actor, String reason) {
    if (actor == null) throw new IllegalArgumentException("An administrator actor is required");
    AiAdministrationApplicationService.validateReason(reason);
  }

  private static String reference(long version) {
    return "encrypted:ai:" + version;
  }

  private record State(
      long version,
      Long draftVersion,
      Long activeVersion,
      boolean liveActive,
      UUID credentialId,
      String ciphertext) {
    @Override
    public String toString() {
      return "AiProviderState[redacted]";
    }
  }

  record SettingsInput(
      String provider,
      String model,
      String promptVersion,
      int timeoutSeconds,
      int maxOutputTokens,
      long dailyTokenBudget,
      String reason) {}

  record Settings(
      long version,
      String provider,
      String model,
      String promptVersion,
      int timeoutSeconds,
      int maxOutputTokens,
      long dailyTokenBudget) {}

  record Limits(int timeoutSeconds, int maxOutputTokens, long dailyTokenBudget) {}

  record Status(
      long version,
      Settings draft,
      Settings active,
      boolean liveActive,
      boolean liveEnabled,
      boolean masterKeyConfigured,
      String credentialStatus,
      boolean canActivate,
      List<String> activationBlockers,
      Limits limits,
      String endpoint,
      String imageProvider,
      String sourceProvider) {}
}
