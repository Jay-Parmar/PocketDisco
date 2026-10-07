import configparser
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class ServiceTests(unittest.TestCase):
    def test_services_have_separate_users_and_budgets(self):
        for name, user, memory in (
            ("api", "pocketdisco", "512M"),
            ("postgres", "pocketdisco-db", "512M"),
            ("redis", "pocketdisco-redis", "256M"),
        ):
            with self.subTest(name=name):
                unit = configparser.ConfigParser(interpolation=None)
                unit.read(ROOT / f"pocketdisco-{name}.service")
                service = unit["Service"]
                self.assertEqual(service["User"], user)
                self.assertEqual(service["MemoryMax"], memory)
                self.assertEqual(service["ProtectSystem"], "strict")
                self.assertEqual(service["NoNewPrivileges"], "true")
                self.assertEqual(service["IPAddressDeny"], "any")
                self.assertEqual(service["IPAddressAllow"], "localhost")
                self.assertEqual(service["LimitCORE"], "0")
                self.assertEqual(service["Restart"], "on-failure")
                self.assertEqual(unit["Unit"]["StartLimitBurst"], "5")

    def test_api_reads_root_owned_environment(self):
        unit = configparser.ConfigParser(interpolation=None)
        unit.read(ROOT / "pocketdisco-api.service")
        service = unit["Service"]
        self.assertEqual(service["EnvironmentFile"], "/etc/pocketdisco/api.env")
        self.assertIn("/current/.venv/bin/python -m pocketdisco", service["ExecStart"])
        self.assertGreaterEqual(int(service["TimeoutStopSec"]), 30)
        self.assertNotIn("ReadWritePaths", service)

    def test_postgres_is_separate_and_hides_queries(self):
        config = (ROOT / "postgres.conf").read_text()
        for expected in (
            "listen_addresses = '127.0.0.1'",
            "port = 55432",
            "max_connections = 30",
            "log_min_error_statement = 'panic'",
            "log_statement = 'none'",
            "log_parameter_max_length = 0",
            "log_parameter_max_length_on_error = 0",
            "log_error_verbosity = 'terse'",
            "update_process_title = off",
        ):
            self.assertIn(expected, config)
        self.assertNotIn("/var/lib/postgresql", config)

    def test_postgres_auth_only_allows_named_local_roles(self):
        records = (ROOT / "pg_hba.conf").read_text().splitlines()
        self.assertEqual(
            records,
            [
                "local all pocketdisco-db peer",
                "host pocketdisco pocketdisco_runtime 127.0.0.1/32 scram-sha-256",
                "host pocketdisco pocketdisco_migrate 127.0.0.1/32 scram-sha-256",
            ],
        )

    def test_redis_is_ephemeral_authenticated_and_bounded(self):
        config = (ROOT / "redis.conf").read_text()
        for expected in (
            "bind 127.0.0.1",
            "port 56379",
            "protected-mode yes",
            "aclfile /etc/pocketdisco/redis.acl",
            "maxclients 128",
            "maxmemory 128mb",
            "maxmemory-policy noeviction",
            'save ""',
            "appendonly no",
            "slowlog-log-slower-than -1",
        ):
            self.assertIn(expected, config)
        self.assertNotIn("requirepass", config)


if __name__ == "__main__":
    unittest.main()
