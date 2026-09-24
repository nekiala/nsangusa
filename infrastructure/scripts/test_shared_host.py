import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "shared_host", Path(__file__).with_name("check-shared-host.py"))
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)


class SharedHostGuards(unittest.TestCase):
    def setUp(self):
        self.baseline = {
            "schemaVersion": 2, "hostname": "fixture",
            "containers": {"existing": {
                "id": "original", "image": "sha256:original", "startedAt": "original",
                "restartCount": 0, "health": "healthy",
            }},
            "units": {"nginx.service": {
                "MainPID": "123", "ActiveState": "active",
                "ActiveEnterTimestampMonotonic": "12345",
            }},
            "files": {"/etc/nginx/nginx.conf": "original"},
            "symlinks": {"/etc/nginx/sites-enabled/site": "../sites-available/site"},
            "smoke": {"url": "http://127.0.0.1:3000/", "status": 200},
        }

    def test_identical_baseline_passes(self):
        HOST.compare(self.baseline, copy.deepcopy(self.baseline))

    def test_replacement_restart_image_change_and_unhealthy_state_fail(self):
        for field, value in (
            ("id", "replacement"), ("image", "sha256:new"), ("startedAt", "new"),
            ("restartCount", 1), ("health", "unhealthy"),
        ):
            with self.subTest(field=field):
                changed = copy.deepcopy(self.baseline)
                changed["containers"]["existing"][field] = value
                with self.assertRaises(RuntimeError):
                    HOST.compare(self.baseline, changed)

    def test_host_or_service_restart_fails(self):
        for field in ("hostname", "units", "smoke"):
            with self.subTest(field=field):
                changed = copy.deepcopy(self.baseline)
                changed[field] = None
                with self.assertRaises(RuntimeError):
                    HOST.compare(self.baseline, changed)

    def test_site_enablement_changes_and_legacy_baselines_fail(self):
        changed = copy.deepcopy(self.baseline)
        changed["symlinks"] = {}
        with self.assertRaises(RuntimeError):
            HOST.compare(self.baseline, changed)
        changed = copy.deepcopy(self.baseline)
        changed["schemaVersion"] = 1
        with self.assertRaises(ValueError):
            HOST.compare(changed, self.baseline)

    def test_capture_includes_link_targets_but_never_reads_certificate_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "site.conf").write_text("server {}")
            (root / "enabled-site").symlink_to("site.conf")
            (root / "private.key").write_text("synthetic key fixture")
            (root / "key-alias").symlink_to("private.key")
            outputs = [
                '{"health":"healthy"}',
                "ActiveState=active\nMainPID=123\nActiveEnterTimestampMonotonic=12345\n",
            ]
            with patch.object(HOST, "smoke", return_value={}), patch.object(
                HOST, "command", side_effect=outputs
            ):
                state = HOST.capture(["existing"], ["nginx.service"], [directory], "unused")
            self.assertEqual(state["symlinks"][str(root / "enabled-site")], "site.conf")
            self.assertEqual(state["files"][str(root / "enabled-site")],
                             state["files"][str(root / "site.conf")])
            self.assertNotIn(str(root / "private.key"), state["files"])
            self.assertNotIn(str(root / "key-alias"), state["files"])

    def test_configuration_edit_removal_and_addition_fail(self):
        for files in ({}, {"/etc/nginx/nginx.conf": "changed"},
                      {**self.baseline["files"], "/etc/nginx/new.conf": "new"}):
            with self.subTest(files=files):
                changed = copy.deepcopy(self.baseline)
                changed["files"] = files
                with self.assertRaises(RuntimeError):
                    HOST.compare(self.baseline, changed)

    def test_only_exact_approved_new_configuration_is_permitted(self):
        additions = {
            "files": {"/etc/nginx/sites-available/staging": "sha256-expected",
                      "/etc/nginx/sites-enabled/staging": "sha256-expected"},
            "symlinks": {"/etc/nginx/sites-enabled/staging": "../sites-available/staging"},
        }
        changed = copy.deepcopy(self.baseline)
        for field in additions:
            changed[field].update(additions[field])
        HOST.compare(self.baseline, changed, additions)
        for field, path in (("files", "/etc/nginx/sites-available/staging"),
                            ("symlinks", "/etc/nginx/sites-enabled/staging")):
            with self.subTest(field=field):
                invalid = copy.deepcopy(changed)
                invalid[field][path] = "unexpected"
                with self.assertRaises(RuntimeError):
                    HOST.compare(self.baseline, invalid, additions)
        with self.assertRaises(RuntimeError):
            HOST.compare(self.baseline, self.baseline, additions)

    def test_approved_additions_cannot_override_or_omit_protected_state(self):
        for additions in (
            {"files": {"/etc/nginx/nginx.conf": "changed"}, "symlinks": {}},
            {"files": {}, "symlinks": {"/etc/nginx/sites-enabled/site": "changed"}},
            {"files": {"/etc/nginx/sites-enabled/site": "changed"}, "symlinks": {}},
            {"files": {}, "symlinks": {}, "units": {}},
            {"files": {"relative": "changed"}, "symlinks": {}},
            {"files": {"/etc/nginx/../nginx/new.conf": "changed"}, "symlinks": {}},
            {"files": [], "symlinks": {}},
        ):
            with self.subTest(additions=additions), self.assertRaises(ValueError):
                HOST.compare(self.baseline, self.baseline, additions)

    def test_remote_or_credential_bearing_smoke_targets_fail_before_network_io(self):
        for url in (
            "https://example.test", "http://user:password@localhost/",
            "http://127.0.0.1/?token=secret", "http://127.0.0.1/#fragment",
            "file:///etc/passwd",
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                HOST.smoke(url)

    def test_redirects_fail_instead_of_following_an_external_target(self):
        with self.assertRaises(RuntimeError):
            HOST.NoRedirect().redirect_request(None, None, 302, "", {}, "https://example.test")

    def test_container_projection_never_requests_environment_or_health_output(self):
        self.assertNotIn(".Config", HOST.CONTAINER_FORMAT)
        self.assertNotIn(".Log", HOST.CONTAINER_FORMAT)
        self.assertNotIn(".Output", HOST.CONTAINER_FORMAT)


if __name__ == "__main__":
    unittest.main()
