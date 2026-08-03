"""AethelHook - finds the most recently active Devin CLI session for a given working
directory, so RunHeadlessDevinCliPromptAsync (Program.cs) can capture a resumable
session_id after a headless `-p` run. Devin CLI's -p mode prints only plain text to
stdout - no machine-readable session id anywhere in it, unlike the other four headless-
capable agents integrated in AethelHook - so this reads the sessions table directly
from the same sessions.db used by extract_summary.py.

The db_path argument is REQUIRED when this is invoked by the API service directly
(RunHeadlessDevinCliPromptAsync always passes it) - confirmed live this script is
spawned directly by the LocalSystem-run service, not as a child of devin.exe the way
on_stop.ps1/extract_summary.py are, so it never inherits the real user's overridden
APPDATA env var the way that chain does. Relying on %APPDATA% expansion here silently
resolved to LocalSystem's own empty profile and always returned nothing. Falls back to
%APPDATA% expansion only when db_path is omitted, for convenience when run manually
from a real user's own terminal.

Prints the session id to stdout on success, prints nothing on any failure (missing
database, no matching session, etc.) - the caller treats empty output as "couldn't
resolve a session id" and simply starts fresh next time rather than crashing or hanging.
"""

import os
import sqlite3
import sys


def main():
    if len(sys.argv) < 2:
        return
    working_dir = sys.argv[1]
    db_path = sys.argv[2] if len(sys.argv) > 2 else os.path.expandvars(r"%APPDATA%\devin\cli\sessions.db")
    if not os.path.exists(db_path):
        return
    try:
        conn = sqlite3.connect(db_path, timeout=2)
        cur = conn.cursor()
        cur.execute(
            "SELECT id FROM sessions WHERE working_directory = ? ORDER BY last_activity_at DESC LIMIT 1",
            (working_dir,),
        )
        row = cur.fetchone()
        if row:
            print(row[0])
    except sqlite3.Error:
        return


if __name__ == "__main__":
    main()
