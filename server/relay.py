"""MCLauncher relay server - accounts, friends and chat over HTTP.

Zero dependencies (stdlib only). Run:

    python server/relay.py [port]        # default 8765

Deploy anywhere that can run Python (VPS, free-tier host, or your own PC
for testing). Clients point Settings -> 'Chat server' at
http://<host>:<port>. Endpoints are JSON POST/GET; auth is a bearer-ish
token in the JSON body.

Security note: use HTTPS in production (put this behind a reverse proxy
with TLS) - passwords are sent once at signup/login and hashed server-side
with PBKDF2; chat text is plaintext on the wire otherwise.
"""

import hashlib
import json
import os
import re
import secrets
import sqlite3
import sys
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DB_PATH = Path(os.environ.get(
    "RELAY_DB", Path(__file__).resolve().parent / "relay.db"))
_ITER = 200_000


def _db():
    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    return db


def _gen_code():
    return "MCL-" + "-".join(
        secrets.token_hex(2).upper() for _ in range(3))


def _init(db):
    db.executescript("""
        CREATE TABLE IF NOT EXISTS users(
            name TEXT PRIMARY KEY COLLATE NOCASE,
            salt TEXT, hash TEXT, licensed INT DEFAULT 0,
            admin INT DEFAULT 0, created INT);
        CREATE TABLE IF NOT EXISTS sessions(
            token TEXT PRIMARY KEY, name TEXT, created INT);
        CREATE TABLE IF NOT EXISTS friends(
            user TEXT, friend TEXT, status TEXT,
            PRIMARY KEY(user, friend));
        CREATE TABLE IF NOT EXISTS messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sender TEXT, recipient TEXT, text TEXT, ts INT);
        CREATE INDEX IF NOT EXISTS idx_msg_pair
            ON messages(sender, recipient, id);
        CREATE TABLE IF NOT EXISTS oauth_links(
            provider TEXT, sub TEXT, name TEXT,
            PRIMARY KEY(provider, sub));
        CREATE TABLE IF NOT EXISTS pending(
            token TEXT PRIMARY KEY, provider TEXT, sub TEXT,
            suggested TEXT, created INT);
        CREATE TABLE IF NOT EXISTS redeem_keys(
            keyhash TEXT PRIMARY KEY, used_by TEXT, used_at INT);
        CREATE TABLE IF NOT EXISTS cfg(
            key TEXT PRIMARY KEY, value TEXT);
    """)
    # migrate: older DBs lack these columns on users
    for col, typ in (("code", "TEXT"), ("bound_mc", "TEXT DEFAULT ''"),
                     ("bound_at", "INT DEFAULT 0")):
        try:
            db.execute(f"SELECT {col} FROM users LIMIT 1")
        except sqlite3.OperationalError:
            db.execute(f"ALTER TABLE users ADD COLUMN {col} {typ}")
    for row in db.execute("SELECT name FROM users WHERE code IS NULL OR code=''"
                          ).fetchall():
        db.execute("UPDATE users SET code=? WHERE name=?",
                   (_gen_code(), row["name"]))
    db.commit()


def _hash(pw, salt):
    return hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, _ITER).hex()


def _user(db, name):
    r = db.execute("SELECT * FROM users WHERE name=?", (name,)).fetchone()
    return dict(r) if r else None


def _auth(db, token):
    if not token:
        return None
    r = db.execute("SELECT name FROM sessions WHERE token=?",
                   (token,)).fetchone()
    return r["name"] if r else None


def _public_user(rec):
    return {"name": rec["name"], "licensed": bool(rec["licensed"]),
            "admin": bool(rec["admin"]), "code": rec.get("code") or "",
            "bound_mc": rec.get("bound_mc") or "",
            "bound_at": int(rec.get("bound_at") or 0)}


def _pair(db, a, b):
    return db.execute(
        "SELECT * FROM friends WHERE user=? AND friend=?", (a, b)).fetchone()


def _friend_state(db, me, other):
    """'friends' | 'outgoing' | 'incoming' | None for me -> other."""
    fwd = _pair(db, me, other)
    if fwd and fwd["status"] == "accepted":
        return "friends"
    if fwd:
        return "outgoing"
    back = _pair(db, other, me)
    if back:
        return "friends" if back["status"] == "accepted" else "incoming"
    return None


