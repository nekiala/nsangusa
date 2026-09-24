package com.nsangusa.news.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import jakarta.validation.Validator;
import java.util.Arrays;
import org.springframework.test.util.ReflectionTestUtils;

final class ContractRuntime {
  private ContractRuntime() {}

  static Object instance(String className, Object... arguments) {
    try {
      var constructor =
          Arrays.stream(Class.forName(className).getDeclaredConstructors())
              .filter(value -> value.getParameterCount() == arguments.length)
              .findFirst()
              .orElseThrow();
      constructor.setAccessible(true);
      return constructor.newInstance(arguments);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static ObjectMapper eventMapper() {
    Object configuration =
        instance("com.nsangusa.news.integration.internal.EventJsonConfiguration");
    return ReflectionTestUtils.invokeMethod(configuration, "eventObjectMapper");
  }

  static IncomingEventReader reader(ObjectMapper mapper, Validator validator) {
    return (IncomingEventReader)
        instance(
            "com.nsangusa.news.eventprocessing.internal.JacksonIncomingEventReader",
            mapper,
            validator);
  }
}
