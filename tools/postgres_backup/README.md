# Private-cluster backup check

Run on the reviewed Ubuntu server after migrations and room-control tests:

```sh
sudo python3 /opt/pocketdisco/current/tools/postgres_backup/run.py --expected-revision 0001_private_rooms
```

The helper checks the dedicated cluster identity, writes a root-only custom
PostgreSQL dump, and restores it into a fresh, uniquely named scratch database.
It checks the migration marker and core tables, then removes only that scratch
database. It never restores over the running application database.

Archives and checksum reports remain in `/var/lib/pocketdisco/backups`. They
contain application data. Do not commit, publish, or copy them into diagnostics.
The output contains only the archive name, size, checksum, and check results.

If the check fails, the archive is retained for investigation. A failed scratch
cleanup also makes the check fail. This is a local restore drill, not off-site
backup, automated retention, a disaster-recovery plan, or public-release approval.
No previous backup is automatically removed.

```powershell
.local-tools/api-venv/Scripts/python -m unittest discover -s tools/postgres_backup -v
```
