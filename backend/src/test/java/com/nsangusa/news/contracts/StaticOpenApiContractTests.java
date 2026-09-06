package com.nsangusa.news.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class StaticOpenApiContractTests {
  private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
  private static final Pattern CONTROLLER =
      Pattern.compile(
          "@RestController\\b\\s*@RequestMapping\\(\\s*\"([^\"]+)\"\\s*\\).*?\\bclass\\s+\\w+[^\\{]*\\{",
          Pattern.DOTALL);
  private static final Pattern MAPPING =
      Pattern.compile(
          "@(?:org\\.springframework\\.web\\.bind\\.annotation\\.)?"
              + "(Get|Post|Put|Patch|Delete)Mapping"
              + "(?:\\s*\\(\\s*\"([^\"]*)\"\\s*\\))?");
  private static final Pattern ANY_MAPPING =
      Pattern.compile(
          "@(?:org\\.springframework\\.web\\.bind\\.annotation\\.)?"
              + "(?:Get|Post|Put|Patch|Delete|Request)Mapping\\b");

  @Test
  void staticOpenApiMatchesEveryControllerAndSecurityFrameworkRoute() throws IOException {
    Path backend = backendRoot();
    Map<String, Object> document =
        loadOpenApi(backend.resolve("../contracts/openapi.yaml").normalize());

    assertEquals("3.1.0", document.get("openapi"));
    assertNotNull(document.get("info"));
    assertNotNull(document.get("components"));

    OpenApiRoutes openApi = openApiRoutes(document);
    Set<Route> controllers = controllerRoutes(backend.resolve("src/main/java"));
    Set<Route> framework = springSecurityRoutes(backend.resolve("src/main/java"));

    assertRoutesEqual("controller routes", controllers, openApi.controllerRoutes());
    assertRoutesEqual("framework-provided routes", framework, openApi.frameworkRoutes());
    assertTrue(
        openApi.controllerRoutes().stream().noneMatch(openApi.frameworkRoutes()::contains),
        "A route cannot be both controller-provided and framework-provided");
    validateLocalReferences(document, document);
  }

  private static Path backendRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    if (Files.isDirectory(current.resolve("src/main/java"))) {
      return current;
    }
    Path nested = current.resolve("backend");
    assertTrue(Files.isDirectory(nested.resolve("src/main/java")), "Cannot locate backend module");
    return nested;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadOpenApi(Path contract) throws IOException {
    assertTrue(Files.isRegularFile(contract), "Missing static OpenAPI contract: " + contract);
    Object loaded;
    try (var reader = Files.newBufferedReader(contract)) {
      loaded = new Yaml().load(reader);
    }
    assertTrue(loaded instanceof Map<?, ?>, "OpenAPI document must be a YAML object");
    return (Map<String, Object>) loaded;
  }

  @SuppressWarnings("unchecked")
  private static OpenApiRoutes openApiRoutes(Map<String, Object> document) {
    Object rawPaths = document.get("paths");
    assertTrue(rawPaths instanceof Map<?, ?>, "OpenAPI paths must be an object");
    Set<Route> controllerRoutes = new HashSet<>();
    Set<Route> frameworkRoutes = new HashSet<>();
    Set<String> operationIds = new HashSet<>();

    ((Map<String, Object>) rawPaths)
        .forEach(
            (path, rawPathItem) -> {
              assertTrue(path.startsWith("/"), "OpenAPI path must start with '/': " + path);
              assertTrue(rawPathItem instanceof Map<?, ?>, "Path item must be an object: " + path);
              ((Map<String, Object>) rawPathItem)
                  .forEach(
                      (method, rawOperation) -> {
                        if (!HTTP_METHODS.contains(method)) {
                          return;
                        }
                        assertTrue(
                            rawOperation instanceof Map<?, ?>,
                            "Operation must be an object: " + method + " " + path);
                        Map<String, Object> operation = (Map<String, Object>) rawOperation;
                        String operationId = (String) operation.get("operationId");
                        assertNotNull(operationId, "Missing operationId: " + method + " " + path);
                        assertTrue(
                            operationIds.add(operationId), "Duplicate operationId: " + operationId);
                        assertTrue(
                            operation.get("responses") instanceof Map<?, ?>,
                            "Missing responses: " + method + " " + path);
                        Route route = new Route(method.toUpperCase(), path);
                        if ("spring-security".equals(operation.get("x-framework-provided"))) {
                          frameworkRoutes.add(route);
                        } else {
                          controllerRoutes.add(route);
                        }
                      });
            });
    return new OpenApiRoutes(controllerRoutes, frameworkRoutes);
  }

  private static Set<Route> controllerRoutes(Path sourceRoot) throws IOException {
    Set<Route> routes = new HashSet<>();
    int controllerAnnotations = 0;
    int parsedControllers = 0;
    int mappingAnnotations = 0;
    int parsedMappings = 0;

    try (Stream<Path> files = Files.walk(sourceRoot)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        controllerAnnotations += countMatches(Pattern.compile("@RestController\\b"), source);
        var controller = CONTROLLER.matcher(source);
        while (controller.find()) {
          parsedControllers++;
          String basePath = controller.group(1);
          int bodyStart = controller.end() - 1;
          int bodyEnd = matchingBrace(source, bodyStart);
          String body = source.substring(bodyStart + 1, bodyEnd);
          mappingAnnotations += countMatches(ANY_MAPPING, body);

          var mapping = MAPPING.matcher(body);
          while (mapping.find()) {
            parsedMappings++;
            String suffix = mapping.group(2) == null ? "" : mapping.group(2);
            routes.add(new Route(mapping.group(1).toUpperCase(), joinPaths(basePath, suffix)));
          }
        }
      }
    }

    assertEquals(
        controllerAnnotations, parsedControllers, "Unsupported @RestController declaration");
    assertEquals(mappingAnnotations, parsedMappings, "Unsupported Spring mapping declaration");
    assertEquals(parsedMappings, routes.size(), "Duplicate controller route and HTTP method");
    return routes;
  }

  private static Set<Route> springSecurityRoutes(Path sourceRoot) throws IOException {
    Path configuration =
        sourceRoot.resolve("com/nsangusa/news/identity/internal/SecurityConfiguration.java");
    String source = Files.readString(configuration);
    return Set.of(
        new Route("POST", extractPath(source, "loginProcessingUrl")),
        new Route("POST", extractPath(source, "logoutUrl")));
  }

  private static String extractPath(String source, String method) {
    var matcher = Pattern.compile("\\." + method + "\\(\\s*\"([^\"]+)\"\\s*\\)").matcher(source);
    assertTrue(matcher.find(), "Cannot find Spring Security " + method);
    String path = matcher.group(1);
    assertTrue(!matcher.find(), "Multiple Spring Security " + method + " declarations");
    return path;
  }

  private static int matchingBrace(String source, int openingBrace) {
    int depth = 0;
    boolean string = false;
    boolean character = false;
    boolean lineComment = false;
    boolean blockComment = false;
    boolean escaped = false;

    for (int index = openingBrace; index < source.length(); index++) {
      char current = source.charAt(index);
      char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
      if (lineComment) {
        if (current == '\n') {
          lineComment = false;
        }
        continue;
      }
      if (blockComment) {
        if (current == '*' && next == '/') {
          blockComment = false;
          index++;
        }
        continue;
      }
      if (string || character) {
        if (escaped) {
          escaped = false;
        } else if (current == '\\') {
          escaped = true;
        } else if (string && current == '"') {
          string = false;
        } else if (character && current == '\'') {
          character = false;
        }
        continue;
      }
      if (current == '/' && next == '/') {
        lineComment = true;
        index++;
      } else if (current == '/' && next == '*') {
        blockComment = true;
        index++;
      } else if (current == '"') {
        string = true;
      } else if (current == '\'') {
        character = true;
      } else if (current == '{') {
        depth++;
      } else if (current == '}' && --depth == 0) {
        return index;
      }
    }
    throw new AssertionError("Unmatched controller class brace");
  }

  private static String joinPaths(String base, String suffix) {
    if (suffix.isEmpty()) {
      return base;
    }
    return base.endsWith("/") || suffix.startsWith("/") ? base + suffix : base + "/" + suffix;
  }

  private static int countMatches(Pattern pattern, String value) {
    int count = 0;
    var matcher = pattern.matcher(value);
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  private static void assertRoutesEqual(String label, Set<Route> expected, Set<Route> actual) {
    Set<Route> missing = new HashSet<>(expected);
    missing.removeAll(actual);
    Set<Route> extra = new HashSet<>(actual);
    extra.removeAll(expected);
    assertTrue(
        missing.isEmpty() && extra.isEmpty(),
        () ->
            label
                + " differ from contracts/openapi.yaml"
                + "\nMissing from OpenAPI: "
                + sorted(missing)
                + "\nNot implemented: "
                + sorted(extra));
  }

  private static List<String> sorted(Set<Route> routes) {
    return routes.stream().map(Route::toString).sorted().toList();
  }

  @SuppressWarnings("unchecked")
  private static void validateLocalReferences(Object node, Map<String, Object> document) {
    if (node instanceof Map<?, ?> map) {
      Object reference = map.get("$ref");
      if (reference instanceof String value && value.startsWith("#/")) {
        Object target = document;
        for (String segment : value.substring(2).split("/")) {
          assertTrue(target instanceof Map<?, ?>, "Invalid local OpenAPI reference: " + value);
          target =
              ((Map<String, Object>) target).get(segment.replace("~1", "/").replace("~0", "~"));
          assertNotNull(target, "Unresolved local OpenAPI reference: " + value);
        }
      }
      map.values().forEach(value -> validateLocalReferences(value, document));
    } else if (node instanceof List<?> list) {
      list.forEach(value -> validateLocalReferences(value, document));
    }
  }

  private record Route(String method, String path) {
    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  private record OpenApiRoutes(Set<Route> controllerRoutes, Set<Route> frameworkRoutes) {}
}
