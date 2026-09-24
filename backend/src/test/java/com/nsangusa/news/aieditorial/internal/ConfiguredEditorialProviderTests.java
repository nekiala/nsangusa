package com.nsangusa.news.aieditorial.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.aieditorial.EditorialProviders.ProviderConfiguration;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ConfiguredEditorialProviderTests {
  @Test
  void savedDailyBudgetActuallyControlsConservativeRequestAdmission() {
    var requests = mock(AiRequestRepository.class);
    var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    var setup = mock(AiProviderSetupService.class);
    var configuration =
        new ProviderConfiguration("openai", "gpt-5-mini", "editorial-v1", 1, "", "encrypted:ai:1");
    var request =
        new AiRequestRecord(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "analysis", configuration);
    when(requests.findById(request.id)).thenReturn(java.util.Optional.of(request));
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
    var audit =
        new AiRequestAuditService(
            requests,
            mock(AiResultRepository.class),
            new ObjectMapper(),
            jdbc,
            1000000,
            131072,
            4096);
    audit.useProviderSetup(setup);
    when(setup.runtimeSettings(configuration))
        .thenReturn(
            new AiProviderSetupService.Settings(
                1, "openai", "gpt-5-mini", "editorial-v1", 10, 1024, 1000));
    assertThat(audit.budgetExhausted(request.id)).isTrue();
    when(setup.runtimeSettings(configuration))
        .thenReturn(
            new AiProviderSetupService.Settings(
                1, "openai", "gpt-5-mini", "editorial-v1", 10, 1024, 500000));
    assertThat(audit.budgetExhausted(request.id)).isFalse();
    assertThat(request.reservedTokens).isEqualTo(135168);
  }

  @Test
  void fakeResultsStayLabeledAndLiveConfigurationFailureNeverFallsBack() {
    var setup = mock(AiProviderSetupService.class);
    var administration = mock(AiAdministrationApplicationService.class);
    var provider =
        new ConfiguredEditorialProvider(
            administration,
            setup,
            new ObjectMapper(),
            URI.create("https://api.openai.com"),
            131072,
            262144);
    when(setup.fakeAllowed()).thenReturn(true);
    var fake = new ProviderConfiguration("fake", "deterministic-editorial-v1", "editorial-v1");
    assertThat(provider.evaluate("Neutral reporting", fake).provider()).isEqualTo("fake");
    var live = new ProviderConfiguration("openai", "gpt-5-mini", "editorial-v1");
    when(setup.runtimeSettings(live)).thenThrow(new AiProviderException("live_ai_not_activated"));
    assertThatThrownBy(() -> provider.evaluate("Neutral reporting", live))
        .isInstanceOf(AiProviderException.class)
        .hasMessageContaining("live_ai_not_activated");
    verify(setup, never()).credential();
    when(setup.fakeAllowed()).thenReturn(false);
    assertThatThrownBy(() -> provider.evaluate("Neutral reporting", fake))
        .isInstanceOf(AiProviderException.class)
        .hasMessageContaining("simulation_not_authorized");
  }
}
