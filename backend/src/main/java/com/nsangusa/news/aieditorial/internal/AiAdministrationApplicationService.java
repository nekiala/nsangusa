package com.nsangusa.news.aieditorial.internal;

import com.nsangusa.news.aieditorial.AiAdministrationService;
import com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration;
import com.nsangusa.news.audit.AuditService;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AiAdministrationApplicationService implements AiAdministrationService {
  private final JdbcTemplate jdbc;
  private final DeployedEditorialCatalog catalog;
  private final AuditService audit;
  private AiProviderSetupService setup;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void useSetup(AiProviderSetupService setup) {
    this.setup = setup;
  }

  AiAdministrationApplicationService(
      JdbcTemplate jdbc, DeployedEditorialCatalog catalog, AuditService audit) {
    this.jdbc = jdbc;
    this.catalog = catalog;
    this.audit = audit;
  }

  @Override
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional(readOnly = true)
  public ConfigurationView current() {
    return effectiveSelection();
  }

  @Override
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  public List<ProviderView> providers() {
    return catalog.providers();
  }

  @Override
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional(readOnly = true)
  public List<ConfigurationView> history(int page, int size) {
    validatePage(page, size);
    return jdbc.query(
        "select * from ai_configuration_versions order by version desc limit ? offset ?",
        this::configurationRow,
        size,
        (long) page * size);
  }

  @Override
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional(readOnly = true)
  public ConfigurationView configuration(long version) {
    return jdbc
        .query(
            "select * from ai_configuration_versions where version = ?",
            this::configurationRow,
            version)
        .stream()
        .findFirst()
        .orElseThrow(() -> notFound("Configuration"));
  }

  @Override
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional(readOnly = true)
  public List<PromptView> prompts(int page, int size) {
    validatePage(page, size);
    return jdbc.query(
        "select * from ai_prompt_versions order by revision desc limit ? offset ?",
        this::promptRow,
        size,
        (long) page * size);
  }

  @Override
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional(readOnly = true)
  public PromptView prompt(String version) {
    return findPrompt(version);
  }

  @Override
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional
  public long select(
      long expectedVersion,
      String provider,
      String model,
      String promptVersion,
      String reason,
      UUID actor) {
    requireActor(actor);
    validateReason(reason);
    catalog.validate(provider, model);
    findPrompt(promptVersion);
    lock();
    var selection = effectiveSelection();
    long current = selection.version();
    if (expectedVersion != current) {
      throw new OptimisticLockingFailureException(
          "AI configuration has changed; refresh before saving");
    }
    if (setup != null && !selection.provider().equals(provider)) {
      throw new IllegalArgumentException(
          "Provider changes require saving and explicitly activating a provider draft");
    }
    long next = Math.addExact(current, 1);
    String secretReference =
        "openai".equals(provider)
            ? (setup == null ? catalog.provider().secretReference() : setup.activeReference())
            : "none:local-fake";
    if ("fake".equals(provider) && setup != null && !setup.fakeAllowed()) {
      throw new IllegalArgumentException("Simulation is disabled in production");
    }
    jdbc.update(
        """
        insert into ai_configuration_versions
          (version, provider, model, prompt_version, secret_reference, changed_by, reason)
        values (?, ?, ?, ?, ?, ?, ?)
        """,
        next,
        provider,
        model,
        promptVersion,
        secretReference,
        actor,
        reason.trim());
    audit.record(
        actor,
        "AI_CONFIGURATION_SELECTED",
        "ai_configuration",
        identifier("configuration", next),
        Map.of(
            "version",
            Long.toString(next),
            "provider",
            provider,
            "model",
            model,
            "promptVersion",
            promptVersion,
            "reason",
            reason.trim()));
    return next;
  }

  @Override
  @PreAuthorize("hasRole('ADMINISTRATOR')")
  @Transactional
  public String createPrompt(long expectedRevision, String guidance, String reason, UUID actor) {
    requireActor(actor);
    validateGuidance(guidance);
    validateReason(reason);
    lock();
    long revision = jdbc.queryForObject("select max(revision) from ai_prompt_versions", Long.class);
    if (expectedRevision != revision) {
      throw new OptimisticLockingFailureException(
          "Prompt registry has changed; refresh before saving");
    }
    long next = Math.addExact(revision, 1);
    String version = "editorial-guidance-v" + next;
    jdbc.update(
        """
        insert into ai_prompt_versions (revision, version, guidance, created_by, reason)
        values (?, ?, ?, ?, ?)
        """,
        next,
        version,
        guidance.trim(),
        actor,
        reason.trim());
    audit.record(
        actor,
        "AI_PROMPT_VERSION_CREATED",
        "ai_prompt",
        identifier("prompt", next),
        Map.of("version", version, "revision", Long.toString(next), "reason", reason.trim()));
    return version;
  }

  @Transactional(readOnly = true)
  public ProviderConfiguration snapshot() {
    var selection = effectiveSelection();
    catalog.validate(selection.provider(), selection.model());
    var prompt = findPrompt(selection.promptVersion());
    return new ProviderConfiguration(
        selection.provider(),
        selection.model(),
        prompt.version(),
        selection.version(),
        prompt.guidance(),
        selection.secretReference());
  }

  void validateSnapshot(ProviderConfiguration configuration) {
    catalog.validate(configuration.provider(), configuration.model());
    if (setup != null && "openai".equals(configuration.provider())) {
      setup.runtimeSettings(configuration);
    } else if (!catalog.provider().secretReference().equals(configuration.secretReference())) {
      throw new IllegalArgumentException("The selected secret binding is no longer deployed");
    }
  }

  private ConfigurationView effectiveSelection() {
    return jdbc
        .query(
            "select * from ai_configuration_versions order by version desc limit 1",
            this::configurationRow)
        .stream()
        .findFirst()
        .orElseGet(
            () ->
                new ConfigurationView(
                    0,
                    catalog.provider().id(),
                    catalog.defaultModel(),
                    "editorial-v1",
                    catalog.provider().secretReference(),
                    null,
                    null,
                    "Operator-deployed default"));
  }

  private PromptView findPrompt(String version) {
    if (version == null || !version.matches("[a-zA-Z0-9._-]{1,100}")) {
      throw new IllegalArgumentException("Invalid prompt version");
    }
    return jdbc
        .query("select * from ai_prompt_versions where version = ?", this::promptRow, version)
        .stream()
        .findFirst()
        .orElseThrow(() -> notFound("Prompt version"));
  }

  private ConfigurationView configurationRow(ResultSet row, int index) throws SQLException {
    return new ConfigurationView(
        row.getLong("version"),
        row.getString("provider"),
        row.getString("model"),
        row.getString("prompt_version"),
        row.getString("secret_reference"),
        row.getObject("changed_by", UUID.class),
        row.getTimestamp("changed_at").toInstant(),
        row.getString("reason"));
  }

  private PromptView promptRow(ResultSet row, int index) throws SQLException {
    return new PromptView(
        row.getLong("revision"),
        row.getString("version"),
        row.getString("guidance"),
        row.getObject("created_by", UUID.class),
        row.getTimestamp("created_at").toInstant(),
        row.getString("reason"));
  }

  private void lock() {
    jdbc.execute("select pg_advisory_xact_lock(1936613736)");
  }

  static void validateGuidance(String guidance) {
    if (guidance == null
        || guidance.trim().length() < 10
        || guidance.length() > 4000
        || guidance.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')
        || guidance.matches("(?is).*(https?://|\\bsk-[a-z0-9_-]{8,}|-----BEGIN|\\bBearer\\s+).*")) {
      throw new IllegalArgumentException(
          "Guidance must be 10–4000 plain-text characters without URLs or credentials");
    }
  }

  static void validateReason(String reason) {
    if (reason == null
        || reason.isBlank()
        || reason.length() > 500
        || reason.chars().anyMatch(Character::isISOControl)
        || reason.matches("(?is).*(\\bsk-[a-z0-9_-]{8,}|-----BEGIN|\\bBearer\\s+).*")) {
      throw new IllegalArgumentException(
          "An audit reason of 1–500 plain-text characters is required");
    }
  }

  private static void requireActor(UUID actor) {
    if (actor == null) throw new IllegalArgumentException("An administrator actor is required");
  }

  private static void validatePage(int page, int size) {
    if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Page must be non-negative and size must be 1–100");
    }
  }

  private static UUID identifier(String type, long version) {
    return UUID.nameUUIDFromBytes(("ai-" + type + ":" + version).getBytes(StandardCharsets.UTF_8));
  }

  private static ResponseStatusException notFound(String type) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found");
  }
}
