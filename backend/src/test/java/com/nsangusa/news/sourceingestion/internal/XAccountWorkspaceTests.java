package com.nsangusa.news.sourceingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.nsangusa.news.sourceingestion.SourceIngestionService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class XAccountWorkspaceTests {
  @Test
  void productionCannotInjectSyntheticPosts() {
    var service = mock(SourceIngestionService.class);
    var controller = new XAccountController(service, mock(XSourceProvider.class), "production");

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
  void localLookupIsDeterministicAndExplicitlySynthetic() {
    var provider = new FakeXSourceProvider();
    var lookup = provider.lookupAccount("@Example");

    assertThat(lookup.simulated()).isTrue();
    assertThat(lookup.accountId()).matches("\\d{1,30}");
    assertThat(provider.lookupAccount("example").accountId()).isEqualTo(lookup.accountId());
    assertThat(lookup.handle()).isEqualTo("Example");
  }
}
