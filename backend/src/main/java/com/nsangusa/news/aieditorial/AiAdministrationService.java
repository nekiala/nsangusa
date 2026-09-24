package com.nsangusa.news.aieditorial;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Administration of editorial model and prompt selections; credential setup is a separate boundary.
 */
public interface AiAdministrationService {
  ConfigurationView current();

  List<ProviderView> providers();

  List<ConfigurationView> history(int page, int size);

  ConfigurationView configuration(long version);

  List<PromptView> prompts(int page, int size);

  PromptView prompt(String version);

  long select(
      long expectedVersion,
      String provider,
      String model,
      String promptVersion,
      String reason,
      UUID actor);

  String createPrompt(long expectedRevision, String guidance, String reason, UUID actor);

  record ProviderView(
      String id,
      String displayName,
      List<String> capabilities,
      List<String> approvedModels,
      String secretReference,
      boolean simulated) {}

  record ConfigurationView(
      long version,
      String provider,
      String model,
      String promptVersion,
      String secretReference,
      UUID changedBy,
      Instant changedAt,
      String reason) {}

  record PromptView(
      long revision,
      String version,
      String guidance,
      UUID createdBy,
      Instant createdAt,
      String reason) {}
}
