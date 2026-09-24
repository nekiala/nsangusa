package com.nsangusa.news.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.springframework.web.util.pattern.PathPatternParser;

final class StaticControllerRoutes {
  private static final String SPRING_ANNOTATIONS = "org.springframework.web.bind.annotation.";
  private static final Map<String, String> METHODS =
      Map.of(
          "GetMapping", "GET",
          "PostMapping", "POST",
          "PutMapping", "PUT",
          "PatchMapping", "PATCH",
          "DeleteMapping", "DELETE");

  private StaticControllerRoutes() {}

  static Set<Route> read(Path sourceRoot) throws IOException {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "Static route verification requires a JDK");
    try (var files = Files.walk(sourceRoot);
        var fileManager = compiler.getStandardFileManager(null, null, null)) {
      return read(
          fileManager.getJavaFileObjectsFromPaths(
              files.filter(path -> path.toString().endsWith(".java")).sorted().toList()));
    }
  }

  static Set<Route> read(Iterable<? extends JavaFileObject> sources) throws IOException {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "Static route verification requires a JDK");
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
      var task =
          (JavacTask)
              compiler.getTask(
                  null, fileManager, diagnostics, List.of("-proc:none"), null, sources);
      // Let Java syntax own annotation and body boundaries, without compiling or starting Spring.
      Iterable<? extends CompilationUnitTree> units = task.parse();
      assertTrue(
          diagnostics.getDiagnostics().stream()
              .noneMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR),
          () -> "Malformed controller source: " + diagnostics.getDiagnostics());
      var scanner = new ControllerScanner();
      for (CompilationUnitTree unit : units) {
        scanner.scan(unit, null);
      }
      return Set.copyOf(scanner.routes);
    }
  }

  private static final class ControllerScanner extends TreeScanner<Void, Void> {
    private final Set<Route> routes = new HashSet<>();
    private final Set<AnnotationTree> supportedAnnotations = new HashSet<>();
    private int controllerDepth;

    @Override
    public Void visitClass(ClassTree declaration, Void unused) {
      var annotations = declaration.getModifiers().getAnnotations();
      var controllers =
          annotations.stream().filter(annotation -> named(annotation, "RestController")).toList();
      if (controllers.isEmpty()) {
        return super.visitClass(declaration, unused);
      }
      assertEquals(
          1, controllers.size(), "Unsupported @RestController declaration: " + declaration);
      assertEquals(
          Tree.Kind.CLASS,
          declaration.getKind(),
          "Unsupported @RestController declaration: " + declaration.getSimpleName());
      assertTrue(
          declaration.getExtendsClause() == null && declaration.getImplementsClause().isEmpty(),
          "Unsupported controller inheritance: " + declaration.getSimpleName());
      supportedAnnotations.addAll(controllers);
      var classMappings = mappings(annotations);
      assertTrue(classMappings.size() <= 1, "Multiple Spring class mappings: " + classMappings);
      String basePath = "";
      if (!classMappings.isEmpty()) {
        var mapping = classMappings.getFirst();
        assertTrue(
            named(mapping, "RequestMapping"), "Unsupported Spring class mapping: " + mapping);
        basePath = literalPath(mapping);
        supportedAnnotations.add(mapping);
      }

      for (Tree member : declaration.getMembers()) {
        if (!(member instanceof MethodTree method)) {
          continue;
        }
        var methodMappings = mappings(method.getModifiers().getAnnotations());
        assertTrue(
            methodMappings.size() <= 1, "Multiple Spring method mappings: " + methodMappings);
        for (AnnotationTree mapping : methodMappings) {
          String httpMethod = METHODS.get(annotationName(mapping));
          assertNotNull(httpMethod, "Unsupported Spring mapping declaration: " + mapping);
          Route route = new Route(httpMethod, joinPaths(basePath, literalPath(mapping)));
          assertTrue(routes.add(route), "Duplicate controller route and HTTP method: " + route);
          supportedAnnotations.add(mapping);
        }
      }
      controllerDepth++;
      super.visitClass(declaration, unused);
      controllerDepth--;
      return null;
    }

    @Override
    public Void visitAnnotation(AnnotationTree annotation, Void unused) {
      if (named(annotation, "RestController") || (controllerDepth > 0 && isMapping(annotation))) {
        assertTrue(
            supportedAnnotations.contains(annotation),
            "Unsupported controller or Spring mapping declaration: " + annotation);
      }
      return super.visitAnnotation(annotation, unused);
    }
  }

  private static List<? extends AnnotationTree> mappings(
      List<? extends AnnotationTree> annotations) {
    return annotations.stream().filter(StaticControllerRoutes::isMapping).toList();
  }

  private static boolean isMapping(AnnotationTree annotation) {
    return named(annotation, "RequestMapping") || METHODS.containsKey(annotationName(annotation));
  }

  private static boolean named(AnnotationTree annotation, String name) {
    return name.equals(annotationName(annotation));
  }

  private static String annotationName(AnnotationTree annotation) {
    String name = annotation.getAnnotationType().toString();
    return name.startsWith(SPRING_ANNOTATIONS) ? name.substring(SPRING_ANNOTATIONS.length()) : name;
  }

  private static String literalPath(AnnotationTree mapping) {
    var arguments = mapping.getArguments();
    if (arguments.isEmpty()) {
      return "";
    }
    assertEquals(1, arguments.size(), "Unsupported Spring mapping declaration: " + mapping);
    ExpressionTree value = arguments.getFirst();
    if (value instanceof AssignmentTree assignment) {
      assertTrue(
          Set.of("value", "path").contains(assignment.getVariable().toString()),
          "Unsupported Spring mapping declaration: " + mapping);
      value = assignment.getExpression();
    }
    assertTrue(
        value instanceof LiteralTree literal && literal.getValue() instanceof String,
        "Unsupported Spring mapping path (expected one literal): " + mapping);
    String path = (String) ((LiteralTree) value).getValue();
    assertTrue(
        !path.contains("${") && !path.contains("#{"),
        "Unsupported dynamic Spring mapping path: " + mapping);
    return path;
  }

  private static String joinPaths(String base, String suffix) {
    var parser = PathPatternParser.defaultInstance;
    String path =
        parser.parse(fullPath(base)).combine(parser.parse(fullPath(suffix))).getPatternString();
    return path.isEmpty() ? "/" : path;
  }

  private static String fullPath(String path) {
    return path.isEmpty() || path.startsWith("/") ? path : "/" + path;
  }

  record Route(String method, String path) {
    @Override
    public String toString() {
      return method + " " + path;
    }
  }
}
