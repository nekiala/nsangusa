package com.nsangusa.news.eventprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.DurableCommandExecutor;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class JpaDurableCommandExecutorTests {
  private final JdbcClient jdbc = mock(JdbcClient.class);
  private final DurableCommandExecutor executor =
      new JpaDurableCommandExecutor(jdbc, new ObjectMapper().findAndRegisterModules());

  @Test
  void nullKeyExplicitlyPreservesLegacyExecutionWithoutDatabaseAccess() {
    var invocations = new AtomicInteger();
    Supplier<String> command = () -> Integer.toString(invocations.incrementAndGet());

    assertThat(executor.execute(null, null, null, new Object(), command)).isEqualTo("1");
    assertThat(executor.execute(null, null, null, new Object(), command)).isEqualTo("2");
    assertThat(executor.execute(null, null, null, null, () -> null)).isNull();
    verifyNoInteractions(jdbc);
  }

  @Test
  void propagatesLegacyCommandFailuresWithoutFallback() {
    var failure = new IllegalStateException("command failed");

    assertThatThrownBy(
            () ->
                executor.execute(
                    null,
                    null,
                    null,
                    null,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    verifyNoInteractions(jdbc);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "short",
        "1234567",
        "1234 678",
        "12345678 ",
        " 12345678",
        "1234\t678",
        "1234\n678",
        "12345678\r\n",
        "1234567\u0000",
        "1234567\u007f",
        "1234567\u00e9"
      })
  void invalidKeysConflictBeforeExecutingOrAccessingTheDatabase(String key) {
    assertInvalidKey(key);
  }

  @Test
  void oversizedKeyConflictsBeforeExecutingOrAccessingTheDatabase() {
    assertInvalidKey("x".repeat(201));
  }

  @Test
  void keyedExecutionRequiresActorAndOperation() {
    Supplier<String> command = mock();

    assertThatThrownBy(() -> executor.execute(null, "valid-key", "POST /items", null, command))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> executor.execute(UUID.randomUUID(), "valid-key", null, null, command))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> executor.execute(UUID.randomUUID(), "valid-key", "  ", null, command))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(jdbc, command);
  }

  @Test
  void oversizedOperationFailsBeforeExecutingOrAccessingTheDatabase() {
    Supplier<String> command = mock();

    assertThatThrownBy(
            () -> executor.execute(UUID.randomUUID(), "valid-key", "x".repeat(501), null, command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Command operation must not exceed 500 characters");
    verifyNoInteractions(jdbc, command);
  }

  @Test
  void unserializableRequestFailsBeforeExecutingOrAccessingTheDatabase() {
    Supplier<String> command = mock();

    assertThatThrownBy(
            () ->
                executor.execute(
                    UUID.randomUUID(), "valid-key", "POST /items", new Object(), command))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(jdbc, command);
  }

  private void assertInvalidKey(String key) {
    Supplier<String> command = mock();

    assertThatThrownBy(() -> executor.execute(UUID.randomUUID(), key, "POST /items", null, command))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    verifyNoInteractions(jdbc, command);
  }
}
