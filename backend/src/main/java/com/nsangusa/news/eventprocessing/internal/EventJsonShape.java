package com.nsangusa.news.eventprocessing.internal;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.nsangusa.news.integration.NewsEvents.ArticleDraftGenerated;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class EventJsonShape {
  private EventJsonShape() {}

  static void validate(JsonNode value, JavaType type, TypeFactory types) {
    if (value == null || value.isNull()) {
      require(!type.isPrimitive());
      return;
    }
    Class<?> raw = type.getRawClass();
    if (raw == String.class || raw == UUID.class || raw == Instant.class || raw.isEnum()) {
      require(value.isTextual());
      if (raw == UUID.class) {
        String text = value.textValue();
        require(text.equalsIgnoreCase(UUID.fromString(text).toString()));
      } else if (raw == Instant.class) {
        String text = value.textValue();
        require(
            text.matches(
                "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})"));
        OffsetDateTime.parse(text);
      }
    } else if (raw == boolean.class || raw == Boolean.class) {
      require(value.isBoolean());
    } else if (raw == int.class
        || raw == Integer.class
        || raw == long.class
        || raw == Long.class
        || raw == short.class
        || raw == Short.class
        || raw == byte.class
        || raw == Byte.class
        || raw == BigInteger.class) {
      require(value.isIntegralNumber());
    } else if (raw == double.class
        || raw == Double.class
        || raw == float.class
        || raw == Float.class
        || raw == BigDecimal.class) {
      require(value.isNumber());
    } else if (type.isCollectionLikeType()) {
      require(value.isArray());
      var distinct = new HashSet<JsonNode>();
      for (var element : value) {
        require(!element.isNull());
        if (Set.class.isAssignableFrom(raw)) require(distinct.add(element));
        validate(element, type.getContentType(), types);
      }
    } else if (type.isMapLikeType()) {
      require(value.isObject());
      value
          .elements()
          .forEachRemaining(
              element -> {
                require(!element.isNull());
                validate(element, type.getContentType(), types);
              });
    } else if (raw.isRecord()) {
      require(value.isObject());
      for (var component : raw.getRecordComponents()) {
        // Legacy v1 drafting events predate token accounting; absent counts still default to zero.
        if (raw == ArticleDraftGenerated.class
            && Set.of("inputTokens", "outputTokens").contains(component.getName())
            && !value.has(component.getName())) {
          continue;
        }
        validate(
            value.get(component.getName()),
            types.resolveMemberType(component.getGenericType(), type.getBindings()),
            types);
      }
    }
  }

  private static void require(boolean condition) {
    if (!condition)
      throw new IllegalArgumentException("Event field has an invalid JSON type or format");
  }
}
