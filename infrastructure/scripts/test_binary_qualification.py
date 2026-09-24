"""Driver guards and bounded Node HTTP unit fixtures; no Docker, application starts, or builds."""
import argparse
from contextlib import redirect_stderr
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

MODULE = Path(__file__).with_name("binary-qualification.py")
SPEC = importlib.util.spec_from_file_location("binary_qualification", MODULE)
BINARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BINARY)


class BinaryQualificationGuards(unittest.TestCase):
    def test_requires_full_immutable_references(self):
        digest = "a" * 64
        self.assertEqual(BINARY.immutable_reference("sha256:" + digest), "sha256:" + digest)
        self.assertEqual(BINARY.immutable_reference("example.invalid/backend@sha256:" + digest),
                         "example.invalid/backend@sha256:" + digest)
        for value in ("backend:latest", "backend:previous", "sha256:1234", "--privileged",
                      "backend@sha256:" + "a" * 63):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                BINARY.immutable_reference(value)

    def test_cannot_claim_transition_with_reused_image(self):
        images = {name: {"id": "sha256:" + "a" * 64} for name in
                  ("previous-backend", "previous-frontend", "candidate-backend", "candidate-frontend")}
        with self.assertRaises(RuntimeError):
            BINARY.assert_distinct_images(images)

    def test_rejects_existing_stack_names_and_ports(self):
        for project in ("nsangusa-preview", "nsangusa-phase4", "nsangusa-binary-other", "../../other"):
            with self.subTest(project=project), self.assertRaises(RuntimeError):
                BINARY.checked_project(project)
        for port in ("3000", "8080", "18025", "5432", "0x1234", "-1", "65536"):
            with self.subTest(port=port), self.assertRaises(argparse.ArgumentTypeError):
                BINARY.checked_port(port)
        self.assertEqual(BINARY.checked_port("0"), 0)
        self.assertEqual(BINARY.checked_port("18026"), 18026)

    def test_ownership_requires_exact_inventory_name_and_both_labels(self):
        project = "nsangusa-binary-0123456789ab"
        name = project + "-postgres"
        metadata = {"Name": "/" + name, "Config": {"Labels": {
            BINARY.OWNER: project, "com.docker.compose.project": project}}}
        BINARY.owned(metadata, "container", name, project)
        for key in (BINARY.OWNER, "com.docker.compose.project"):
            modified = {"Name": "/" + name, "Config": {"Labels": dict(metadata["Config"]["Labels"])}}
            modified["Config"]["Labels"][key] = "nsangusa-preview"
            with self.subTest(label=key), self.assertRaises(RuntimeError):
                BINARY.owned(modified, "container", name, project)
        with self.assertRaises(RuntimeError):
            BINARY.owned(metadata, "container", project + "-foreign", project)

    def test_active_credentials_or_work_cannot_qualify_live_rollback(self):
        empty = {"liveSetups": 0, "credentials": 0, "liveSettings": 0, "pendingAiRequests": 0}
        BINARY.assert_no_live_state(empty)
        for field in ("liveSetups", "credentials", "liveSettings", "pendingAiRequests"):
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                BINARY.assert_no_live_state({**empty, field: 1})
        for state in ({}, None, {**empty, "credentials": False}, {**empty, "credentials": "0"}):
            with self.subTest(state=state), self.assertRaises(RuntimeError):
                BINARY.assert_no_live_state(state)

    def test_snapshot_preserves_business_fields_and_records_monotonic_source_observations(self):
        source = {"id": "source-1", "version": 0, "last_checked_at": None, "permitted_text": "retained"}
        original = {"source_posts": [source], "articles": [{"body": "retained article", "version": 7}]}
        baseline, observations = BINARY.snapshot_projection(original)
        refreshed = {**original, "source_posts": [
            {**source, "version": 1, "last_checked_at": "2026-09-21T00:01:00+00:00"}]}
        current, observed = BINARY.snapshot_projection(refreshed, observations)
        self.assertEqual(current, baseline)
        self.assertEqual(source["version"], 0)
        self.assertEqual(observed["source-1"]["version"], 1)
        changed = {**refreshed, "source_posts": [
            {**refreshed["source_posts"][0], "permitted_text": "unexpected edit"}]}
        self.assertNotEqual(BINARY.snapshot_projection(changed, observed)[0], baseline)
        for invalid in (
            {**source, "version": 0, "last_checked_at": "2026-09-21T00:02:00+00:00"},
            {**source, "version": 2, "last_checked_at": "2026-09-21T00:00:00+00:00"},
            {**source, "version": 2, "last_checked_at": None},
            {**source, "version": 1, "last_checked_at": "2026-09-21T00:01:00"},
        ):
            with self.subTest(invalid=invalid), self.assertRaises(RuntimeError):
                BINARY.snapshot_projection({**original, "source_posts": [invalid]}, observed)

    def test_generated_compose_is_isolated_bounded_fake_and_build_free(self):
        names = list(BINARY.DEPENDENCIES) + [
            "previous-backend", "previous-frontend", "candidate-backend", "candidate-frontend"]
        images = {name: {"id": "sha256:" + f"{index:064x}"} for index, name in enumerate(names)}
        ports = {"frontend": 13000, "backend": 18080, "management": 18081, "mailpit": 18026}
        document = BINARY.compose_document("nsangusa-binary-0123456789ab", images, ports, "candidate", True)
        self.assertTrue(document["networks"]["isolated"]["internal"])
        for name, service in document["services"].items():
            with self.subTest(service=name):
                self.assertNotIn("build", service)
                self.assertEqual(service["pull_policy"], "never")
                self.assertEqual(service["networks"], ["isolated"])
                self.assertIn("mem_limit", service)
                self.assertIn("cpus", service)
                for port in service.get("ports", []):
                    self.assertEqual(port["host_ip"], "127.0.0.1")
        backend = document["services"]["backend"]
        self.assertEqual(backend["environment"]["AI_LIVE_ENABLED"], "false")
        self.assertEqual(backend["environment"]["PROVIDER_MODE"], "fake")
        self.assertEqual(backend["environment"]["LOCAL_SEED"], "false")
        self.assertEqual(backend["environment"]["MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED"], "false")
        self.assertIn("${BINARY_MASTER_KEY:", backend["environment"]["AI_CREDENTIAL_MASTER_KEY"])
        self.assertNotIn("ports", document["services"]["postgres"])
        previous = BINARY.compose_document("nsangusa-binary-0123456789ab", images, ports, "previous", False)
        self.assertEqual(previous["services"]["backend"]["environment"]["SPRING_FLYWAY_ENABLED"], "false")
        self.assertEqual(previous["services"]["backend"]["image"], images["previous-backend"]["id"])
        self.assertEqual(len(document["services"]["kafka"]["volumes"]), 3)
        self.assertEqual(len(document["services"]["kafka-init"]["volumes"]), 3)
        internal = BINARY.compose_document(
            "nsangusa-binary-0123456789ab", images, ports, "candidate", True, "internal")
        self.assertTrue(internal["networks"]["isolated"]["internal"])
        self.assertTrue(all("ports" not in service for service in internal["services"].values()))
        self.assertEqual(internal["services"]["backend"]["environment"]["PUBLIC_BASE_URL"],
                         "http://127.0.0.1:13000")

    def test_preflight_refusal_never_cleans_preexisting_resources(self):
        qualification = BINARY.Qualification.__new__(BINARY.Qualification)
        qualification.created = False
        qualification.evidence = {}
        qualification.save = lambda: None
        qualification.resources = lambda: self.fail("preflight refusal must not enumerate/delete resources")
        qualification.cleanup()
        self.assertTrue(qualification.evidence["cleanup"]["notStarted"])

    def test_all_four_application_inputs_are_mandatory(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            BINARY.parser().parse_args(["--output", ".local/example"])

    def test_internal_transport_requires_explicit_option_and_never_retargets_services(self):
        args = ["--output", ".local/example"]
        for name in ("previous-backend", "previous-frontend", "candidate-backend", "candidate-frontend"):
            args.extend(["--" + name, "sha256:" + "a" * 64])
        self.assertEqual(BINARY.parser().parse_args(args).http_transport, "host")
        self.assertEqual(BINARY.parser().parse_args(args + ["--http-transport", "internal"]).http_transport,
                         "internal")
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            BINARY.parser().parse_args(args + ["--http-transport", "auto"])
        ports = {"frontend": 13000, "backend": 18080, "management": 18081, "mailpit": 18026}
        for service, origin in BINARY.INTERNAL_HTTP_ORIGINS.items():
            self.assertEqual(BINARY.http_target("internal", service, "/probe", ports), origin + "/probe")
        self.assertEqual(BINARY.http_target("host", "backend", "/probe", ports),
                         "http://127.0.0.1:18080/probe")
        for service, path in (("foreign", "/probe"), ("backend", "//foreign/"),
                              ("backend", "http://foreign/"), ("backend", "/bad\r\nheader"),
                              ("backend", "/bad\\path"), ("backend", "/" + "a" * 2048)):
            with self.subTest(service=service, path=path), self.assertRaises(RuntimeError):
                BINARY.http_target("internal", service, path, ports)

    def internal_qualification(self):
        qualification = BINARY.Qualification.__new__(BINARY.Qualification)
        qualification.args = SimpleNamespace(http_transport="internal")
        qualification.project = "nsangusa-binary-0123456789ab"
        qualification.ports = {"backend": 18080}
        qualification.images = {
            "candidate-frontend": {"id": "sha256:" + "a" * 64},
            "previous-frontend": {"id": "sha256:" + "b" * 64},
        }
        name = qualification.project + "-frontend"
        qualification.inspect = Mock(return_value={
            "Name": "/" + name, "Config": {"Labels": {
                BINARY.OWNER: qualification.project, "com.docker.compose.project": qualification.project}},
            "State": {"Running": True}, "Image": qualification.images["candidate-frontend"]["id"],
            "NetworkSettings": {"Networks": {qualification.project + "-internal": {}}}})
        qualification.docker = Mock(return_value=subprocess.CompletedProcess(
            [], 0, json.dumps({"status": 401, "headers": {"www-authenticate": "Basic"},
                               "bodyBase64": base64.b64encode(b"denied").decode(), "bytes": 6}).encode(), b""))
        qualification.http = Mock()
        return qualification

    def test_prometheus_requests_allow_exposition_and_api_authentication_errors(self):
        qualification = self.internal_qualification()
        for service in ("backend", "management"):
            with self.subTest(service=service):
                qualification.request(service, "/actuator/prometheus")
                payload = json.loads(qualification.docker.call_args.kwargs["data"])
                self.assertEqual(payload["headers"]["Accept"], "*/*")
                self.assertEqual(payload["headers"]["Accept-Encoding"], "identity")

    def test_internal_http_exec_is_owned_bounded_and_preserves_response_fields(self):
        qualification = self.internal_qualification()
        status, headers, body = qualification.request("backend", "/api/v1/admin/users")
        self.assertEqual((status, headers, body), (401, {"www-authenticate": "Basic"}, b"denied"))
        arguments, options = qualification.docker.call_args
        self.assertEqual(arguments[:5], ("exec", "-i", qualification.project + "-frontend", "node", "-e"))
        self.assertEqual(arguments[5], BINARY.NODE_HTTP_PROBE)
        payload = json.loads(options["data"])
        self.assertEqual(payload["url"], "http://backend:8080/api/v1/admin/users")
        self.assertEqual(payload["maxBytes"], 2 * 1024 * 1024)
        self.assertEqual(payload["timeoutMs"], 15000)
        self.assertEqual(options["timeout"], 20)
        self.assertFalse(options["check"])
        qualification.http.open.assert_not_called()
        qualification.docker.return_value = subprocess.CompletedProcess(
            [], 2, b'{"error":"unavailable"}', b"")
        with self.assertRaises(BINARY.HttpTransportUnavailable):
            qualification.request("backend", "/api/v1/admin/users")
        qualification.http.open.assert_not_called()

    def test_internal_http_refuses_foreign_or_replaced_probe_container(self):
        for alteration in ("owner", "network", "image", "running"):
            qualification = self.internal_qualification()
            metadata = qualification.inspect.return_value
            if alteration == "owner":
                metadata["Config"]["Labels"][BINARY.OWNER] = "nsangusa-preview"
            elif alteration == "network":
                metadata["NetworkSettings"]["Networks"]["foreign"] = {}
            elif alteration == "image":
                metadata["Image"] = "sha256:" + "c" * 64
            else:
                metadata["State"]["Running"] = False
            with self.subTest(alteration=alteration), self.assertRaises(RuntimeError):
                qualification.request("backend", "/probe")
            qualification.docker.assert_not_called()

    def test_internal_http_result_cannot_bypass_size_or_format_guards(self):
        normal = {"status": 200, "headers": {}, "bodyBase64": "eA==", "bytes": 1}
        for result in ({**normal, "bytes": 2097153}, {**normal, "bytes": 0},
                       {**normal, "status": True}, {**normal, "bodyBase64": "!not-base64!"},
                       {**normal, "headers": {"bad": None}}, {**normal, "error": "timeout"}, {}):
            with self.subTest(result=result), self.assertRaises(RuntimeError):
                BINARY.decode_internal_response(subprocess.CompletedProcess(
                    [], 0, json.dumps(result).encode(), b""))
        with self.assertRaises(BINARY.HttpTransportUnavailable):
            BINARY.decode_internal_response(subprocess.CompletedProcess(
                [], 2, b'{"error":"timeout"}', b""))


class NodeHttpProbeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.node = shutil.which("node")
        if not cls.node:
            raise RuntimeError("Node on PATH is required for real HTTP probe unit tests")
        cls.hits = {}

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                cls.hits[self.path] = cls.hits.get(self.path, 0) + 1
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/redirect-target")
                    self.end_headers()
                    return
                if self.path == "/deny":
                    self.send_response(403)
                    self.send_header("X-Probe", "actual-unit-http")
                    self.end_headers()
                    self.wfile.write(b"denied")
                    return
                self.send_response(200)
                self.send_header("X-Probe", "actual-unit-http")
                if self.path == "/declared-large":
                    self.send_header("Content-Length", str(BINARY.HTTP_MAX_RESPONSE_BYTES + 1))
                self.end_headers()
                try:
                    if self.path == "/stream-large":
                        for _ in range(33):
                            self.wfile.write(b"x" * 65536)
                    elif self.path == "/slow":
                        for _ in range(30):
                            self.wfile.write(b"x")
                            self.wfile.flush()
                            time.sleep(0.05)
                    elif self.path != "/declared-large":
                        self.wfile.write(b"\x00binary\xff")
                except (BrokenPipeError, ConnectionResetError):
                    pass

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, kwargs={"poll_interval": 0.05})
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)
        if cls.thread.is_alive():
            raise RuntimeError("HTTP unit fixture did not stop")

    def probe(self, path, **overrides):
        payload = {"url": f"http://127.0.0.1:{self.server.server_port}{path}",
                   "headers": {"Accept-Encoding": "identity"},
                   "maxBytes": BINARY.HTTP_MAX_RESPONSE_BYTES, "timeoutMs": 15000, **overrides}
        return subprocess.run([self.node, "-e", BINARY.NODE_HTTP_PROBE],
                              input=json.dumps(payload).encode(), capture_output=True, timeout=3,
                              env={**os.environ, "NODE_OPTIONS": ""})

    def test_reads_actual_status_headers_and_binary_body(self):
        status, headers, body = BINARY.decode_internal_response(self.probe("/ok"))
        self.assertEqual(status, 200)
        self.assertEqual(headers["x-probe"], "actual-unit-http")
        self.assertEqual(body, b"\x00binary\xff")
        status, headers, body = BINARY.decode_internal_response(self.probe("/deny"))
        self.assertEqual((status, body), (403, b"denied"))

    def test_redirect_is_returned_without_following_it(self):
        before = self.hits.get("/redirect-target", 0)
        status, headers, body = BINARY.decode_internal_response(self.probe("/redirect"))
        self.assertEqual((status, headers["location"], body), (302, "/redirect-target", b""))
        self.assertEqual(self.hits.get("/redirect-target", 0), before)

    def test_refuses_declared_and_streamed_oversize_responses(self):
        for path in ("/declared-large", "/stream-large"):
            with self.subTest(path=path):
                response = self.probe(path)
                self.assertEqual(response.returncode, 2)
                self.assertEqual(json.loads(response.stdout)["error"], "response_too_large")

    def test_deadline_covers_continuously_streaming_response(self):
        response = self.probe("/slow", timeoutMs=150)
        self.assertEqual(response.returncode, 2)
        self.assertEqual(json.loads(response.stdout)["error"], "timeout")

    def test_caller_cannot_increase_hard_limits(self):
        for limits in ({"maxBytes": 2097153}, {"timeoutMs": 15001}, {"timeoutMs": 0}):
            with self.subTest(limits=limits):
                response = self.probe("/ok", **limits)
                self.assertEqual(response.returncode, 2)
                self.assertEqual(json.loads(response.stdout)["error"], "invalid_request")


if __name__ == "__main__":
    unittest.main()
