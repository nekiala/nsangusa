package com.nsangusa.news.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nsangusa.news.contracts.StaticControllerRoutes.Route;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class StaticOpenApiContractTests {
  private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

  @Test
  void staticOpenApiMatchesEveryControllerAndSecurityFrameworkRoute() throws IOException {
    Path backend = backendRoot();
    Map<String, Object> document =
        loadOpenApi(backend.resolve("../contracts/openapi.yaml").normalize());

    assertEquals("3.1.0", document.get("openapi"));
    assertNotNull(document.get("info"));
    assertNotNull(document.get("components"));

    OpenApiRoutes openApi = openApiRoutes(document);
    Set<Route> controllers = StaticControllerRoutes.read(backend.resolve("src/main/java"));
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

  private static Set<Route> springSecurityRoutes(Path sourceRoot) throws IOException {
    Path configuration =
        sourceRoot.resolve("com/nsangusa/news/identity/internal/SecurityConfiguration.java");
    return springSecurityRoutes(Files.readString(configuration));
  }

  static Set<Route> springSecurityRoutes(String source) {
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

  private record OpenApiRoutes(Set<Route> controllerRoutes, Set<Route> frameworkRoutes) {}
}