def handle(db, method, path, body):
    if method == "GET" and path == "/ping":
        return {"ok": True}

    if method == "POST" and path == "/signup":
        name = (body.get("name") or "").strip()
        pw = body.get("password") or ""
        if not re.match(r"^[A-Za-z0-9_]{3,20}$", name):
            return {"error": "username must be 3-20 letters/digits/_"}
        if len(pw) < 6:
            return {"error": "password must be at least 6 characters"}
        if _user(db, name):
            return {"error": "username taken"}
        salt = secrets.token_bytes(16)
        db.execute(
            "INSERT INTO users(name,salt,hash,licensed,admin,created,code)"
            " VALUES(?,?,?,0,0,?,?)",
            (name, salt.hex(), _hash(pw, salt), int(time.time()),
             _gen_code()))
        db.commit()
        return _login(db, name, pw)

    if method == "POST" and path == "/login":
        return _login(db, body.get("name") or "", body.get("password") or "")

    if method == "GET" and path == "/config":
        row = db.execute(
            "SELECT value FROM cfg WHERE key='checkout_url'").fetchone()
        return {"checkout_url": row["value"] if row else ""}

    if method == "GET" and path == "/version":
        vf = Path(os.environ.get(
            "UPDATE_VERSION",
            Path(__file__).resolve().parent / "version.txt"))
        return {"version": vf.read_text().strip() if vf.is_file() else ""}

    if method == "POST" and path == "/oauth/ms":
        access = body.get("access_token") or ""
        try:
            req = urllib.request.Request(
                "https://graph.microsoft.com/oidc/userinfo",
                headers={"Authorization": f"Bearer {access}"})
            info = json.loads(urllib.request.urlopen(req, timeout=10).read())
        except Exception:
            return {"error": "Microsoft sign-in could not be verified"}
        sub = info.get("sub")
        if not sub:
            return {"error": "Microsoft sign-in failed"}
        row = db.execute(
            "SELECT name FROM oauth_links WHERE provider='ms' AND sub=?",
            (sub,)).fetchone()
        if row:  # known identity -> straight to a session
            rec = _user(db, row["name"])
            token = secrets.token_hex(24)
            db.execute("INSERT INTO sessions VALUES(?,?,?)",
                       (token, rec["name"], int(time.time())))
            db.commit()
            out = _public_user(rec)
            out["token"] = token
            return out
        # new identity -> hold a pending slot until they pick a username
        pending = secrets.token_hex(16)
        raw = info.get("name") or info.get("preferred_username") or "player"
        suggested = re.sub(r"[^A-Za-z0-9_]", "", raw)[:16] or "player"
        db.execute("INSERT INTO pending VALUES(?,?,?,?,?)",
                   (pending, "ms", sub, suggested, int(time.time())))
        db.commit()
        return {"needs_name": True, "pending": pending,
                "suggested": suggested}

    if method == "POST" and path == "/oauth/register":
        prow = db.execute("SELECT * FROM pending WHERE token=?",
                          (body.get("pending") or "",)).fetchone()
        if not prow or int(time.time()) - prow["created"] > 600:
            return {"error": "sign-in expired - try again"}
        name = (body.get("name") or "").strip()
        if not re.match(r"^[A-Za-z0-9_]{3,20}$", name):
            return {"error": "username must be 3-20 letters/digits/_"}
        if _user(db, name):
            return {"error": "username taken"}
        salt = secrets.token_bytes(16)
        db.execute("INSERT INTO users(name,salt,hash,licensed,admin,"
                   "created,code) VALUES(?,?,?,0,0,?,?)",
                   (name, salt.hex(), "OAUTH", int(time.time()),
                    _gen_code()))
        db.execute("INSERT INTO oauth_links VALUES(?,?,?)",
                   (prow["provider"], prow["sub"], name))
        db.execute("DELETE FROM pending WHERE token=?", (prow["token"],))
        token = secrets.token_hex(24)
        db.execute("INSERT INTO sessions VALUES(?,?,?)",
                   (token, name, int(time.time())))
        db.commit()
        out = _public_user(_user(db, name))
        out["token"] = token
        return out

    # ---- everything below needs a valid token
    me = _auth(db, body.get("token"))
    if not me:
        return {"error": "not signed in"}, 401

    if path == "/me":
        return _public_user(_user(db, me))

    if path == "/account/bind":
        rec = _user(db, me)
        if not rec or not rec["licensed"]:
            return {"error": "license required"}
        mc_name = (body.get("mc_name") or "").strip()
        if not re.match(r"^[A-Za-z0-9_]{3,16}$", mc_name):
            return {"error": "not a valid Minecraft username"}
        bound_at = int(rec.get("bound_at") or 0)
        left = bound_at + 86400 - int(time.time()) if bound_at else 0
        if left > 0:
            return {"error": "locked", "locked_for": left,
                    "bound_mc": rec.get("bound_mc") or ""}
        db.execute("UPDATE users SET bound_mc=?, bound_at=? WHERE name=?",
                   (mc_name, int(time.time()), me))
        db.commit()
        return {"ok": True, "bound_mc": mc_name}

    if path == "/account/password":
        pw = body.get("password") or ""
        if len(pw) < 6:
            return {"error": "password must be at least 6 characters"}
        rec = _user(db, me)
        if rec and rec["hash"] == "OAUTH":
            return {"error": "OAuth accounts have no password"}
        salt = secrets.token_bytes(16)
        db.execute("UPDATE users SET salt=?, hash=? WHERE name=?",
                   (salt.hex(), _hash(pw, salt), me))
        # kick every other session so a stolen token dies with the old pw
        db.execute("DELETE FROM sessions WHERE name=? AND token!=?",
                   (me, body.get("token")))
        db.commit()
        return {"ok": True}

    if path == "/redeem":
        key = (body.get("key") or "").strip().upper()
        if not re.match(r"^BUY-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$", key):
            return {"error": "not a valid purchase key"}
        kh = hashlib.sha256(key.encode()).hexdigest()
        row = db.execute(
            "SELECT * FROM redeem_keys WHERE keyhash=?", (kh,)).fetchone()
        if not row:
            return {"error": "invalid purchase key"}
        if row["used_by"]:
            return {"error": "key already used"}
        db.execute("UPDATE redeem_keys SET used_by=?, used_at=? "
                   "WHERE keyhash=?", (me, int(time.time()), kh))
        db.execute("UPDATE users SET licensed=1 WHERE name=?", (me,))
        db.commit()
        return {"ok": True}

    if path == "/friends":
        rows = db.execute(
            "SELECT friend, status FROM friends WHERE user=?", (me,)).fetchall()
        out, inc, acc = [], [], []
        for r in rows:
            st = _friend_state(db, me, r["friend"])
            if st == "friends":
                acc.append(r["friend"])
            elif st == "outgoing":
                out.append(r["friend"])
        # requests aimed at me that I haven't answered
        rows = db.execute(
            "SELECT user FROM friends WHERE friend=? AND status='pending'",
            (me,)).fetchall()
        for r in rows:
            if _friend_state(db, me, r["user"]) == "incoming":
                inc.append(r["user"])
        return {"friends": sorted(acc, key=str.lower),
                "outgoing": sorted(out, key=str.lower),
                "incoming": sorted(inc, key=str.lower)}

    if path == "/friends/add":
        other = (body.get("friend") or "").strip()
        if not _user(db, other):
            return {"error": "no such user"}
        if other.lower() == me.lower():
            return {"error": "that's you"}
        st = _friend_state(db, me, other)
        if st == "friends":
            return {"error": "already friends"}
        if st == "outgoing":
            return {"error": "request already sent"}
        accept = st == "incoming"  # mutual request = instant friends
        db.execute(
            "INSERT OR REPLACE INTO friends VALUES(?,?,?)",
            (me, other, "accepted" if accept else "pending"))
        if accept:
            db.execute(
                "UPDATE friends SET status='accepted' WHERE user=? AND friend=?",
                (other, me))
        db.commit()
        return {"ok": True, "friends": accept}

    if path == "/friends/remove":
        other = (body.get("friend") or "").strip()
        db.execute("DELETE FROM friends WHERE (user=? AND friend=?) "
                   "OR (user=? AND friend=?)", (me, other, other, me))
        db.commit()
        return {"ok": True}

    if path == "/messages/send":
        to = (body.get("to") or "").strip()
        text = (body.get("text") or "").strip()
        if not text or len(text) > 2000:
            return {"error": "empty or too long"}
        if _friend_state(db, me, to) != "friends":
            return {"error": "not friends"}
        db.execute("INSERT INTO messages(sender, recipient, text, ts) "
                   "VALUES(?,?,?,?)", (me, to, text, int(time.time())))
        db.commit()
        return {"ok": True}

    if path == "/messages":
        other = (body.get("with") or "").strip()
        since = int(body.get("since") or 0)
        if _friend_state(db, me, other) != "friends":
            return {"error": "not friends"}
        rows = db.execute(
            "SELECT id, sender, text, ts FROM messages WHERE id>? AND "
            "((sender=? AND recipient=?) OR (sender=? AND recipient=?)) "
            "ORDER BY id LIMIT 200",
            (since, me, other, other, me)).fetchall()
        return {"messages": [dict(r) for r in rows]}

    if path == "/admin/license":
        rec = _user(db, me)
        if not rec or not rec["admin"]:
            return {"error": "not admin"}, 403
        target = (body.get("user") or "").strip()
        flag = 1 if body.get("licensed") else 0
        cur = db.execute("UPDATE users SET licensed=? WHERE name=?",
                         (flag, target))
        db.commit()
        if not cur.rowcount:
            return {"error": "no such user"}
        return {"ok": True}

    if path == "/admin/users":
        rec = _user(db, me)
        if not rec or not rec["admin"]:
            return {"error": "not admin"}, 403
        rows = db.execute(
            "SELECT name, licensed, admin FROM users ORDER BY name").fetchall()
        return {"users": [{"name": r["name"],
                           "licensed": bool(r["licensed"]),
                           "admin": bool(r["admin"])} for r in rows]}

    if path == "/admin/genkeys":
        rec = _user(db, me)
        if not rec or not rec["admin"]:
            return {"error": "not admin"}, 403
        count = max(1, min(50, int(body.get("count") or 1)))
        keys = []
        for _ in range(count):
            key = "BUY-" + "-".join(
                secrets.token_hex(2).upper() for _ in range(3))
            db.execute("INSERT INTO redeem_keys VALUES(?,?,?)",
                       (hashlib.sha256(key.encode()).hexdigest(), None, 0))
            keys.append(key)
        db.commit()
        return {"keys": keys}

    if path == "/admin/config":
        rec = _user(db, me)
        if not rec or not rec["admin"]:
            return {"error": "not admin"}, 403
        url = str(body.get("checkout_url") or "").strip()
        db.execute("INSERT INTO cfg VALUES('checkout_url',?) "
                   "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                   (url,))
        db.commit()
        return {"ok": True}

    return {"error": "unknown endpoint"}, 404


def _login(db, name, pw):
    rec = _user(db, name)
    if not rec or rec["hash"] != _hash(pw, bytes.fromhex(rec["salt"])):
        return {"error": "wrong username or password"}
    token = secrets.token_hex(24)
    db.execute("INSERT INTO sessions VALUES(?,?,?)",
               (token, rec["name"], int(time.time())))
    db.commit()
    out = _public_user(rec)
    out["token"] = token
    return out


class Handler(BaseHTTPRequestHandler):
    def _json(self, payload, code=200):
        raw = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _body(self):
        ln = int(self.headers.get("Content-Length") or 0)
        if not ln:
            return {}
        try:
            return json.loads(self.rfile.read(ln) or b"{}")
        except json.JSONDecodeError:
            return {}

    def _handle(self, method):
        db = _db()
        try:
            res = handle(db, method, self.path.split("?")[0],
                         self._body() if method == "POST" else _qs(self.path))
            if isinstance(res, tuple):
                self._json(res[0], res[1])
            else:
                self._json(res)
        except Exception as e:
            self._json({"error": str(e)}, 500)
        finally:
            db.close()

    def do_GET(self):
        if self.path.split("?")[0] == "/update.zip":
            return self._serve_update_zip()
        self._handle("GET")

    def _serve_update_zip(self):
        zp = Path(os.environ.get(
            "UPDATE_ZIP",
            Path(__file__).resolve().parent / "update.zip"))
        if not zp.is_file():
            self.send_response(404)
            self.end_headers()
            return
        data = zp.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        self._handle("POST")

    def log_message(self, *a):
        pass


def _qs(path):
    from urllib.parse import parse_qs, urlparse
    return {k: v[0] for k, v in parse_qs(urlparse(path).query).items()}


def main():
    if len(sys.argv) > 1:
        port = int(sys.argv[1])
    else:
        port = int(os.environ.get("PORT", 8765))
    db = _db()
    _init(db)
    db.close()
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"relay listening on :{port}  (db: {DB_PATH})")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
