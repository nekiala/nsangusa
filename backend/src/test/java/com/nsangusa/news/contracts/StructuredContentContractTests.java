package com.nsangusa.news.contracts;

import static com.nsangusa.news.contracts.ContractSchemas.invalid;
import static com.nsangusa.news.contracts.ContractSchemas.valid;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsangusa.news.articles.ArticleContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class StructuredContentContractTests {
  @Test
  void frozenHttpRequiredAndUnknownFieldExamplesDetectSchemaWeakening() throws Exception {
    var fixture = ContractSchemas.fixture("http.json");
    for (var sample :
        Map.of(
                "profile", "Phase4IdentityUserProfile",
                "adminUser", "Phase4IdentityAdminUser",
                "article", "Article",
                "registration", "Phase4IdentityRegistrationRequest",
                "roleChange", "Phase4IdentityRoleChange",
                "fallbackRequest", "ImageFallbackRequest")
            .entrySet()) {
      var schema = ContractSchemas.component(sample.getValue());
      ObjectNode original = fixture.required(sample.getKey()).deepCopy();
      valid(schema, original);
      var fields = original.fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        ObjectNode missing = original.deepCopy();
        missing.remove(field);
        invalid(schema, missing);
      }
      ObjectNode unknown = original.deepCopy();
      unknown.put("futureField", true);
      invalid(schema, unknown);
    }
  }

  @TestFactory
  Stream<DynamicTest> durableInvalidExamplesFailSchemaAndActualJacksonConstructor()
      throws Exception {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode example :
        ContractSchemas.fixture("http.json").required("invalidContentExamples")) {
      tests.add(
          DynamicTest.dynamicTest(
              example.required("name").asText(),
              () -> {
                JsonNode content = example.required("value");
                invalid(ContractSchemas.component("ArticleContent"), content);
                assertThrows(
                    com.fasterxml.jackson.core.JacksonException.class,
                    () -> ContractRuntime.eventMapper().treeToValue(content, ArticleContent.class));
              }));
    }
    return tests.stream();
  }

  @Test
  void blockAndListCardinalityAndUrlLengthBoundariesAreEnforced() throws Exception {
    ObjectNode content = ContractSchemas.JSON.createObjectNode().put("version", 1);
    var blocks = content.putArray("blocks");
    for (int i = 0; i < 200; i++) {
      blocks.addObject().put("type", "paragraph").put("text", "Block");
    }
    accepted(content);
    blocks.addObject().put("type", "paragraph").put("text", "Block");
    rejected(content);

    blocks.removeAll();
    var list = blocks.addObject().put("type", "unordered_list").putArray("items");
    for (int i = 0; i < 100; i++) {
      list.add("Item");
    }
    accepted(content);
    list.add("Item");
    rejected(content);

    blocks.removeAll();
    String prefix = "https://example.test/";
    ObjectNode link = blocks.addObject().put("type", "link").put("text", "Source");
    link.put("url", prefix + "a".repeat(2048 - prefix.length()));
    accepted(content);
    link.put("url", prefix + "a".repeat(2049 - prefix.length()));
    rejected(content);
  }

  @Test
  void normalizedAggregateLimitIncludesBlockListAndLinkDelimiters() throws Exception {
    ObjectNode content = ContractSchemas.JSON.createObjectNode().put("version", 1);
    var blocks = content.putArray("blocks");
    blocks.addObject().put("type", "paragraph").put("text", "a".repeat(50_000));
    ObjectNode second = blocks.addObject().put("type", "paragraph").put("text", "b".repeat(49_998));
    assertEquals(100_000, accepted(content).plainText().length());
    second.put("text", "b".repeat(49_999));
    runtimeAggregateRejection(content);

    blocks.removeAll();
    var items = blocks.addObject().put("type", "ordered_list").putArray("items");
    items.add("a".repeat(50_000)).add("b".repeat(49_999));
    assertEquals(100_000, accepted(content).plainText().length());
    items.set(1, ContractSchemas.JSON.getNodeFactory().textNode("b".repeat(50_000)));
    runtimeAggregateRejection(content);

    blocks.removeAll();
    String url = "https://example.test/source";
    ObjectNode link = blocks.addObject().put("type", "link").put("url", url);
    link.put("text", "a".repeat(100_000 - url.length() - 3));
    assertEquals(100_000, accepted(content).plainText().length());
    link.put("text", "a".repeat(100_000 - url.length() - 2));
    runtimeAggregateRejection(content);
  }

  @Test
  void schemaCannotReplaceRuntimeUriHostAndPortChecks() throws Exception {
    for (String url : List.of("https://example.test:65536/path", "https://bad_host.test/path")) {
      ObjectNode content = ContractSchemas.JSON.createObjectNode().put("version", 1);
      content
          .putArray("blocks")
          .addObject()
          .put("type", "link")
          .put("text", "Source")
          .put("url", url);
      assertThrows(
          com.fasterxml.jackson.core.JacksonException.class,
          () -> ContractRuntime.eventMapper().treeToValue(content, ArticleContent.class));
    }
  }

  @Test
  void deliberateHttpSchemaBreaksAreCaughtBySupportedV1Examples() throws Exception {
    var fixture = ContractSchemas.fixture("http.json");
    valid(ContractSchemas.component("ArticleCommandRequest"), fixture.required("articleCommand"));
    ObjectNode changed = (ObjectNode) ContractSchemas.openApi();
    ((ObjectNode) changed.at("/components/schemas/ArticleCommandRequest"))
        .withArray("required")
        .add("content");
    invalid(
        ContractSchemas.openApiSchema(changed, "#/components/schemas/ArticleCommandRequest"),
        fixture.required("articleCommand"));

    changed = (ObjectNode) ContractSchemas.openApi();
    ((ObjectNode) changed.at("/components/schemas/ArticleContent/properties/version"))
        .put("const", 2);
    invalid(
        ContractSchemas.openApiSchema(changed, "#/components/schemas/ArticleContent"),
        fixture.required("content"));

    changed = (ObjectNode) ContractSchemas.openApi();
    ((ObjectNode)
            changed.at("/components/schemas/Phase4IdentityUserProfile/properties/lastLoginAt"))
        .put("type", "string");
    invalid(
        ContractSchemas.openApiSchema(changed, "#/components/schemas/Phase4IdentityUserProfile"),
        fixture.required("profile"));

    changed = (ObjectNode) ContractSchemas.openApi();
    ((ObjectNode) changed.at("/components/schemas/Article/properties/generatedImage"))
        .put("const", true);
    invalid(
        ContractSchemas.openApiSchema(changed, "#/components/schemas/Article"),
        fixture.required("article"));
  }

  private static ArticleContent accepted(JsonNode content) throws Exception {
    valid(ContractSchemas.component("ArticleContent"), content);
    return ContractRuntime.eventMapper().treeToValue(content, ArticleContent.class);
  }

  private static void rejected(JsonNode content) {
    invalid(ContractSchemas.component("ArticleContent"), content);
    assertThrows(
        com.fasterxml.jackson.core.JacksonException.class,
        () -> ContractRuntime.eventMapper().treeToValue(content, ArticleContent.class));
  }

  private static void runtimeAggregateRejection(JsonNode content) {
    // JSON Schema cannot sum normalized strings across arrays. This is an explicit extra invariant.
    valid(ContractSchemas.component("ArticleContent"), content);
    assertThrows(
        com.fasterxml.jackson.core.JacksonException.class,
        () -> ContractRuntime.eventMapper().treeToValue(content, ArticleContent.class));
  }
}
