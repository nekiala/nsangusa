package com.nsangusa.news.aieditorial.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EditorialJsonSchema {
  private EditorialJsonSchema() {}

  static Map<String, Object> analysis(List<UUID> authorizedIds) {
    return object(
        "analysis", text(20_000),
        "claims", array(claim(authorizedIds), 1, 50),
        "confidence", confidence(),
        "warnings", array(text(500), 0, 50));
  }

  static Map<String, Object> draft(List<UUID> authorizedIds) {
    return object(
        "headline", text(300),
        "summary", text(2000),
        "body", text(30_000),
        "editorialContext", Map.of("type", List.of("string", "null"), "maxLength", 5000),
        "seoTitle", text(300),
        "seoDescription", text(500),
        "slugSuggestion", text(250),
        "tags", array(text(50), 1, 20),
        "topic", text(100),
        "sourceIds", array(sourceId(authorizedIds), 1, 20),
        "claims", array(claim(authorizedIds), 1, 50),
        "confidence", confidence(),
        "uncertaintyNotes", array(text(500), 0, 50),
        "safetyFlags", array(text(500), 0, 50),
        "humanReviewRequired", Map.of("type", "boolean"),
        "imagePrompt", text(4000),
        "imageAltText", text(500),
        "socialPreviewText", text(1000));
  }

  static Map<String, Object> safety() {
    return object("allowed", Map.of("type", "boolean"), "flags", array(text(500), 0, 50));
  }

  private static Map<String, Object> claim(List<UUID> ids) {
    return object(
        "text", text(2000),
        "classification",
            Map.of("type", "string", "enum", List.of("REPORTED", "UNVERIFIED", "DISPUTED")),
        "supportingSourceIds", array(sourceId(ids), 1, 20));
  }

  private static Map<String, Object> sourceId(List<UUID> ids) {
    return Map.of("type", "string", "enum", ids.stream().map(UUID::toString).toList());
  }

  private static Map<String, Object> confidence() {
    return Map.of("type", "number", "minimum", 0, "maximum", 1);
  }

  private static Map<String, Object> text(int max) {
    return Map.of("type", "string", "minLength", 1, "maxLength", max);
  }

  private static Map<String, Object> array(Map<String, Object> items, int min, int max) {
    return Map.of("type", "array", "items", items, "minItems", min, "maxItems", max);
  }

  private static Map<String, Object> object(Object... fields) {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (int i = 0; i < fields.length; i += 2) {
      properties.put((String) fields[i], fields[i + 1]);
    }
    return Map.of(
        "type",
        "object",
        "properties",
        properties,
        "required",
        List.copyOf(properties.keySet()),
        "additionalProperties",
        false);
  }

  // Validate the same bounded schema sent to OpenAI; strict mode is not a trust boundary.
  static void validate(JsonNode value, JsonNode schema) {
    if (value == null) {
      throw new AiProviderException("malformed_output");
    }
    JsonNode type = schema.get("type");
    if (type.isArray()) {
      if (value.isNull()) {
        return;
      }
      require(value.isTextual());
    } else {
      switch (type.asText()) {
        case "object" -> {
          require(value.isObject() && value.size() == schema.path("properties").size());
          schema
              .path("properties")
              .properties()
              .forEach(entry -> validate(value.get(entry.getKey()), entry.getValue()));
        }
        case "array" -> {
          require(value.isArray());
          require(
              value.size() >= schema.path("minItems").asInt()
                  && value.size() <= schema.path("maxItems").asInt());
          value.forEach(item -> validate(item, schema.path("items")));
        }
        case "string" -> require(value.isTextual() && !value.asText().isBlank());
        case "boolean" -> require(value.isBoolean());
        case "number" -> {
          require(value.isNumber());
          BigDecimal number = value.decimalValue();
          require(
              number.compareTo(schema.path("minimum").decimalValue()) >= 0
                  && number.compareTo(schema.path("maximum").decimalValue()) <= 0);
        }
        default -> throw new IllegalStateException("Unsupported editorial schema type");
      }
    }
    if (value.isTextual() && schema.has("maxLength")) {
      require(value.asText().length() <= schema.path("maxLength").asInt());
    }
    if (schema.has("enum")) {
      boolean matches = false;
      for (JsonNode allowed : schema.path("enum")) {
        matches |= allowed.equals(value);
      }
      require(matches);
    }
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw new AiProviderException("malformed_output");
    }
  }
}
