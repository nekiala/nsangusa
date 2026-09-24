package com.nsangusa.news.eventprocessing.internal;

import com.nsangusa.news.eventprocessing.TerminalEventException;
import jakarta.validation.ConstraintViolationException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

final class EventFailurePolicy {
  static final String CATEGORY_HEADER = "x-failure-category";
  static final String ATTEMPT_HEADER = "x-delivery-attempt";
  static final String REPLAY_GROUP_HEADER = "x-replay-consumer-group";

  private EventFailurePolicy() {}

  static String category(Throwable failure) {
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
      if (cause instanceof AccessDeniedException || cause instanceof AuthenticationException) {
        return "authorization";
      }
      if (cause instanceof TerminalEventException) {
        return "invariant";
      }
      if (cause instanceof IllegalArgumentException
          || cause instanceof ConstraintViolationException
          || cause instanceof org.springframework.kafka.support.serializer.DeserializationException
          || cause instanceof org.springframework.messaging.converter.MessageConversionException
          || cause instanceof org.springframework.kafka.support.converter.ConversionException
          || cause instanceof ClassCastException
          || cause instanceof NoSuchMethodException) {
        return "invalid";
      }
    }
    return "transient";
  }

  static String safeMessage(String category) {
    return switch (category == null ? "transient" : category) {
      case "authorization" -> "Event authorization rejected";
      case "invariant" -> "Event invariant rejected";
      case "invalid" -> "Event contract rejected";
      default -> "Event processing failed; inspect restricted diagnostics";
    };
  }

  static String publicMessage(String storedMessage, boolean poison) {
    if (storedMessage == null) return null;
    for (String category :
        java.util.List.of("authorization", "invariant", "invalid", "transient")) {
      String safe = safeMessage(category);
      if (safe.equals(storedMessage)) return safe;
    }
    return safeMessage(poison ? "invalid" : "transient");
  }

  static Throwable rootCause(Throwable failure) {
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    while (failure.getCause() != null && visited.add(failure.getCause())) {
      failure = failure.getCause();
    }
    return failure;
  }
}
