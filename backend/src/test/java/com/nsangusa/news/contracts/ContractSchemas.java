package com.nsangusa.news.contracts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.org.apache.commons.validator.routines.EmailValidator;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.format.EmailFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

final class ContractSchemas {
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Path ROOT = contractsRoot();
  private static final Pattern EMAIL_DOMAIN =
      Pattern.compile(
          "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
              + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
  private static final JsonSchemaFactory FACTORY = schemaFactory();
  private static final SchemaValidatorsConfig CONFIG =
      SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
  private static final JsonNode OPEN_API = readOpenApi();

  private ContractSchemas() {}

  private static JsonSchemaFactory schemaFactory() {
    // JSON Schema email checks syntax, not the library's registry of delegated TLDs.
    var email =
        new EmailFormat(
            new EmailValidator(true, true) {
              @Override
              protected boolean isValidDomain(String domain) {
                if (domain.startsWith("[")) {
                  return super.isValidDomain(domain.replaceFirst("(?i)^\\[IPv6:", "["));
                }
                return domain.length() <= 253 && EMAIL_DOMAIN.matcher(domain).matches();
              }
            });
    var dialect = JsonMetaSchema.builder(JsonMetaSchema.getV202012()).format(email).build();
    return JsonSchemaFactory.getInstance(
        SpecVersion.VersionFlag.V202012, builder -> builder.metaSchema(dialect));
  }

  static JsonNode fixture(String file) throws IOException {
    return JSON.readTree(ROOT.resolve("compatibility/v1").resolve(file).toFile());
  }

  static JsonNode openApi() {
    return OPEN_API.deepCopy();
  }

  static JsonSchema component(String name) {
    return openApiSchema(OPEN_API, "#/components/schemas/" + name);
  }

  static JsonSchema openApiSchema(JsonNode document, String pointer) {
    ObjectNode root = JSON.createObjectNode();
    root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    root.put("$ref", pointer);
    root.set("components", document.required("components"));
    root.set("paths", document.required("paths"));
    return compile(root);
  }

  static JsonSchema request(String path, String method) {
    return openApiSchema(
        OPEN_API,
        "#/paths/" + escape(path) + "/" + method + "/requestBody/content/application~1json/schema");
  }

  static JsonSchema response(String path, String method, int status, String mediaType) {
    JsonNode response =
        OPEN_API.path("paths").path(path).path(method).path("responses").path("" + status);
    if (response.isMissingNode()) {
      response = OPEN_API.path("paths").path(path).path(method).path("responses").path("default");
    }
    assertTrue(!response.isMissingNode(), "Undocumented response " + status + " " + path);
    if (response.has("$ref")) {
      response = OPEN_API.at(response.required("$ref").asText().substring(1));
    }
    JsonNode schema = response.path("content").path(mediaType).path("schema");
    assertTrue(
        !schema.isMissingNode(), "Undocumented response media type " + mediaType + " " + path);
    ObjectNode root = JSON.createObjectNode();
    root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    root.set("components", OPEN_API.required("components"));
    root.set("allOf", JSON.createArrayNode().add(schema));
    return compile(root);
  }

  static Map<String, ObjectNode> eventDocuments() throws IOException {
    Map<String, ObjectNode> documents = new LinkedHashMap<>();
    try (var files = Files.list(ROOT.resolve("events"))) {
      for (Path file :
          files.filter(path -> path.toString().endsWith(".schema.json")).sorted().toList()) {
        documents.put(file.getFileName().toString(), (ObjectNode) JSON.readTree(file.toFile()));
      }
    }
    return documents;
  }

  static JsonSchema event(Map<String, ObjectNode> documents, String filename) {
    ObjectNode root = JSON.createObjectNode();
    root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    root.put("$ref", "#/$defs/" + escape(filename));
    ObjectNode definitions = root.putObject("$defs");
    documents.forEach(
        (name, document) -> {
          ObjectNode local = document.deepCopy();
          local.remove("$id");
          localizeReferences(local);
          definitions.set(name, local);
        });
    return compile(root);
  }

  static JsonSchema compile(JsonNode schema) {
    return FACTORY.getSchema(schema, CONFIG);
  }

  static void valid(JsonSchema schema, JsonNode value) {
    var errors = schema.validate(value);
    assertTrue(errors.isEmpty(), () -> "Contract rejected payload:\n" + errors + "\n" + value);
  }

  static void invalid(JsonSchema schema, JsonNode value) {
    assertTrue(
        !schema.validate(value).isEmpty(), () -> "Contract accepted invalid payload: " + value);
  }

  private static void localizeReferences(JsonNode node) {
    if (node instanceof ObjectNode object && object.has("$ref")) {
      String reference = object.required("$ref").asText();
      String[] parts = reference.split("#", 2);
      // Every event reference must resolve inside the checked-in bundle. Never fetch the network.
      assertTrue(
          parts[0].matches("[a-z-]+\\.schema\\.json"),
          "Only local event schema references are permitted: " + reference);
      object.put("$ref", "#/$defs/" + escape(parts[0]) + (parts.length == 2 ? parts[1] : ""));
    }
    node.forEach(ContractSchemas::localizeReferences);
  }

  private static String escape(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private static JsonNode readOpenApi() {
    try (var input = Files.newBufferedReader(ROOT.resolve("openapi.yaml"))) {
      return JSON.valueToTree(new Yaml().load(input));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static Path contractsRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    Path root = Files.isDirectory(current.resolve("contracts")) ? current : current.getParent();
    assertTrue(Files.isDirectory(root.resolve("contracts")), "Cannot locate contracts directory");
    return root.resolve("contracts");
  }
}
