import importlib.util
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "postgres_backup", Path(__file__).with_name("run.py")
)
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)


class BackupTest(unittest.TestCase):
    def test_restore_cleanup_rejects_non_scratch_databases(self):
        for name in (
            "pocketdisco",
            "postgres",
            "pocketdisco_test",
            "other_restore_test",
        ):
            with self.subTest(name=name), self.assertRaises(ValueError):
                backup.remove_restore_database(name)

    def test_restore_cleanup_targets_only_generated_database(self):
        name = "pocketdisco_restore_" + "a" * 32 + "_test"
        with patch.object(backup, "run_pg") as command:
            backup.remove_restore_database(name)
        command.assert_called_once_with("dropdb", name)

    def test_postgres_commands_use_only_dedicated_peer_connection(self):
        result = subprocess.CompletedProcess([], 0, b"ok", b"")
        with patch.object(backup.subprocess, "run", return_value=result) as command:
            self.assertEqual(backup.run_pg("psql", "-d", "pocketdisco"), b"ok")
        args = command.call_args.args[0]
        self.assertEqual(args[:4], ["runuser", "-u", "pocketdisco-db", "--"])
        self.assertIn("/run/pocketdisco-postgres", args)
        self.assertIn("55432", args)
        self.assertIn("--no-password", args)
        self.assertNotIn("PGPASSWORD", command.call_args.kwargs["env"])

    def test_failed_command_never_prints_subprocess_output(self):
        result = subprocess.CompletedProcess(
            [], 1, b"private data", b"credential-canary"
        )
        with (
            patch.object(backup.subprocess, "run", return_value=result),
            self.assertRaises(RuntimeError) as error,
        ):
            backup.run_pg("pg_dump", "-d", "pocketdisco")
        self.assertNotIn("private", str(error.exception))
        self.assertNotIn("canary", str(error.exception))


if __name__ == "__main__":
    unittest.main()
