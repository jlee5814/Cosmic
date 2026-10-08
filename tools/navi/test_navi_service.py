"""Run: python3 -m unittest tools/navi/test_navi_service.py"""
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import navi_service as navi  # noqa: E402


class FakeImap:
    """Records every call so tests can assert the mailbox is only ever read."""

    def __init__(self, uids, headers):
        self.uids, self.headers, self.calls = uids, headers, []

    def login(self, user, password):
        self.calls.append(("login", user))

    def select(self, mailbox, readonly=False):
        self.calls.append(("select", mailbox, readonly))
        return "OK", [b"1"]

    def uid(self, command, *args):
        self.calls.append(("uid", command) + args)
        if command == "SEARCH":
            return "OK", [b" ".join(self.uids)]
        rows = [(f"{n} (UID {u.decode()} BODY[HEADER.FIELDS (FROM SUBJECT)] {{9}}".encode(), self.headers[u])
                for n, u in enumerate(self.uids[-navi.MAX_ITEMS:], 1)]
        return "OK", [part for row in rows for part in (row, b")")]

    def logout(self):
        self.calls.append(("logout",))


def summary_with(fake):
    with mock.patch.object(navi, "gmail_credentials", return_value=("me@example.com", "pw")), \
            mock.patch.object(navi.imaplib, "IMAP4_SSL", return_value=fake):
        return navi.inbox_summary()


class InboxSummaryTest(unittest.TestCase):
    def test_newest_first_with_decoded_names_and_subjects(self):
        fake = FakeImap([b"7", b"9", b"12", b"15"], {
            b"9": b"From: Old <old@x.com>\r\nSubject: older\r\n\r\n",
            b"12": b"From: =?utf-8?q?Jos=C3=A9?= <jose@x.com>\r\nSubject: =?utf-8?q?caf=C3=A9_=F0=9F=8E=89?=\r\n\r\n",
            b"15": b"From: alerts@bank.com\r\nSubject: \r\n\r\n",
        })
        self.assertEqual(summary_with(fake), [
            "4 unread in primary, newest first:",
            "alerts: (no subject)",
            "Jose: cafe",
            "Old: older",
        ])

    def test_mailbox_is_only_read(self):
        fake = FakeImap([b"3"], {b"3": b"From: a@b.c\r\nSubject: hi\r\n\r\n"})
        summary_with(fake)
        self.assertIn(("select", "INBOX", True), fake.calls)
        fetches = [c for c in fake.calls if c[:2] == ("uid", "FETCH")]
        self.assertTrue(all("BODY.PEEK" in c[3] and "TEXT" not in c[3] for c in fetches))
        self.assertFalse([c for c in fake.calls if c[:2] == ("uid", "STORE")])
        self.assertEqual(fake.calls[-1], ("logout",))

    def test_empty_inbox(self):
        self.assertEqual(summary_with(FakeImap([], {})), ["nothing unread in primary"])

    def test_long_subject_is_clipped(self):
        fake = FakeImap([b"1"], {b"1": b"From: A <a@b.c>\r\nSubject: " + b"x" * 200 + b"\r\n\r\n"})
        line = summary_with(fake)[1]
        self.assertEqual(len(line), navi.MAX_LINE)
        self.assertTrue(line.endswith("..."))

    def test_missing_keychain_item_is_a_whisperable_message(self):
        missing = mock.Mock(returncode=44, stdout="")
        with mock.patch.object(navi.subprocess, "run", return_value=missing):
            with self.assertRaisesRegex(navi.ToolMessage, "isn't set up"):
                navi.gmail_credentials()


class OwnerGateTest(unittest.TestCase):
    """Runs the real handler on an ephemeral port with a stub tool."""

    def setUp(self):
        import http.server
        import threading
        self.calls = []
        patches = [
            mock.patch.object(navi, "TOKEN", "t0k"),
            mock.patch.object(navi, "OWNERS", {"sipsaeki"}),
            mock.patch.object(navi, "TOOLS", {"/v1/email": lambda: self.calls.append(1) or ["inbox line"]}),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        navi.Handler.log_message = lambda *a, **k: None
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), navi.Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.shutdown)

    def get(self, owner=None, token="t0k"):
        import urllib.error
        import urllib.request
        headers = {"Authorization": f"Bearer {token}"}
        if owner is not None:
            headers["X-Navi-Owner"] = owner
        req = urllib.request.Request(f"http://127.0.0.1:{self.server.server_port}/v1/email", headers=headers)
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, r.read().decode().strip()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode().strip()

    def test_listed_owner_gets_the_tool_case_insensitively(self):
        self.assertEqual(self.get("Sipsaeki"), (200, "inbox line"))
        self.assertEqual(self.calls, [1])

    def test_other_owner_is_refused_before_the_tool_runs(self):
        self.assertEqual(self.get("superman"), (403, "navi isn't linked to your account"))
        self.assertEqual(self.get(""), (403, "navi isn't linked to your account"))
        self.assertEqual(self.get(None), (403, "navi isn't linked to your account"))
        self.assertEqual(self.calls, [])

    def test_bad_token_is_refused_even_for_the_owner(self):
        self.assertEqual(self.get("Sipsaeki", token="nope")[0], 401)
        self.assertEqual(self.calls, [])

    def test_no_owner_list_means_nobody(self):
        with mock.patch.object(navi, "ENV_FILE", Path("/nonexistent/navi.env")):
            self.assertEqual(navi.load_owners(), set())


if __name__ == "__main__":
    unittest.main()
