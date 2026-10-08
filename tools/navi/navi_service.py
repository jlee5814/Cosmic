#!/usr/bin/env python3
"""Navi service: owner tools for Cosmic bots, kept outside the game server.

The game server never holds email credentials. It calls this service over
localhost (host.docker.internal from the container) with a shared token and
whispers the returned lines to the bot's owner.

Step 1 scope: one read only tool, a Gmail Primary inbox summary.
  * The mailbox is opened read only (EXAMINE) and headers are fetched with
    BODY.PEEK, so nothing is marked read, moved, or changed.
  * Only sender names and subjects leave this process; bodies are never fetched.
  * Nothing from the mailbox is logged.

See tools/navi/README.md for setup.
"""
import argparse
import email
import email.header
import email.utils
import hmac
import http.server
import imaplib
import os
import re
import secrets
import subprocess
import sys
import unicodedata
from pathlib import Path

BIND = os.environ.get("NAVI_BIND", "127.0.0.1")
PORT = int(os.environ.get("NAVI_PORT", "8790"))
STATE_DIR = Path(os.environ.get("NAVI_STATE_DIR", str(Path.home() / ".navi")))
ENV_FILE = STATE_DIR / "navi.env"
KEYCHAIN_SERVICE = "navi-gmail"
IMAP_HOST = "imap.gmail.com"
INBOX_QUERY = "category:primary is:unread"
MAX_ITEMS = 3
MAX_LINE = 90

TOKEN = ""
OWNERS: set[str] = set()


class ToolMessage(Exception):
    """An expected failure (setup missing, login rejected). Its text is safe to whisper."""


def load_or_create_token() -> str:
    STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text().splitlines():
            if line.startswith("NAVI_TOKEN="):
                return line.split("=", 1)[1].strip()
    token = secrets.token_urlsafe(32)
    fd = os.open(ENV_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(f"NAVI_TOKEN={token}\n")
    return token


def load_owners() -> set[str]:
    """Game characters allowed to use Navi, from NAVI_OWNERS in navi.env (comma separated).

    Fails closed: with no list, nobody gets an answer. The mailbox belongs to one person,
    so owning a bot must never be enough on its own.
    """
    if not ENV_FILE.exists():
        return set()
    for line in ENV_FILE.read_text().splitlines():
        if line.startswith("NAVI_OWNERS="):
            return {name.strip().lower() for name in line.split("=", 1)[1].split(",") if name.strip()}
    return set()


def gmail_credentials() -> tuple[str, str]:
    """Account and app password from the macOS login keychain (service navi-gmail)."""
    def security(*extra: str) -> subprocess.CompletedProcess:
        return subprocess.run(["security", "find-generic-password", "-s", KEYCHAIN_SERVICE, *extra],
                              capture_output=True, text=True, timeout=10)

    meta = security()
    account = re.search(r'"acct"<blob>="([^"]+)"', meta.stdout)
    secret = security("-w") if meta.returncode == 0 else None
    if not account or secret is None or secret.returncode != 0 or not secret.stdout.strip():
        raise ToolMessage("email isn't set up yet, see tools/navi/README.md")
    return account.group(1), secret.stdout.strip().replace(" ", "")


def chat_safe(text: str) -> str:
    """Fold to plain ASCII the game client can show; drops emoji instead of printing '?'."""
    folded = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"\s+", " ", folded).strip()


def decode_header(value: str) -> str:
    parts = []
    for chunk, charset in email.header.decode_header(value or ""):
        if isinstance(chunk, bytes):
            try:
                chunk = chunk.decode(charset or "utf-8", errors="replace")
            except LookupError:
                chunk = chunk.decode("utf-8", errors="replace")
        parts.append(chunk)
    return "".join(parts)


def sender_name(from_value: str) -> str:
    name, addr = email.utils.parseaddr(decode_header(from_value))
    return chat_safe(name) or chat_safe(addr.split("@")[0]) or "someone"


def clip(text: str) -> str:
    return text if len(text) <= MAX_LINE else text[: MAX_LINE - 3].rstrip() + "..."


def inbox_summary() -> list[str]:
    user, password = gmail_credentials()
    try:
        imap = imaplib.IMAP4_SSL(IMAP_HOST, timeout=8)
    except OSError:
        raise ToolMessage("can't reach gmail right now")
    try:
        try:
            imap.login(user, password)
        except imaplib.IMAP4.error:
            raise ToolMessage("gmail rejected the login, check the app password")
        imap.select("INBOX", readonly=True)
        typ, data = imap.uid("SEARCH", "X-GM-RAW", f'"{INBOX_QUERY}"')
        uids = data[0].split() if typ == "OK" and data and data[0] else []
        if not uids:
            return ["nothing unread in primary"]

        latest = b",".join(uids[-MAX_ITEMS:]).decode()
        typ, rows = imap.uid("FETCH", latest, "(UID BODY.PEEK[HEADER.FIELDS (FROM SUBJECT)])")
        found = {}
        for row in rows if typ == "OK" else []:
            if not isinstance(row, tuple):
                continue
            uid = re.search(rb"UID (\d+)", row[0])
            msg = email.message_from_bytes(row[1])
            subject = chat_safe(decode_header(msg.get("Subject", ""))) or "(no subject)"
            found[int(uid.group(1)) if uid else -len(found)] = f"{sender_name(msg.get('From', ''))}: {subject}"

        count = len(uids)
        lines = [f"{count} unread in primary" + (", newest first:" if found else "")]
        lines += [clip(found[k]) for k in sorted(found, reverse=True)]
        return lines
    finally:
        try:
            imap.logout()
        except Exception:
            pass


TOOLS = {"/v1/email": inbox_summary}


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "navi/1"

    def do_GET(self):
        expected = f"Bearer {TOKEN}".encode()
        if not TOKEN or not hmac.compare_digest(self.headers.get("Authorization", "").encode(), expected):
            return self.reply(401, ["unauthorized"])
        owner = self.headers.get("X-Navi-Owner", "").strip()
        if owner.lower() not in OWNERS:
            self.log_message("%s owner=%s -> 403", self.path, owner or "-")
            return self.reply(403, ["navi isn't linked to your account"])
        tool = TOOLS.get(self.path)
        if tool is None:
            return self.reply(404, ["unknown tool"])
        try:
            status, lines = 200, tool()
        except ToolMessage as e:
            status, lines = 200, [str(e)]
        except Exception as e:  # never echo exception text; it can carry mailbox data
            self.log_message("%s failed: %s", self.path, type(e).__name__)
            status, lines = 500, ["navi hit an error, check the service log"]
        self.log_message("%s owner=%s -> %d (%d lines)", self.path, owner, status, len(lines))
        self.reply(status, lines)

    def reply(self, status: int, lines: list[str]):
        body = ("\n".join(lines) + "\n").encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_request(self, code="-", size="-"):
        pass  # do_GET logs its own line, without mailbox contents


def main() -> int:
    parser = argparse.ArgumentParser(description="Navi owner tools service for Cosmic bots")
    parser.add_argument("--check", action="store_true", help="print the email summary and exit")
    args = parser.parse_args()

    if args.check:
        try:
            print("\n".join(inbox_summary()))
        except ToolMessage as e:
            print(e)
            return 1
        return 0

    global TOKEN, OWNERS
    TOKEN = load_or_create_token()
    OWNERS = load_owners()
    server = http.server.ThreadingHTTPServer((BIND, PORT), Handler)
    print(f"navi listening on http://{BIND}:{PORT} (token in {ENV_FILE}); owners: "
          f"{', '.join(sorted(OWNERS)) or 'none, set NAVI_OWNERS'}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
