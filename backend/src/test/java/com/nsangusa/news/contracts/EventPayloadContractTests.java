package com.nsangusa.news.contracts;

import static com.nsangusa.news.contracts.ContractSchemas.invalid;
import static com.nsangusa.news.contracts.ContractSchemas.valid;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.nsangusa.news.eventprocessing.IncomingEventReader;
import com.nsangusa.news.integration.EventCatalog;
import com.nsangusa.news.integration.NewsEvents;
import com.nsangusa.news.integration.NewsEvents.ArticleImageApproved;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class EventPayloadContractTests {
  private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
  private static final ObjectMapper MAPPER = ContractRuntime.eventMapper();
  private static final IncomingEventReader READER =
      ContractRuntime.reader(MAPPER, VALIDATORS.getValidator());

  @AfterAll
  static void closeValidator() {
    VALIDATORS.close();
  }

  @Test
  void everyCatalogPayloadHasExactlyOneSchemaAndOneDurableFixture() throws Exception {
    Map<String, ObjectNode> documents = ContractSchemas.eventDocuments();
    Set<String> catalog = new HashSet<>();
    for (Class<?> payload : NewsEvents.class.getDeclaredClasses()) {
      if (Set.of("SourceReference", "Claim").contains(payload.getSimpleName())) {
        continue;
      }
      assertEquals(payload, EventCatalog.require(payload.getSimpleName()).payloadType());
      catalog.add(payload.getSimpleName());
    }
    // Also catch additions to EventCatalog that do not live inside NewsEvents.
    var definitions = EventCatalog.class.getDeclaredField("EVENTS");
    definitions.setAccessible(true);
    assertEquals(catalog, ((Map<?, ?>) definitions.get(null)).keySet());
    Set<String> examples = new HashSet<>();
    for (JsonNode fixture : ContractSchemas.fixture("events.json").required("events")) {
      assertTrue(examples.add(fixture.required("type").asText()), "Duplicate event fixture");
      assertTrue(documents.containsKey(fixture.required("schema").asText()));
    }
    assertEquals(catalog, examples);
    Set<String> schemaTypes = new HashSet<>();
    documents.forEach(
        (name, document) -> {
          if (!Set.of("common.schema.json", "envelope.schema.json").contains(name)) {
            assertTrue(
                schemaTypes.add(document.at("/allOf/1/properties/eventType/const").asText()));
          }
        });
    assertEquals(catalog, schemaTypes);
  }

  @TestFactory
  Stream<DynamicTest> frozenProducerExamplesAndCurrentJacksonRoundTrips() throws Exception {
    var corpus = ContractSchemas.fixture("events.json");
    var schemas = ContractSchemas.eventDocuments();
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode fixture : corpus.required("events")) {
      String type = fixture.required("type").asText();
      JsonSchema schema = ContractSchemas.event(schemas, fixture.required("schema").asText());
      ObjectNode envelope = envelope(corpus, fixture);
      tests.add(
          DynamicTest.dynamicTest(
              type + ": frozen producer -> current consumer -> current producer",
              () -> accepted(schema, envelope)));
      for (JsonNode optional : fixture.required("optional")) {
        ObjectNode omitted = envelope.deepCopy();
        ((ObjectNode) omitted.required("payload")).remove(optional.asText());
        tests.add(
            DynamicTest.dynamicTest(
                type + ": absent optional " + optional.asText(), () -> accepted(schema, omitted)));
      }
      ObjectNode noCause = envelope.deepCopy();
      noCause.remove("causationId");
      tests.add(
          DynamicTest.dynamicTest(
              type + ": absent nullable causationId", () -> accepted(schema, noCause)));
    }
    return tests.stream();
  }

  @TestFactory
  Stream<DynamicTest> allCatalogTypesRejectMalformedEnvelopesAndPayloads() throws Exception {
    var corpus = ContractSchemas.fixture("events.json");
    var schemas = ContractSchemas.eventDocuments();
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode fixture : corpus.required("events")) {
      String type = fixture.required("type").asText();
      JsonSchema schema = ContractSchemas.event(schemas, fixture.required("schema").asText());
      ObjectNode original = envelope(corpus, fixture);
      for (JsonNode example : corpus.required("invalidEnvelopeExamples")) {
        ObjectNode malformed = original.deepCopy();
        malformed.set(example.required("field").asText(), example.required("value"));
        reject(tests, type + ": " + example.required("name").asText(), schema, malformed, type);
      }
      for (JsonNode field : corpus.required("requiredEnvelopeFields")) {
        requiredFieldMutations(tests, type, schema, original, "", field.asText());
      }
      Set<String> optional = new HashSet<>();
      fixture.required("optional").forEach(field -> optional.add(field.asText()));
      fixture
          .required("payload")
          .fieldNames()
          .forEachRemaining(
              field -> {
                if (!optional.contains(field)) {
                  requiredFieldMutations(tests, type, schema, original, "/payload", field);
                }
              });
      ObjectNode unknown = original.deepCopy();
      ((ObjectNode) unknown.required("payload")).put("futureField", true);
      reject(tests, type + ": unknown payload field", schema, unknown, type);
      for (var component : EventCatalog.require(type).payloadType().getRecordComponents()) {
        if (component.getType().equals(UUID.class) || component.getType().equals(Instant.class)) {
          ObjectNode malformed = original.deepCopy();
          ((ObjectNode) malformed.required("payload"))
              .put(
                  component.getName(),
                  component.getType().equals(UUID.class) ? "1-1-1-1-1" : "2026-02-30T00:00:00Z");
          reject(tests, type + ": canonical " + component.getName(), schema, malformed, type);
        }
      }
    }
    return tests.stream();
  }

  @Test
  void nestedSourceAndClaimContractsAreNotIgnored() throws Exception {
    var corpus = ContractSchemas.fixture("events.json");
    var fixture = find(corpus, "ArticleDraftRequested");
    var schema =
        ContractSchemas.event(ContractSchemas.eventDocuments(), fixture.get("schema").asText());
    for (String pointer : List.of("/payload/sources/0", "/payload/claims/0")) {
      ObjectNode envelope = envelope(corpus, fixture);
      ((ObjectNode) envelope.at(pointer)).put("futureField", true);
      rejected(schema, envelope, "ArticleDraftRequested");
    }
    for (String field : List.of("sourcePostId", "account", "postId", "url", "publishedAt")) {
      ObjectNode envelope = envelope(corpus, fixture);
      ((ObjectNode) envelope.at("/payload/sources/0")).remove(field);
      rejected(schema, envelope, "ArticleDraftRequested");
    }
    for (double confidence : new double[] {-0.01, 1.01}) {
      ObjectNode envelope = envelope(corpus, fixture);
      ((ObjectNode) envelope.required("payload")).put("confidence", confidence);
      rejected(schema, envelope, "ArticleDraftRequested");
    }
    ObjectNode invalidClaim = envelope(corpus, fixture);
    ((ObjectNode) invalidClaim.at("/payload/claims/0")).put("classification", "CERTAIN");
    rejected(schema, invalidClaim, "ArticleDraftRequested");
    ObjectNode tooManySources = envelope(corpus, fixture);
    var sources =
        (com.fasterxml.jackson.databind.node.ArrayNode) tooManySources.at("/payload/sources");
    while (sources.size() <= 20) {
      sources.add(sources.get(0).deepCopy());
    }
    rejected(schema, tooManySources, "ArticleDraftRequested");
  }

  @Test
  void explicitLegacyImageConsumerMatrixDoesNotConcealUnsupportedNewProducerDirection()
      throws Exception {
    var corpus = ContractSchemas.fixture("events.json");
    var fixture = find(corpus, "ArticleImageApproved");
    var schema =
        ContractSchemas.event(ContractSchemas.eventDocuments(), fixture.get("schema").asText());
    var legacy =
        ContractSchemas.compile(ContractSchemas.fixture("legacy-image-consumer.schema.json"));
    ObjectNode oldEnvelope = envelope(corpus, fixture);
    ObjectNode oldPayload = (ObjectNode) oldEnvelope.required("payload");
    oldPayload.remove("generatedImage");
    valid(legacy, oldPayload);
    accepted(schema, oldEnvelope);
    assertNull(
        READER.read(oldEnvelope.toString(), ArticleImageApproved.class).payload().generatedImage());
    LegacyImageApproval oldConsumer = MAPPER.treeToValue(oldPayload, LegacyImageApproval.class);
    var compatibilityConstructor =
        new ArticleImageApproved(
            oldConsumer.articleId(),
            oldConsumer.generationId(),
            oldConsumer.objectKey(),
            oldConsumer.altText(),
            oldConsumer.approvedBy(),
            oldConsumer.approvedAt());
    assertEquals(Boolean.TRUE, compatibilityConstructor.generatedImage());

    for (JsonNode value :
        List.of(
            ContractSchemas.JSON.nullNode(),
            ContractSchemas.JSON.getNodeFactory().booleanNode(true),
            ContractSchemas.JSON.getNodeFactory().booleanNode(false))) {
      ObjectNode current = oldEnvelope.deepCopy();
      ((ObjectNode) current.required("payload")).set("generatedImage", value);
      accepted(schema, current);
      invalid(legacy, current.required("payload"));
      assertThrows(
          com.fasterxml.jackson.core.JacksonException.class,
          () -> MAPPER.treeToValue(current.required("payload"), LegacyImageApproval.class));
    }
  }

  @Test
  void breakingSchemaEditsAreDetectedByFrozenExamplesNotOnlySchemaLint() throws Exception {
    var corpus = ContractSchemas.fixture("events.json");
    var fixture = find(corpus, "ArticleImageApproved");
    ObjectNode old = envelope(corpus, fixture);
    ((ObjectNode) old.required("payload")).remove("generatedImage");
    var documents = ContractSchemas.eventDocuments();
    String filename = fixture.required("schema").asText();
    valid(ContractSchemas.event(documents, filename), old);
    var payload = (ObjectNode) documents.get(filename).at("/allOf/1/properties/payload");
    payload.withArray("required").add("generatedImage");
    invalid(ContractSchemas.event(documents, filename), old);

    documents = ContractSchemas.eventDocuments();
    ((ObjectNode)
            documents.get(filename).at("/allOf/1/properties/payload/properties/generatedImage"))
        .put("type", "string");
    invalid(ContractSchemas.event(documents, filename), envelope(corpus, fixture));

    documents = ContractSchemas.eventDocuments();
    ((ObjectNode) documents.get(filename).at("/allOf/1/properties/schemaVersion")).put("const", 2);
    invalid(ContractSchemas.event(documents, filename), envelope(corpus, fixture));

    ObjectNode unknown = envelope(corpus, fixture);
    ((ObjectNode) unknown.required("payload")).put("futureField", true);
    documents = ContractSchemas.eventDocuments();
    invalid(ContractSchemas.event(documents, filename), unknown);
    ((ObjectNode) documents.get(filename).at("/allOf/1/properties/payload"))
        .put("additionalProperties", true);
    valid(ContractSchemas.event(documents, filename), unknown);
    assertThrows(
        RuntimeException.class, () -> READER.read(unknown.toString(), ArticleImageApproved.class));
  }

  static ObjectNode envelope(JsonNode corpus, JsonNode fixture) {
    ObjectNode envelope = corpus.required("envelope").deepCopy();
    envelope.put("eventType", fixture.required("type").asText());
    envelope.set("payload", fixture.required("payload").deepCopy());
    return envelope;
  }

  static JsonNode find(JsonNode corpus, String eventType) {
    for (JsonNode fixture : corpus.required("events")) {
      if (eventType.equals(fixture.required("type").asText())) {
        return fixture;
      }
    }
    throw new AssertionError("Missing fixture " + eventType);
  }

  private static void accepted(JsonSchema schema, ObjectNode json) throws Exception {
    valid(schema, json);
    String type = json.required("eventType").asText();
    var event = READER.read(json.toString(), EventCatalog.require(type).payloadType());
    assertTrue(VALIDATORS.getValidator().validate(event).isEmpty());
    JsonNode serialized = MAPPER.readTree(MAPPER.writeValueAsString(event));
    valid(schema, serialized);
    assertEquals(
        event, READER.read(serialized.toString(), EventCatalog.require(type).payloadType()));
  }

  private static void requiredFieldMutations(
      List<DynamicTest> tests,
      String type,
      JsonSchema schema,
      ObjectNode original,
      String pointer,
      String field) {
    ObjectNode missing = original.deepCopy();
    ((ObjectNode) missing.at(pointer)).remove(field);
    reject(tests, type + ": missing " + pointer + "/" + field, schema, missing, type);
    ObjectNode nullValue = original.deepCopy();
    ((ObjectNode) nullValue.at(pointer)).putNull(field);
    reject(tests, type + ": null " + pointer + "/" + field, schema, nullValue, type);
  }

  private static void reject(
      List<DynamicTest> tests, String name, JsonSchema schema, ObjectNode malformed, String type) {
    tests.add(DynamicTest.dynamicTest(name, () -> rejected(schema, malformed, type)));
  }

  private static void rejected(JsonSchema schema, ObjectNode malformed, String type) {
    invalid(schema, malformed);
    assertThrows(
        RuntimeException.class,
        () -> READER.read(malformed.toString(), EventCatalog.require(type).payloadType()),
        () -> "Runtime accepted malformed " + type + ": " + malformed);
  }

  private record LegacyImageApproval(
      UUID articleId,
      UUID generationId,
      String objectKey,
      String altText,
      UUID approvedBy,
      Instant approvedAt) {}
}
