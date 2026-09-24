package com.nsangusa.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SharedHostStagingConfigurationTests {
  private static final Path ASSETS = Path.of("..", "infrastructure", "k3s", "staging");

  @Test
  void clusterKeepsItsRuntimeAndIngressSeparateFromExistingDockerWorkloads() throws IOException {
    var config = configuration("config.yaml");
    assertThat(config.get("disable")).isEqualTo(List.of("traefik", "servicelb"));
    assertThat(config.get("disable-network-policy")).isEqualTo(false);
    assertThat(config.get("secrets-encryption")).isEqualTo(true);
    assertThat(config.get("secrets-encryption-provider")).isEqualTo("aescbc");
    assertThat(config.get("write-kubeconfig-mode")).isEqualTo("0600");
    assertThat(config.get("flannel-backend")).isEqualTo("host-gw");
    assertThat(config.get("bind-address")).isEqualTo(config.get("node-ip"));
    assertThat(config.get("advertise-address")).isEqualTo(config.get("node-ip"));
    assertThat(config.get("kube-proxy-arg").toString())
        .contains(
            "proxy-mode=iptables",
            "nodeport-addresses=127.0.0.0/8",
            "iptables-localhost-nodeports=true",
            "healthz-bind-address=127.0.0.1:10256");
    for (String key :
        List.of(
            "docker", "container-runtime-endpoint", "image-service-endpoint", "node-external-ip")) {
      assertThat(config.containsKey(key)).as(key).isFalse();
    }
  }

  @Test
  void guardPrecedesServerAndCannotReplaceTheSharedHostsFirewall() throws IOException {
    String service = Files.readString(ASSETS.resolve("k3s.service"));
    assertThat(service)
        .contains(
            "Requires=nsangusa-k3s-guard.service",
            "After=network-online.target docker.service nsangusa-k3s-guard.service",
            "KillMode=process",
            "MemoryMax=4G");
    String guard = Files.readString(ASSETS.resolve("guard.nft"));
    var config = configuration("config.yaml");
    assertThat(guard)
        .contains(
            config.get("cluster-cidr").toString(),
            config.get("service-cidr").toString(),
            "iifname \"" + config.get("flannel-iface") + "\"",
            "destroy table inet nsangusa_k3s_guard",
            "type filter hook prerouting priority -110; policy accept",
            "type filter hook input priority -10; policy accept",
            "type filter hook forward priority -10; policy accept",
            "127.0.0.0/8",
            "10.43.0.0/16",
            "ct state established,related return",
            "iifname \"cni0\" ip saddr 10.42.0.0/16 tcp dport { 6443, 10250 }",
            "tcp dport 30000-32767 counter drop",
            "udp dport 30000-32767 counter drop",
            "sctp dport 30000-32767 counter drop")
        .doesNotContain("flush ruleset", "DOCKER-USER", "DOCKER-FORWARD", "policy drop");
    assertThat(Files.readString(ASSETS.resolve("nsangusa-k3s-guard.service")))
        .contains("ExecStartPre=/usr/sbin/nft --check", "Before=k3s.service")
        .doesNotContain("ExecStop=");
  }

  @Test
  void auditRecordsMetadataRatherThanCredentialBearingRequestBodies() throws IOException {
    var audit = configuration("audit-policy.yaml");
    assertThat(audit.get("kind")).isEqualTo("Policy");
    var rules = (List<?>) audit.get("rules");
    assertThat(rules).hasSize(2);
    assertThat(((Map<?, ?>) rules.get(0)).get("level")).isEqualTo("None");
    assertThat(((Map<?, ?>) rules.get(1)).get("level")).isEqualTo("Metadata");
    assertThat(Files.readString(ASSETS.resolve("config.yaml")))
        .contains("audit-log-maxbackup=5", "audit-log-maxsize=10");
  }

  @Test
  void disposableNetworkProbesUseRestrictedPodsAndAPinnedRuntime() throws IOException {
    try (var reader = Files.newBufferedReader(ASSETS.resolve("qualification.yaml"))) {
      int pods = 0;
      for (Object document : new Yaml().loadAll(reader)) {
        var resource = (Map<?, ?>) document;
        if ("Pod".equals(resource.get("kind"))) {
          pods++;
          var spec = (Map<?, ?>) resource.get("spec");
          assertThat(spec.containsKey("hostNetwork")).isFalse();
          assertThat(((Map<?, ?>) spec.get("securityContext")).get("runAsNonRoot")).isEqualTo(true);
          for (Object containerValue : (List<?>) spec.get("containers")) {
            var container = (Map<?, ?>) containerValue;
            assertThat(container.get("image").toString()).contains("@sha256:");
            assertThat(
                    ((Map<?, ?>) container.get("securityContext")).get("allowPrivilegeEscalation"))
                .isEqualTo(false);
          }
        }
      }
      assertThat(pods).isEqualTo(2);
    }
    assertThat(configuration("qualification-deny.yaml").get("spec").toString())
        .contains("ingress=[]", "policyTypes=[Ingress]");
    assertThat(configuration("qualification-egress-deny.yaml").get("spec").toString())
        .contains("egress=[]", "policyTypes=[Egress]");
  }

  private static Map<?, ?> configuration(String filename) throws IOException {
    try (var reader = Files.newBufferedReader(ASSETS.resolve(filename))) {
      return new Yaml().load(reader);
    }
  }
}
