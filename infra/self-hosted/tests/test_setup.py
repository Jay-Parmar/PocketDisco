import hashlib
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
TEMP = ROOT.parents[1] / ".local-tools" / "self-hosted-tests"
SPEC = importlib.util.spec_from_file_location("native_setup", ROOT / "setup.py")
setup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(setup)


class SetupTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        TEMP.mkdir(parents=True, exist_ok=True)

    def test_runtime_env_never_receives_migration_password(self):
        files = setup.secret_files("a" * 64, "b" * 64, "c" * 64)
        self.assertIn("pocketdisco_runtime:" + "a" * 64, files["api.env"])
        self.assertNotIn("b" * 64, files["api.env"])
        self.assertIn("pocketdisco_migrate:" + "b" * 64, files["migrate.env"])
        self.assertNotIn("a" * 64, files["migrate.env"])
        self.assertNotIn("c" * 64, files["migrate.env"])
        self.assertIn("POCKETDISCO_PORT=18080", files["api.env"])
        self.assertIn("POCKETDISCO_TRUSTED_PROXY_IPS=\n", files["api.env"])

    def test_redis_auth_file_contains_only_hash_and_needed_commands(self):
        acl = setup.secret_files("a" * 64, "b" * 64, "c" * 64)["redis.acl"]
        self.assertIn("#" + hashlib.sha256(("c" * 64).encode()).hexdigest(), acl)
        self.assertNotIn("c" * 64, acl)
        self.assertIn("user default off", acl)
        self.assertIn("~pocketdisco:v1:* &pocketdisco:v1:*", acl)
        self.assertIn("-@all", acl)
        self.assertIn("+eval", acl)
        self.assertIn("+getdel", acl)
        for command in ("+@all", "+config", "+monitor", "+acl", "+flushall", "+shutdown"):
            self.assertNotIn(command, acl)

    def test_roles_are_limited_and_other_databases_are_denied(self):
        sql = setup.bootstrap_sql("a" * 64, "b" * 64)
        self.assertEqual(sql.count("NOSUPERUSER NOCREATEDB NOCREATEROLE"), 2)
        self.assertIn("REVOKE ALL ON DATABASE pocketdisco FROM PUBLIC", sql)
        self.assertIn("REVOKE ALL ON DATABASE postgres FROM PUBLIC", sql)
        self.assertIn("REVOKE ALL ON DATABASE template1 FROM PUBLIC", sql)
        self.assertNotIn("GRANT ALL", sql)
        self.assertIn("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES", sql)
        self.assertIn("ALTER DEFAULT PRIVILEGES FOR ROLE pocketdisco_migrate", sql)
        self.assertIn("REVOKE ALL ON SCHEMA public FROM PUBLIC", sql)
        self.assertNotIn("GRANT CREATE", sql)

    def test_rejects_non_generated_passwords(self):
        with self.assertRaises(ValueError):
            setup.bootstrap_sql("'; SELECT 1; --", "b" * 64)
        with self.assertRaises(ValueError):
            setup.secret_files("a" * 64, "b" * 64, "not generated")

    def test_refuses_existing_paths_without_changing_them(self):
        with tempfile.TemporaryDirectory(dir=TEMP) as directory:
            path = Path(directory) / "api.env"
            path.write_text("original")
            with self.assertRaises(RuntimeError):
                setup.refuse_existing([path])
            with self.assertRaises(FileExistsError):
                setup.write_new(path, "replacement", 0o600)
            self.assertEqual(path.read_text(), "original")

    def test_new_secret_uses_private_descriptor_permissions(self):
        with tempfile.TemporaryDirectory(dir=TEMP) as directory:
            path = Path(directory) / "api.env"
            with (
                patch.object(setup.os, "fchown", create=True) as chown,
                patch.object(setup.os, "fchmod", create=True) as chmod,
            ):
                setup.write_new(path, "secret\n", 0o600)
            self.assertEqual(path.read_text(), "secret\n")
            self.assertEqual(chown.call_args.args[1:], (0, 0))
            self.assertEqual(chmod.call_args.args[1:], (0o600,))

    def test_command_failure_does_not_echo_input_or_output(self):
        result = subprocess.CompletedProcess([], 1, "sensitive output", "sensitive SQL")
        with (
            patch.object(setup.subprocess, "run", return_value=result) as run,
            self.assertRaisesRegex(RuntimeError, "database setup failed") as error,
        ):
            setup.run(["psql"], "database setup", input="password secret")
        self.assertNotIn("sensitive", str(error.exception))
        self.assertNotIn("secret", str(error.exception))
        self.assertEqual(run.call_args.kwargs["stdout"], subprocess.DEVNULL)
        self.assertEqual(run.call_args.kwargs["stderr"], subprocess.DEVNULL)
        self.assertNotIn("secret", repr(run.call_args.args))

    def test_schema_marker_is_read_only_for_runtime(self):
        self.assertIn(
            "REVOKE INSERT, UPDATE, DELETE ON public.alembic_version FROM pocketdisco_runtime",
            setup.POST_MIGRATION_SQL,
        )

    def test_root_owned_release_symlink_checks_target_permissions(self):
        with (
            patch.object(Path, "lstat", return_value=SimpleNamespace(st_uid=0, st_mode=0o777)),
            patch.object(Path, "stat", return_value=SimpleNamespace(st_uid=0, st_mode=0o755)),
        ):
            setup.require_root_owned(Path("/opt/pocketdisco/current"))

    def test_release_target_must_not_be_shared_writable(self):
        with (
            patch.object(Path, "lstat", return_value=SimpleNamespace(st_uid=0, st_mode=0o777)),
            patch.object(Path, "stat", return_value=SimpleNamespace(st_uid=0, st_mode=0o775)),
            self.assertRaises(RuntimeError),
        ):
            setup.require_root_owned(Path("/opt/pocketdisco/current"))

    @unittest.skipUnless(
        sys.platform == "linux" and setup.os.geteuid() == 0,
        "requires root on Linux",
    )
    def test_actual_root_owned_symlink(self):
        with tempfile.TemporaryDirectory(dir=TEMP) as directory:
            target = Path(directory) / "release"
            target.mkdir(mode=0o755)
            link = Path(directory) / "current"
            link.symlink_to(target, target_is_directory=True)
            setup.require_root_owned(link)
            target.chmod(0o775)
            with self.assertRaises(RuntimeError):
                setup.require_root_owned(link)

    def test_migration_os_identity_is_distinct_from_runtime_and_admin(self):
        self.assertIn("pocketdisco-migrate", setup.USERS)
        self.assertEqual(len(set(setup.USERS)), 4)


if __name__ == "__main__":
    unittest.main()
