import configparser
import importlib.machinery
import importlib.util
import os
from pathlib import Path
import ssl
import subprocess
import unittest
from unittest.mock import patch


ASSETS = Path(__file__).resolve().parents[1] / "nginx" / "staging"
LOADER = importlib.machinery.SourceFileLoader("acme_verify", str(ASSETS / "nsangusa-acme-verify"))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
VERIFY = importlib.util.module_from_spec(SPEC)
LOADER.exec_module(VERIFY)


class StagingHttpsGuards(unittest.TestCase):
    def test_acme_uses_an_isolated_webroot_without_nginx_or_standalone_plugins(self):
        config = configparser.ConfigParser()
        config.read_string("[certbot]\n" + (ASSETS / "cli.ini").read_text())
        options = config["certbot"]
        self.assertEqual(options["config-dir"], "/etc/nsangusa-acme")
        self.assertEqual(options["work-dir"], "/var/lib/nsangusa-acme")
        self.assertEqual(options["logs-dir"], "/var/log/nsangusa-acme")
        self.assertEqual(options["webroot-path"], "/var/www/nsangusa-acme")
        self.assertEqual(options["authenticator"], "webroot")
        self.assertEqual(options["server"], "https://acme-v02.api.letsencrypt.org/directory")
        self.assertEqual(options["email"], "contact@nsangusa.com")
        self.assertTrue(options.getboolean("agree-tos"))
        self.assertTrue(options.getboolean("non-interactive"))
        self.assertNotIn("installer", options)

    def test_only_the_staging_hostname_and_its_certificate_are_configured(self):
        for name in ("http-bootstrap.conf", "https.conf"):
            with self.subTest(name=name):
                text = (ASSETS / name).read_text()
                for line in text.splitlines():
                    if line.strip().startswith("server_name "):
                        self.assertEqual(line.strip(), "server_name staging.nsangusa.com;")
                self.assertNotIn("default_server", text)
                self.assertNotIn("/etc/letsencrypt", text)
                self.assertIn("location ^~ /.well-known/acme-challenge/", text)
                self.assertIn("try_files $uri =404;", text)
        bootstrap = (ASSETS / "http-bootstrap.conf").read_text()
        self.assertNotIn("proxy_pass", bootstrap)
        self.assertIn('return 503 "Nsangusa staging is not yet deployed.\\n";', bootstrap)
        tls = (ASSETS / "https.conf").read_text()
        self.assertIn("ssl_protocols TLSv1.2 TLSv1.3;", tls)
        self.assertIn("return 308 https://staging.nsangusa.com$request_uri;", tls)
        self.assertIn("ssl_session_tickets off;", tls)
        self.assertNotIn("includeSubDomains", tls)
        self.assertNotIn("preload", tls)
        self.assertIn('add_header Cache-Control "no-store" always;', tls)
        self.assertIn('add_header X-Robots-Tag "noindex, nofollow" always;', tls)

    def test_test_environment_is_proxied_only_to_loopback_node_ports_behind_basic_auth(self):
        tls = (ASSETS / "https.conf").read_text()
        https_server = tls[tls.index("listen 443 ssl;"):]
        targets = [line.split()[1].rstrip(";") for line in https_server.splitlines()
                   if line.strip().startswith("proxy_pass ")]
        self.assertEqual(sorted(targets), ["http://127.0.0.1:30380", "http://127.0.0.1:30381"])
        self.assertNotIn("proxy_pass", tls[:tls.index("listen 443 ssl;")])
        self.assertIn('auth_basic "Nsangusa test environment";', https_server)
        self.assertIn("auth_basic_user_file /etc/nginx/nsangusa-test.htpasswd;", https_server)
        # The only unauthenticated location is the HTTPS challenge path, which serves nothing.
        acme = https_server[https_server.index("location ^~ /.well-known/acme-challenge/"):]
        acme = acme[:acme.index("}")]
        self.assertEqual(https_server.count("auth_basic off;"), 1)
        self.assertIn("auth_basic off;", acme)
        self.assertIn("return 404;", acme)
        # Credentials stop at the proxy and clients cannot inject forwarded addresses.
        self.assertIn('proxy_set_header Authorization "";', https_server)
        self.assertIn("proxy_set_header X-Forwarded-For $remote_addr;", https_server)
        self.assertNotIn("$proxy_add_x_forwarded_for", https_server)
        self.assertIn("location ~ ^/(api|login|logout|oauth2)(/|$)", https_server)
        self.assertIn('return 503 "Nsangusa test environment is unavailable.\\n";', https_server)

    def test_renewal_is_scoped_bounded_and_does_not_force_new_certificates(self):
        service = (ASSETS / "nsangusa-acme-renew.service").read_text()
        self.assertIn("--config /etc/nsangusa-acme/cli.ini", service)
        self.assertIn("--cert-name staging.nsangusa.com", service)
        self.assertIn("--deploy-hook /usr/local/libexec/nsangusa-acme-reload", service)
        self.assertIn("ExecStartPost=/usr/local/libexec/nsangusa-acme-verify", service)
        self.assertIn("TimeoutStartSec=15min", service)
        self.assertIn("UMask=0077", service)
        self.assertIn(
            "ExecStartPre=/usr/bin/test -s /etc/nsangusa-acme/renewal/staging.nsangusa.com.conf",
            service,
        )
        self.assertNotIn("ConditionPathExists=", service)
        self.assertNotIn("--force-renewal", service)
        self.assertNotIn("--dry-run", service)
        timer = (ASSETS / "nsangusa-acme-renew.timer").read_text()
        self.assertIn("OnCalendar=*-*-* 00,12:00:00", timer)
        self.assertIn("RandomizedDelaySec=1h", timer)
        self.assertIn("Persistent=true", timer)

    def test_reload_requires_valid_certificate_and_configuration_without_restart(self):
        hook = (ASSETS / "nsangusa-acme-reload").read_text()
        self.assertIn("-checkhost staging.nsangusa.com", hook)
        self.assertIn("-checkend 604800", hook)
        self.assertIn("/usr/sbin/nginx -t", hook)
        self.assertIn("/usr/bin/systemctl reload nginx.service", hook)
        self.assertLess(hook.index("-checkhost"), hook.index("/usr/sbin/nginx -t"))
        self.assertLess(hook.index("/usr/sbin/nginx -t"), hook.index("systemctl reload"))
        self.assertNotIn("restart", hook)
        self.assertNotIn("stop", hook)

    def test_unexpected_lineages_or_domains_fail_before_certificate_or_service_access(self):
        valid = "/etc/nsangusa-acme/live/staging.nsangusa.com"
        for lineage, domains in (
            ("", ""), ("/etc/letsencrypt/live/other", "staging.nsangusa.com"),
            (valid, "other.example"), (valid, "staging.nsangusa.com other.example"),
            (valid + "/../other", "staging.nsangusa.com"),
        ):
            with self.subTest(lineage=lineage, domains=domains):
                result = subprocess.run(
                    ["sh", str(ASSETS / "nsangusa-acme-reload")],
                    env={**os.environ, "RENEWED_LINEAGE": lineage, "RENEWED_DOMAINS": domains},
                    capture_output=True, text=True, timeout=5,
                )
                self.assertEqual(result.returncode, 1)
                self.assertIn("unexpected lineage or domain", result.stderr)
                self.assertEqual(result.stdout, "")

    def test_served_certificate_must_match_disk_and_not_be_near_expiry(self):
        expiration = "Dec 21 08:28:27 2026 GMT"
        expires_at = ssl.cert_time_to_seconds(expiration)
        VERIFY.check_certificate(b"same", b"same", expiration, expires_at - 604801)
        for actual, now in ((b"old", expires_at - 604801),
                            (b"same", expires_at - 604800), (b"same", expires_at + 1)):
            with self.subTest(actual=actual, now=now), self.assertRaises(VERIFY.CertificateNotReady):
                VERIFY.check_certificate(b"same", actual, expiration, now)

    def test_graceful_reload_can_converge_but_never_masks_persistent_failure(self):
        with patch.object(VERIFY.time, "sleep"), patch.object(
            VERIFY, "probe", side_effect=[VERIFY.CertificateNotReady("old"), None]
        ) as probe:
            VERIFY.wait_for_certificate(None, b"fixture")
            self.assertEqual(probe.call_count, 2)
        with patch.object(VERIFY.time, "sleep"), patch.object(
            VERIFY, "probe", side_effect=ssl.SSLError("untrusted certificate")
        ) as probe:
            with self.assertRaisesRegex(RuntimeError, "activation failed"):
                VERIFY.wait_for_certificate(None, b"fixture")
            self.assertEqual(probe.call_count, 10)


if __name__ == "__main__":
    unittest.main()
