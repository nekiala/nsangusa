package com.nsangusa.news.sourceingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class XAccountWorkspaceTests {
  @Test
  void productionCannotInjectSyntheticPosts() {
    var service = mock(SourceIngestionService.class);
    var controller =
        new XAccountController(service, mock(XSourceProvider.class), "production", false);

    assertThat(controller.capabilities().simulationEnabled()).isFalse();
    assertThatThrownBy(
            () ->
                controller.simulate(
                    UUID.randomUUID(),
                    new XAccountController.SimulatedPostRequest(
                        "123", "https://x.com/source/status/123", "Untrusted text", Instant.now())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode().value())
                    .isEqualTo(403));
    verifyNoInteractions(service);
  }

  @Test
  void liveXWithFakeProvidersCannotInjectSyntheticPosts() {
    var controller =
        new XAccountController(
            mock(SourceIngestionService.class), mock(XSourceProvider.class), "fake", true);

    assertThat(controller.capabilities().simulationEnabled()).isFalse();
  }

  @Test
  void liveGateSelectsTheOfficialApiWithoutProductionProviderMode() {
    var runner =
        new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(FakeXSourceProvider.class, OfficialXApiSourceProvider.class)
            .withPropertyValues(
                "spring.profiles.active=local",
                "news.providers.mode=fake",
                "news.x.api-base-url=https://api.x.com");

    runner.run(
        context ->
            assertThat(context.getBean(XSourceProvider.class))
                .isInstanceOf(FakeXSourceProvider.class));
    runner
        .withPropertyValues("news.x.live-enabled=true", "news.x.bearer-token=test-token")
        .run(
            context ->
                assertThat(context.getBean(XSourceProvider.class))
                    .isInstanceOf(OfficialXApiSourceProvider.class));
    runner
        .withPropertyValues("news.x.live-enabled=true", "news.x.bearer-token=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void localLookupIsDeterministicAndExplicitlySynthetic() {
    var provider = new FakeXSourceProvider();
    var lookup = provider.lookupAccount("@Example");

    assertThat(lookup.simulated()).isTrue();
    assertThat(lookup.accountId()).matches("\\d{1,30}");
    assertThat(provider.lookupAccount("example").accountId()).isEqualTo(lookup.accountId());
    assertThat(lookup.handle()).isEqualTo("Example");
  }
}
