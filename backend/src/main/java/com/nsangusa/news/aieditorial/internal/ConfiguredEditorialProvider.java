package com.nsangusa.news.aieditorial.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.aieditorial.EditorialProviders.*;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Selects only explicitly activated adapters; a failed live call is never replaced by simulation.
 */
@Component
@Primary
class ConfiguredEditorialProvider
    implements EditorialAnalysisProvider, ArticleDraftProvider, ContentSafetyProvider {
  private final AiAdministrationApplicationService administration;
  private final AiProviderSetupService setup;
  private final ObjectMapper mapper;
  private final URI endpoint;
  private final int maxRequestBytes;
  private final int maxResponseBytes;
  private final ProductionEditorialProvider.Exchange exchange;
  private final FakeEditorialProvider fake = new FakeEditorialProvider();

  ConfiguredEditorialProvider(
      AiAdministrationApplicationService administration,
      AiProviderSetupService setup,
      ObjectMapper mapper,
      @Value("${news.providers.ai.base-url:https://api.openai.com}") URI endpoint,
      @Value("${news.providers.ai.max-request-bytes:131072}") int maxRequestBytes,
      @Value("${news.providers.ai.max-response-bytes:262144}") int maxResponseBytes) {
    this.administration = administration;
    this.setup = setup;
    this.mapper = mapper;
    this.endpoint = endpoint;
    this.maxRequestBytes = maxRequestBytes;
    this.maxResponseBytes = maxResponseBytes;
    this.exchange = ProductionEditorialProvider.productionExchange(endpoint);
  }

  @Override
  public ProviderConfiguration configuration() {
    return administration.snapshot();
  }

  @Override
  public AnalysisResult analyze(AnalysisRequest request) {
    return analyze(request, configuration());
  }

  @Override
  public AnalysisResult analyze(AnalysisRequest request, ProviderConfiguration configuration) {
    if (simulated(configuration)) return fake.analyze(request, configuration);
    return live(configuration).analyze(request, configuration);
  }

  @Override
  public ArticleDraftGenerated draft(DraftRequest request) {
    return draft(request, configuration());
  }

  @Override
  public ArticleDraftGenerated draft(DraftRequest request, ProviderConfiguration configuration) {
    if (simulated(configuration)) return fake.draft(request, configuration);
    return live(configuration).draft(request, configuration);
  }

  @Override
  public SafetyResult evaluate(String content) {
    return evaluate(content, configuration());
  }

  @Override
  public SafetyResult evaluate(String content, ProviderConfiguration configuration) {
    if (simulated(configuration)) return fake.evaluate(content, configuration);
    return live(configuration).evaluate(content, configuration);
  }

  private boolean simulated(ProviderConfiguration configuration) {
    if (!"fake".equals(configuration.provider())) return false;
    if (!setup.fakeAllowed() || !"deterministic-editorial-v1".equals(configuration.model())) {
      throw new AiProviderException("simulation_not_authorized");
    }
    return true;
  }

  private ProductionEditorialProvider live(ProviderConfiguration configuration) {
    var settings = setup.runtimeSettings(configuration);
    return new ProductionEditorialProvider(
        mapper,
        endpoint,
        setup.credential(),
        configuration.model(),
        configuration.promptVersion(),
        Duration.ofSeconds(settings.timeoutSeconds()),
        maxRequestBytes,
        maxResponseBytes,
        settings.maxOutputTokens(),
        exchange);
  }
}
