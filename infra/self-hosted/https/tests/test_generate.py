import configparser
import contextlib
import importlib.util
import io
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("https_generate", ROOT / "generate.py")
generate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generate)


class AddressTests(unittest.TestCase):
    def test_public_ipv4(self):
        self.assertEqual(generate.public_address("8.8.8.8", ipv4=True), "8.8.8.8")

    def test_tester_ipv6_is_canonical(self):
        self.assertEqual(
            generate.public_address("2001:4860:4860:0:0:0:0:8888"),
            "2001:4860:4860::8888",
        )

    def test_public_endpoint_must_be_ipv4(self):
        with self.assertRaises(ValueError):
            generate.public_address("2001:4860:4860::8888", ipv4=True)

    def test_refuses_nonpublic_addresses_and_networks(self):
        for value in (
            "0.0.0.0",
            "127.0.0.1",
            "10.0.0.1",
            "192.168.5.187",
            "100.88.177.63",
            "169.254.1.1",
            "192.0.2.1",
            "224.0.0.1",
            "255.255.255.255",
            "::",
            "::1",
            "fc00::1",
            "fe80::1",
            "ff02::1",
            "2001:db8::1",
            "::ffff:127.0.0.1",
            "0.0.0.0/0",
            "8.8.8.8/32",
            "2001:4860:4860::8888/128",
            "::/0",
            "*",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                generate.public_address(value)

    def test_refuses_config_injection_or_hostname(self):
        for value in (
            "example.com",
            "https://8.8.8.8",
            "8.8.8.8:443",
            " 8.8.8.8",
            "8.8.8.8\n",
            "8.8.8.8\n}\n:80 {respond hacked}",
            "8.8.8.8 1.1.1.1",
            "{env.PUBLIC_IP}",
            "2001:4860::1%eth0",
            "[2001:4860::1]",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                generate.public_address(value)


class ConfigTests(unittest.TestCase):
    def test_default_is_staging_and_health_only(self):
        config = generate.render("8.8.8.8")
        self.assertIn("dir " + generate.DIRECTORIES["staging"], config)
        self.assertIn("path /healthz\n\t\tmethod GET", config)
        self.assertNotIn("/v1/", config)
        self.assertNotIn("@rooms", config)
        self.assertNotIn("@PUBLIC_IP@", config)
        self.assertNotIn("@ACME_DIRECTORY@", config)
        self.assertNotIn("@ROOM_ROUTE@", config)

    def test_room_access_requires_exact_actual_peer_addresses(self):
        config = generate.render("8.8.8.8", ["9.9.9.9", "1.1.1.1", "9.9.9.9"])
        self.assertIn("path /v1/*\n\t\tremote_ip 1.1.1.1 9.9.9.9", config)
        self.assertEqual(config.count("path /v1/*"), 1)
        self.assertNotIn("client_ip", config)
        self.assertNotIn("trusted_proxies", config)
        self.assertNotIn("private_ranges", config)
        self.assertIn("header_up X-Forwarded-For {remote_host}", config)
        self.assertIn("header_up X-Forwarded-Proto https", config)
        self.assertIn("header_up -Forwarded", config)
        self.assertIn("header_up -X-Real-IP", config)

    def test_no_cidr_or_invalid_tester_can_open_rooms(self):
        for value in ("0.0.0.0/0", "8.8.8.8/32", "*", "127.0.0.1", "x\nrespond 200"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                generate.render("8.8.8.8", [value])

    def test_tester_count_is_bounded(self):
        with self.assertRaises(ValueError):
            generate.render("8.8.8.8", ["1.1.1.1"] * (generate.MAX_TESTERS + 1))
        self.assertIn("@rooms", generate.render("8.8.8.8", ["1.1.1.1"] * generate.MAX_TESTERS))

    def test_production_requires_explicit_choice(self):
        config = generate.render("8.8.8.8", acme="production")
        self.assertIn("dir " + generate.DIRECTORIES["production"], config)
        self.assertNotIn("acme-staging", config)
        with self.assertRaises(ValueError):
            generate.render("8.8.8.8", acme="https://another-issuer.test")

    def test_ip_certificate_has_explicit_profile_and_no_sni_default(self):
        config = generate.render("8.8.8.8")
        self.assertIn("https://8.8.8.8 {", config)
        self.assertIn("default_sni 8.8.8.8", config)
        self.assertIn("issuer acme {", config)
        self.assertIn("profile shortlived", config)
        self.assertIn("disable_tlsalpn_challenge", config)
        self.assertIn("http:// {\n\trespond 404\n}", config)
        self.assertNotIn("tls internal", config)
        self.assertNotIn("on_demand", config)
        self.assertNotIn("tls_insecure_skip_verify", config)

    def test_limits_preserve_private_backend_budgets(self):
        config = generate.render("8.8.8.8")
        for expected in (
            "reverse_proxy 127.0.0.1:18080",
            "max_size 8KiB",
            "max_header_size 16KiB",
            "read_header 5s",
            "read_body 10s",
            "write_idle 30s",
            "response_header_timeout 10s",
            "dial_timeout 3s",
            "unhealthy_request_count 64",
            "max_conns_per_host 64",
            "network_proxy none",
            "protocols h1 h2",
        ):
            self.assertIn(expected, config)
        self.assertNotIn("stream_timeout", config)
        self.assertNotIn("lb_retries", config)
        self.assertNotIn("lb_try_duration", config)

    def test_no_request_logging_or_network_admin_listener(self):
        config = generate.render("8.8.8.8")
        self.assertIn("exclude http admin.api", config)
        self.assertIn("persist_config off", config)
        self.assertIn("admin unix//run/pocketdisco-proxy/admin.sock", config)
        self.assertEqual(config.count("log "), 1)
        self.assertNotIn("log_credentials", config)
        self.assertNotIn("debug", config)
        self.assertNotIn("trace", config)


class CommandTests(unittest.TestCase):
    def test_stdout_contains_only_config(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(generate.main(["--public-ip", "8.8.8.8"]), 0)
        self.assertEqual(output.getvalue(), generate.render("8.8.8.8"))

    def test_invalid_input_never_prints_partial_config(self):
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            with self.assertRaises(SystemExit) as failure:
                generate.main(["--public-ip", "8.8.8.8", "--tester-ip", "0.0.0.0/0"])
        self.assertEqual(failure.exception.code, 2)
        self.assertEqual(output.getvalue(), "")
        self.assertNotIn("0.0.0.0/0", errors.getvalue())

    def test_template_failure_is_safe(self):
        output, errors = io.StringIO(), io.StringIO()
        with (
            contextlib.redirect_stdout(output),
            contextlib.redirect_stderr(errors),
            patch.object(Path, "read_text", side_effect=OSError("private path detail")),
            self.assertRaises(SystemExit),
        ):
            generate.main(["--public-ip", "8.8.8.8"])
        self.assertEqual(output.getvalue(), "")
        self.assertNotIn("private path detail", errors.getvalue())


class ServiceTests(unittest.TestCase):
    def test_proxy_has_its_own_identity_state_and_only_bind_capability(self):
        config = configparser.ConfigParser(interpolation=None)
        config.read(ROOT / "pocketdisco-proxy.service")
        service = config["Service"]
        for key in ("User", "Group", "StateDirectory", "RuntimeDirectory"):
            self.assertEqual(service[key], "pocketdisco-proxy")
        for key in ("StateDirectoryMode", "RuntimeDirectoryMode"):
            self.assertEqual(service[key], "0700")
        for key in ("AmbientCapabilities", "CapabilityBoundingSet"):
            self.assertEqual(service[key], "CAP_NET_BIND_SERVICE")
        self.assertEqual(service["ProtectSystem"], "strict")
        self.assertEqual(service["NoNewPrivileges"], "true")
        self.assertEqual(service["MemoryMax"], "256M")
        self.assertEqual(service["LimitNOFILE"], "2048")
        self.assertEqual(service["LimitCORE"], "0")
        self.assertNotIn("EnvironmentFile", service)
        self.assertNotIn("IPAddressDeny", service)
        self.assertIn("/etc/pocketdisco/api.env", service["InaccessiblePaths"])
        self.assertIn("/var/lib/pocketdisco", service["InaccessiblePaths"])
        self.assertIn("caddy-" + generate.CADDY_VERSION, service["ExecStart"])
        self.assertIn("unix//run/pocketdisco-proxy/admin.sock", service["ExecReload"])
        self.assertRegex(generate.LINUX_AMD64_SHA256, r"^[a-f0-9]{64}$")

    def test_proxy_trust_appends_an_environment_file_instead_of_environment(self):
        config = configparser.ConfigParser(interpolation=None)
        config.read(ROOT / "pocketdisco-api-https.conf")
        self.assertEqual(
            dict(config["Service"]),
            {"environmentfile": "/etc/pocketdisco/ingress/proxy.env"},
        )
        self.assertEqual(
            (ROOT / "proxy.env.example").read_text(),
            "POCKETDISCO_TRUSTED_PROXY_IPS=127.0.0.1\n",
        )
        existing = (ROOT.parent / "pocketdisco-api.service").read_text()
        self.assertIn("EnvironmentFile=/etc/pocketdisco/api.env", existing)


if __name__ == "__main__":
    unittest.main()
