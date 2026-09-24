package com.nsangusa.news.aieditorial.internal;

import com.nsangusa.news.aieditorial.AiAdministrationService.ProviderView;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class DeployedEditorialCatalog {
  static final Set<String> OPENAI_MODELS = Set.of("gpt-5-mini", "gpt-5-mini-2025-08-07");
  private final ProviderView provider;
  private final String defaultModel;
  private final List<ProviderView> providers;

  DeployedEditorialCatalog(
      @Value("${news.providers.mode:fake}") String mode,
      @Value("${news.providers.ai.model:gpt-5-mini}") String model,
      @Value("${news.providers.ai.authorized-models:${news.providers.ai.model:gpt-5-mini}}")
          String authorizedModels) {
    var capabilities = List.of("ANALYSIS", "DRAFT", "CONTENT_SAFETY");
    List<String> approved =
        Arrays.stream(authorizedModels.split(",", -1))
            .map(String::trim)
            .distinct()
            .sorted()
            .toList();
    if (approved.isEmpty() || !OPENAI_MODELS.containsAll(approved) || !approved.contains(model)) {
      throw new IllegalArgumentException(
          "AI model authorization exceeds the deployed adapter ceiling");
    }
    var openai =
        new ProviderView(
            "openai", "OpenAI Responses", capabilities, approved, "encrypted:ai", false);
    if ("production".equals(mode)) {
      provider = openai;
      defaultModel = model;
      providers = List.of(openai);
    } else if ("fake".equals(mode)) {
      defaultModel = "deterministic-editorial-v1";
      provider =
          new ProviderView(
              "fake",
              "Deterministic local provider",
              capabilities,
              List.of(defaultModel),
              "none:local-fake",
              true);
      providers = List.of(provider, openai);
    } else {
      throw new IllegalArgumentException("Unsupported deployed AI provider mode");
    }
  }

  ProviderView provider() {
    return provider;
  }

  String defaultModel() {
    return defaultModel;
  }

  List<ProviderView> providers() {
    return providers;
  }

  void validate(String providerId, String model) {
    if (providers.stream()
        .noneMatch(
            value -> value.id().equals(providerId) && value.approvedModels().contains(model))) {
      throw new IllegalArgumentException(
          "Select a deployed provider and an operator-approved model");
    }
  }
}
