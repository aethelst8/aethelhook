"""AethelHook - extracts the last assistant message from Devin CLI's own session
database for a given session_id, so on_stop.ps1 can attach a real summary to the
"Devin CLI finished" phone notification.

Devin CLI stores session history in SQLite (sessions.db under
%APPDATA%\\devin\\cli\\), not a JSONL transcript file like the other four agents
integrated in AethelHook - there is no documented API for this, so this reads the
database directly. message_nodes.chat_message is a JSON blob per message; the most
recent row with role == "assistant" is the agent's final reply for that turn.

Prints the extracted text to stdout on success, prints nothing on any failure
(missing session, missing/locked database, unexpected schema, etc.) - the caller
treats empty output as "no summary available" and falls back to a plain
notification, never a crash or a hang.
"""

import json
import os
import sqlite3
import sys


def main():
    if len(sys.argv) < 2:
        return
    session_id = sys.argv[1]
    db_path = os.path.expandvars(r"%APPDATA%\devin\cli\sessions.db")
    if not os.path.exists(db_path):
        return
    try:
        # Sessions.db is written by Devin CLI in WAL mode (sessions.db-wal/-shm are
        # confirmed present alongside it), so a plain read-only connection here does
        # not block on or get blocked by the still-running devin.exe process.
        conn = sqlite3.connect(db_path, timeout=2)
        cur = conn.cursor()
        cur.execute(
            "SELECT chat_message FROM message_nodes WHERE session_id = ? ORDER BY row_id DESC",
            (session_id,),
        )
        for (chat_message,) in cur.fetchall():
            try:
                msg = json.loads(chat_message)
            except (TypeError, ValueError):
                continue
            if msg.get("role") == "assistant" and msg.get("content"):
                content = msg["content"]
                if isinstance(content, str) and content.strip():
                    print(content.strip())
                    return
    except sqlite3.Error:
        return


if __name__ == "__main__":
    main()
