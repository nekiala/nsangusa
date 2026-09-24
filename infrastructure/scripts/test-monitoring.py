#!/usr/bin/env python3
"""Render opt-in monitoring safely and exercise real Prometheus expressions without a cluster."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
PROMETHEUS = "prom/prometheus@sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996"
CHART = "infrastructure/helm/nsangusa"


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def render(helm, *args, succeeds=True):
    result = subprocess.run([helm, "template", "operations-test", CHART, *args],
                            cwd=ROOT, text=True, capture_output=True, timeout=30)
    check((result.returncode == 0) == succeeds, result.stderr or "unexpected render result")
    return result.stdout + result.stderr


def qualify(evidence):
    helm = os.environ.get("HELM") or shutil.which("helm")
    check(helm, "Supply HELM=/path/to/existing/helm or install the repository-pinned Helm tool")
    default = render(helm)
    check("kind: ServiceMonitor" not in default and "backend-metrics" not in default,
          "monitoring must be opt-in")
    enabled = ["--set", "monitoring.enabled=true"]
    plain = render(helm, *enabled)
    check("kind: ServiceMonitor" not in plain and "kind: PrometheusRule" not in plain,
          "default monitoring must not assume CRDs")
    check('MANAGEMENT_SERVER_PORT: "8081"' in plain and "port: management" in plain,
          "management listener/probe wiring missing")
    check("nsangusa-rules.yaml:" in plain and "nsangusa-operations.json:" in plain,
          "plain ConfigMap resources missing")
    linked = render(helm, *enabled, "--set",
                    "monitoring.runbookUrl=https://operations.example.invalid/reliability")
    check("https://operations.example.invalid/reliability#outbox-lag-or-kafka-outage" in linked,
          "deployment-specific runbook links not wired")
    for option, expected in (
        ("monitoring.serviceMonitor.enabled=true", "ServiceMonitor CRD absent"),
        ("monitoring.prometheusRule.enabled=true", "PrometheusRule CRD absent"),
        ("networkPolicy.enabled=false", "requires NetworkPolicy"),
        ("monitoring.port=8080", "8081"),
    ):
        check(expected in render(helm, *enabled, "--set", option, succeeds=False),
              f"missing fail-closed guard: {option}")
    for crd, setting in (("ServiceMonitor", "serviceMonitor"), ("PrometheusRule", "prometheusRule")):
        rendered = render(helm, *enabled, "--set", f"monitoring.{setting}.enabled=true",
                          "--api-versions", f"monitoring.coreos.com/v1/{crd}")
        check(f"kind: {crd}" in rendered, f"qualified {crd} not rendered")
    dashboard = json.loads((ROOT / CHART / "files/observability/dashboard.json").read_text())
    check(len(dashboard["panels"]) >= 10, "operational dashboard incomplete")
    evidence["helmSafetyChecks"] = 12
    name = "nsangusa-alert-test-" + uuid.uuid4().hex[:12]
    state = ROOT / ".local/operational-alert-state" / name
    state.mkdir(parents=True, mode=0o700)
    try:
        result = subprocess.run(
            ["docker", "run", "--rm", "--name", name, "--network", "none", "--read-only",
             "--memory", "256m", "--memory-swap", "256m", "--cpus", "0.5", "--pids-limit", "64",
             "--user", f"{os.getuid()}:{os.getgid()}", "-e", "TMPDIR=/state",
             "--label", f"com.nsangusa.operational-alert-test={name}",
             "-v", f"{state}:/state", "-v", f"{ROOT / 'infrastructure'}:/work:ro",
             "-w", "/work/observability",
             "--entrypoint", "/bin/promtool", PROMETHEUS, "test", "rules", "alert-tests.json"],
            cwd=ROOT, capture_output=True, text=True, timeout=120)
        evidence["promtoolOutput"] = result.stdout + result.stderr
        check(result.returncode == 0, "Prometheus rule tests failed; see evidence")
    finally:
        inspected = subprocess.run(["docker", "inspect", name], capture_output=True, cwd=ROOT)
        if inspected.returncode == 0:
            labels = json.loads(inspected.stdout)[0]["Config"]["Labels"]
            check(labels.get("com.nsangusa.operational-alert-test") == name,
                  "refusing to clean a container without this test's ownership label")
            subprocess.run(["docker", "rm", "-f", name], check=True, capture_output=True, cwd=ROOT)
        else:
            detail = inspected.stderr.decode().lower()
            check("no such object" in detail or "no such container" in detail,
                  "could not verify test container cleanup")
        shutil.rmtree(state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", help="New evidence JSON path below repository")
    args = parser.parse_args()
    os.umask(0o077)
    output = (ROOT / (args.output or
              f".local/operational-evidence/monitoring-{uuid.uuid4().hex[:12]}.json")).resolve()
    check(output.is_relative_to(ROOT) and output != ROOT and not output.exists(),
          "evidence must be a new path below repository")
    output.parent.mkdir(parents=True, exist_ok=True)
    evidence = {"status": "running", "prometheusImage": PROMETHEUS,
                "productionAlertRouting": "not-qualified",
                "limits": {"memoryMiB": 256, "cpus": 0.5, "hostPorts": [], "network": "none"},
                "assets": {name: hashlib.sha256((ROOT / CHART / "files/observability" / name).read_bytes()).hexdigest()
                           for name in ("rules.json", "dashboard.json")},
                "promtoolCases": len(json.loads((ROOT / "infrastructure/observability/alert-tests.json").read_text())["tests"])}
    try:
        qualify(evidence)
        evidence["status"] = "passed"
    except Exception as error:
        evidence["status"] = "failed"
        evidence["failure"] = str(error)
        raise
    finally:
        output.write_text(json.dumps(evidence, indent=2) + "\n")
        print(json.dumps({"status": evidence["status"], "evidence": str(output)}))


if __name__ == "__main__":
    main()
