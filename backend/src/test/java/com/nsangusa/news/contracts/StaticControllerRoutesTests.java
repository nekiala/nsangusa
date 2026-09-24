package com.nsangusa.news.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nsangusa.news.contracts.StaticControllerRoutes.Route;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StaticControllerRoutesTests {
  @Test
  void recognizesFullMethodPathsWithoutClassMapping() throws IOException {
    assertEquals(
        Set.of(new Route("GET", "/api/v1/admin/audit-records")),
        routes(
            """
            @RestController
            @PreAuthorize("hasRole('ADMINISTRATOR')")
            class AuditController {
              @GetMapping("/api/v1/admin/audit-records")
              Object list() { return null; }
            }
            """));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        """
        @RequestMapping("/api")
        @RestController
        @PreAuthorize("hasRole('ADMINISTRATOR')")
        """,
        """
        @RestController
        @PreAuthorize("hasRole('ADMINISTRATOR')")
        @RequestMapping("/api")
        """,
        """
        @PreAuthorize("hasRole('ADMINISTRATOR')")
        @RequestMapping("/api")
        @RestController
        """
      })
  void annotationOrderingDoesNotChangeTheRoutes(String annotations) throws IOException {
    assertEquals(
        Set.of(new Route("GET", "/api/items")),
        routes(annotations + "class Items { @GetMapping(\"/items\") void list() {} }"));
  }

  @Test
  void combinesClassAndMethodMappingsUsingSpringPathSemantics() throws IOException {
    assertEquals(
        Set.of(
            new Route("GET", "/api/items/"),
            new Route("POST", "/api/items/"),
            new Route("PUT", "/api/items/{id}"),
            new Route("PATCH", "/api/items/{id}/state"),
            new Route("DELETE", "/api/items/{id}")),
        routes(
            """
            @org.springframework.web.bind.annotation.RestController
            @org.springframework.web.bind.annotation.RequestMapping(path = "api/items/")
            class Items {
              @GetMapping void list() {}
              @PostMapping() void create() {}
              @PutMapping("/{id}") void replace() {}
              @PatchMapping(value = "{id}/state") void update() {}
              @org.springframework.web.bind.annotation.DeleteMapping(path = "/{id}")
              void delete() {}
            }
            """));
  }

  @Test
  void handlesEmptyPathsAndMultipleControllersInOneSource() throws IOException {
    assertEquals(
        Set.of(new Route("GET", "/"), new Route("POST", "/other")),
        routes(
            """
            @RestController @RequestMapping()
            class Root { @GetMapping("") void root() {} }
            @RestController
            class Other { @PostMapping("other") void other() {} }
            """));
  }

  @Test
  void ignoresBracesAndFakeAnnotationsInsideCommentsAndLiterals() throws IOException {
    assertEquals(
        Set.of(new Route("GET", "/api/{id}"), new Route("GET", "/nested")),
        routes(
            """
            // @RestController @RequestMapping("/fake") class Fake {
            @RestController @RequestMapping("/api")
            class Items {
              /* } @GetMapping("/fake") */
              @GetMapping("/{id}") Object get() {
                String text = "@RestController { @GetMapping(\\"/fake\\") }";
                String block = \"""
                    } @GetMapping("/also-fake") {
                    \""";
                char brace = '}';
                if (text.isEmpty()) { return new Object() { }; }
                return null;
              }
              record Payload(String value) {}
              @RestController
              static class Nested { @GetMapping("/nested") void get() {} }
            }
            """));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "@GetMapping({\"/first\", \"/second\"})",
        "@GetMapping(PATH)",
        "@GetMapping(path = PATH)",
        "@GetMapping(value = \"/items\", params = \"active\")",
        "@GetMapping(consumes = \"application/json\")",
        "@GetMapping(\"/${items.path}\")",
        "@RequestMapping(\"/items\")",
        "@GetMapping(\"/first\") @PostMapping(\"/second\")"
      })
  void rejectsUnsupportedMethodDeclarationsRatherThanGuessingTheirPaths(String mapping) {
    var failure =
        assertThrows(
            AssertionError.class,
            () -> routes("@RestController class Items { " + mapping + " void list() {} }"));
    assertTrue(
        failure.getMessage().contains("Spring"),
        () -> "Expected an explicit mapping failure, got: " + failure.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "@RequestMapping({\"/first\", \"/second\"})",
        "@RequestMapping(BASE_PATH)",
        "@RequestMapping(value = \"/items\", headers = \"X-Items\")",
        "@RequestMapping(\"/first\") @RequestMapping(\"/second\")",
        "@GetMapping(\"/items\")"
      })
  void rejectsUnsupportedClassMappingsRatherThanTreatingThemAsAbsent(String mapping) {
    var failure =
        assertThrows(
            AssertionError.class,
            () ->
                routes(
                    "@RestController "
                        + mapping
                        + " class Items { @GetMapping(\"/items\") void list() {} }"));
    assertTrue(failure.getMessage().contains("Spring"));
  }

  @Test
  void rejectsMalformedSourceAndUnsupportedControllerDeclarations() {
    assertTrue(
        assertThrows(
                AssertionError.class,
                () ->
                    routes(
                        "@RestController class Broken { @GetMapping(\"/items\" void list() {} }"))
            .getMessage()
            .contains("Malformed controller source"));
    assertTrue(
        assertThrows(AssertionError.class, () -> routes("@RestController interface Items {}"))
            .getMessage()
            .contains("Unsupported @RestController declaration"));
    assertTrue(
        assertThrows(
                AssertionError.class, () -> routes("@RestController class Items extends Base {}"))
            .getMessage()
            .contains("Unsupported controller inheritance"));
  }

  @Test
  void rejectsMappingsOnNestedNonControllerClassesRatherThanAttributingThemToTheOuterController() {
    assertTrue(
        assertThrows(
                AssertionError.class,
                () ->
                    routes(
                        """
                        @RestController class Items {
                          static class Helper { @GetMapping("/not-a-route") void get() {} }
                        }
                        """))
            .getMessage()
            .contains("Unsupported controller or Spring mapping declaration"));
  }

  @Test
  void rejectsDuplicateRoutesEvenAcrossControllersAndAfterPathNormalization() {
    assertTrue(
        assertThrows(
                AssertionError.class,
                () ->
                    routes(
                        """
                        @RestController @RequestMapping("api/")
                        class First { @GetMapping("/items") void list() {} }
                        @RestController
                        class Second { @GetMapping("/api/items") void list() {} }
                        """))
            .getMessage()
            .contains("Duplicate controller route and HTTP method: GET /api/items"));
  }

  @Test
  void preservesTheSeparatelyEnforcedSpringSecurityRoutes() {
    assertEquals(
        Set.of(new Route("POST", "/api/v1/auth/login"), new Route("POST", "/api/v1/auth/logout")),
        StaticOpenApiContractTests.springSecurityRoutes(
            """
            http.formLogin(login -> login.loginProcessingUrl("/api/v1/auth/login"))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout"));
            """));
    assertThrows(
        AssertionError.class,
        () ->
            StaticOpenApiContractTests.springSecurityRoutes(
                "http.loginProcessingUrl(\"/login\").loginProcessingUrl(\"/other\")"));
    assertThrows(AssertionError.class, () -> StaticOpenApiContractTests.springSecurityRoutes(""));
  }

  private static Set<Route> routes(String source) throws IOException {
    return StaticControllerRoutes.read(
        List.of(
            new SimpleJavaFileObject(
                URI.create("string:///Controllers.java"), JavaFileObject.Kind.SOURCE) {
              @Override
              public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
              }
            }));
  }
}
