package com.nsangusa.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DeliveryWorkflowTests {
  private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");

  @Test
  void pullRequestsAndReleaseTagsUseTheSameMandatoryQualityWorkflow() throws IOException {
    for (String workflow : List.of("ci.yml", "images.yml")) {
      var jobs = map(workflow(workflow).get("jobs"));
      assertThat(map(jobs.get("quality")).get("uses"))
          .isEqualTo("./.github/workflows/quality-gates.yml");
    }
    assertThat(map(map(workflow("images.yml").get("jobs")).get("images")).get("needs"))
        .isEqualTo("quality");
    var jobs = map(workflow("quality-gates.yml").get("jobs"));
    var readiness = map(jobs.get("release-readiness"));
    assertThat(readiness.get("if")).isEqualTo("${{ always() }}");
    assertThat(readiness.get("needs"))
        .isEqualTo(
            List.of(
                "backend",
                "frontend",
                "fullstack",
                "contracts-and-helm",
                "dependency-scan",
                "operational"));
    assertThat(readiness.toString()).contains("test \"$result\" = success", "exit 1");
    assertThat(jobs.get("backend").toString())
        .contains(
            "docker info",
            "assert-junit.mjs",
            "ordinary",
            "test -s build/reports/cyclonedx/application.cdx.json");
    assertThat(jobs.get("fullstack").toString()).contains("fullstack-acceptance.sh");
    assertThat(jobs.get("operational").toString())
        .contains(
            "operational-drill.mjs",
            "test-monitoring.mjs",
            "node --test infrastructure/scripts/*.test.mjs");
    assertThat(jobs.toString()).doesNotContain("python");
    for (String script :
        List.of(
            "binary-qualification.test.mjs",
            "check-shared-host.test.mjs",
            "staging-https.test.mjs")) {
      assertThat(Files.exists(Path.of("..", "infrastructure", "scripts", script))).isTrue();
    }
  }

  @Test
  void externalActionsAreImmutableAndRequiredJobsCannotIgnoreFailure() throws IOException {
    for (String workflow : List.of("ci.yml", "quality-gates.yml", "images.yml", "deploy.yml")) {
      var jobs = map(workflow(workflow).get("jobs"));
      for (Object value : jobs.values()) {
        var job = map(value);
        assertThat(job.containsKey("continue-on-error")).isFalse();
        if (job.get("steps") instanceof List<?> steps) {
          for (Object stepValue : steps) {
            var step = map(stepValue);
            assertThat(step.containsKey("continue-on-error")).isFalse();
            if (step.get("uses") instanceof String action) {
              assertThat(action).matches("[\\w./-]+@[a-f0-9]{40}");
            }
          }
        }
      }
    }
  }

  @Test
  void freshAcceptanceInfrastructureCannotReusePriorLiveServiceResults() throws IOException {
    String runner =
        Files.readString(Path.of("..", ".github", "scripts", "fullstack-acceptance.sh"));
    assertThat(runner)
        .contains(
            "./gradlew test --rerun",
            "--tests '*MediaDeliveryMigrationTests'",
            "--tests '*S3MediaPersistenceTests'",
            "--tests '*MailpitNewsletterDeliveryTests'",
            "assert-junit.mjs backend/build/test-results/test live");
  }

  @Test
  void promotionRequiresTheExactQualifiedTagAndMatchingApplicationCommits() throws IOException {
    String images = Files.readString(WORKFLOWS.resolve("images.yml"));
    String deploy = Files.readString(WORKFLOWS.resolve("deploy.yml"));
    assertThat(images)
        .contains("source-ref=$GITHUB_REF", "source-commit=$GITHUB_SHA", "quality-gate=passed");
    assertThat(deploy)
        .contains(
            "--certificate-identity \"$identity\"",
            "source-ref=refs/tags/$RELEASE_TAG",
            "quality-gate=passed",
            "\"$backend_commit\" = \"$frontend_commit\"",
            "ref: ${{ steps.signatures.outputs.source-commit }}")
        .doesNotContain("--certificate-identity-regexp");
  }

  @Test
  void stagingAndProductionRequireReviewedEvidenceAndHostedProtectionInspection()
      throws IOException {
    String deploy = Files.readString(WORKFLOWS.resolve("deploy.yml"));
    assertThat(deploy)
        .contains(
            "QUALIFICATION_EVIDENCE_JSON",
            "QUALIFIED_SOURCE_COMMIT: ${{ steps.signatures.outputs.source-commit }}",
            "node .github/scripts/qualification.mjs",
            "RELEASE_GOVERNANCE_TOKEN",
            "node .github/scripts/release-controls.mjs",
            "node .github/scripts/deployment-smoke.mjs --validate-target",
            "Exercise the deployed public application");
    assertThat(deploy.indexOf("node .github/scripts/qualification.mjs"))
        .isLessThan(deploy.indexOf("helm upgrade --install"));
    assertThat(deploy.indexOf("node .github/scripts/release-controls.mjs"))
        .isLessThan(deploy.indexOf("kubectl create namespace"));
    assertThat(deploy.indexOf("node .github/scripts/deployment-smoke.mjs --validate-target"))
        .isLessThan(deploy.indexOf("kubectl create namespace"));
    var jobs = map(workflow("deploy.yml").get("jobs"));
    var steps = (List<?>) map(jobs.get("deploy")).get("steps");
    for (String name :
        List.of(
            "Require reviewed qualification evidence",
            "Inspect actual hosted release protections")) {
      var step =
          steps.stream()
              .map(DeliveryWorkflowTests::map)
              .filter(item -> name.equals(item.get("name")))
              .findFirst()
              .orElseThrow();
      assertThat(step.get("if")).isEqualTo("inputs.environment != 'test'");
    }
  }

  private static Map<?, ?> workflow(String filename) throws IOException {
    try (var reader = Files.newBufferedReader(WORKFLOWS.resolve(filename))) {
      return map(new Yaml().load(reader));
    }
  }

  private static Map<?, ?> map(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<?, ?>) value;
  }
}
