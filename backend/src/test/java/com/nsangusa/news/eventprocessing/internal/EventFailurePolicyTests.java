package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.nsangusa.news.eventprocessing.TerminalEventException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.security.access.AccessDeniedException;

class EventFailurePolicyTests {
  @Test
  void distinguishesTerminalCausesFromTransientRacesThroughListenerWrapping() {
    assertThat(category(new IllegalArgumentException("invalid"))).isEqualTo("invalid");
    assertThat(category(new AccessDeniedException("credential=secret"))).isEqualTo("authorization");
    assertThat(category(new TerminalEventException("invariant"))).isEqualTo("invariant");
    assertThat(category(new IllegalStateException("predecessor not committed")))
        .isEqualTo("transient");
    assertThat(EventFailurePolicy.safeMessage("authorization"))
        .doesNotContain("credential", "secret");
  }

  @Test
  void historicalExceptionMessagesAreNotExposedByOperationsViews() {
    assertThat(EventFailurePolicy.publicMessage("database password=secret", false))
        .isEqualTo(EventFailurePolicy.safeMessage("transient"));
    assertThat(EventFailurePolicy.publicMessage("private invalid payload", true))
        .isEqualTo(EventFailurePolicy.safeMessage("invalid"));
    assertThat(EventFailurePolicy.publicMessage("Event authorization rejected", true))
        .isEqualTo("Event authorization rejected");
  }

  private String category(RuntimeException failure) {
    return EventFailurePolicy.category(new ListenerExecutionFailedException("listener", failure));
  }
}
