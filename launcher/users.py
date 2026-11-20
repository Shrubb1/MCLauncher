"""Local user accounts + license gate for the built-in client mod.

Accounts live in users.json next to the other launcher config. Passwords
are PBKDF2-HMAC-SHA256 hashes (never plaintext). 'licensed' is the
purchase flag - a real payment flow would set it via a server; locally it
is toggled by an admin. 'admin' accounts can only be created by editing
users.json directly (tools/create_admin.py), never through the app.
"""

import hashlib
import hmac
import json
import os
import re
import secrets
import time
from pathlib import Path

from .config import CONFIG_DIR

USERS_FILE = CONFIG_DIR / "users.json"
SESSION_FILE = CONFIG_DIR / "session.json"

_ITER = 200_000
_NAME_RE = re.compile(r"^[A-Za-z0-9_]{3,20}$")


def _gen_code() -> str:
    """Account license key: MCL-XXXX-XXXX-XXXX."""
    return "MCL-" + "-".join(
        secrets.token_hex(2).upper() for _ in range(3))


def subcode(master_code: str, mc_id: str) -> str:
    """Stable per-Minecraft-account code derived from the master key.

    Same inputs always give the same code, so the future client mod can
    regenerate/verify it. SUB-XXXXXXXX.
    """
    h = hmac.new(master_code.encode(), mc_id.encode(),
                 hashlib.sha256).hexdigest()
    return f"SUB-{h[:8].upper()}"


def _load() -> dict:
    if USERS_FILE.is_file():
        try:
            data = json.loads(USERS_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                dirty = False
                for rec in data.values():
                    if isinstance(rec, dict) and not rec.get("code"):
                        rec["code"] = _gen_code()
                        dirty = True
                if dirty:
                    _save(data)
                return data
        except (json.JSONDecodeError, OSError):
            pass
    return {}


def _save(users: dict) -> None:
    USERS_FILE.write_text(json.dumps(users, indent=2), encoding="utf-8")


def _hash(password: str, salt: bytes) -> str:
    return hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, _ITER).hex()


def _server():
    """Relay client when a chat server is configured, else None."""
    if not SESSION_FILE.parent.exists():
        SESSION_FILE.parent.mkdir(parents=True, exist_ok=True)
    try:
        from . import chat
        return chat if chat.enabled() else None
    except Exception:
        return None


def online_mode() -> bool:
    return _server() is not None


def list_users() -> dict:
    return _load()


def create_user(name: str, password: str,
                licensed: bool = False, admin: bool = False) -> str:
    """Create an account. Returns an error string, or "" on success."""
    srv = _server()
    if srv is not None:
        try:
            r = srv.signup(name.strip(), password)
        except Exception:
            return "Chat server unreachable"
        return r.get("error") or ""
    key = name.strip().lower()
    if not _NAME_RE.match(name.strip()):
        return "Username must be 3-20 letters, digits or _"
    if len(password) < 6:
        return "Password must be at least 6 characters"
    users = _load()
    if key in users:
        return "That username is taken"
    salt = secrets.token_bytes(16)
    users[key] = {
        "name": name.strip(),
        "salt": salt.hex(),
        "hash": _hash(password, salt),
        "licensed": bool(licensed),
        "admin": bool(admin),
        "code": _gen_code(),
        "created": int(time.time()),
    }
    _save(users)
    return ""


def authenticate(name: str, password: str) -> dict | None:
    """Return a session-ready user record on success, else None."""
    srv = _server()
    if srv is not None:
        try:
            r = srv.login(name.strip(), password)
        except Exception:
            return {"error": "Chat server unreachable"}
        if r.get("error"):
            return None
        return {"key": r["name"].lower(), "name": r["name"],
                "licensed": bool(r.get("licensed")),
                "admin": bool(r.get("admin")),
                "code": r.get("code", ""),
                "bound_mc": r.get("bound_mc", ""),
                "bound_at": int(r.get("bound_at") or 0),
                "token": r.get("token"), "online": True}
    rec = _load().get(name.strip().lower())
    if not rec:
        return None
    good = rec.get("hash") == _hash(password, bytes.fromhex(rec["salt"]))
    if not good:
        return None
    return {"key": name.strip().lower(), "name": rec.get("name", name),
            "licensed": bool(rec.get("licensed")),
            "admin": bool(rec.get("admin")),
            "code": rec.get("code", ""),
            "bound_mc": rec.get("bound_mc", ""),
            "bound_at": int(rec.get("bound_at") or 0),
            "token": None, "online": False}


def oauth_ms_signin() -> dict:
    """Microsoft OAuth sign-in. Returns a session dict, a
    {'needs_name', 'pending', 'suggested'} registration request,
    or {'error': ...}."""
    srv = _server()
    if srv is None:
        return {"error": "Microsoft sign-in needs a chat server"}
    from . import auth
    try:
        tokens = auth.oauth_ms_signin()
    except auth.AuthError as e:
        return {"error": str(e)}
    try:
        r = srv.oauth_ms(tokens["access_token"])
    except Exception:
        return {"error": "Chat server unreachable"}
    if r.get("needs_name"):
        return r
    if r.get("error"):
        return r
    return {"key": r["name"].lower(), "name": r["name"],
            "licensed": bool(r.get("licensed")),
            "admin": bool(r.get("admin")),
            "code": r.get("code", ""),
            "bound_mc": r.get("bound_mc", ""),
            "bound_at": int(r.get("bound_at") or 0),
            "token": r.get("token"), "online": True}


def oauth_register(pending: str, name: str) -> dict:
    """Finish first-time OAuth sign-in by claiming a username."""
    srv = _server()
    try:
        r = srv.oauth_register(pending, name)
    except Exception:
        return {"error": "Chat server unreachable"}
    if r.get("error"):
        return r
    return {"key": r["name"].lower(), "name": r["name"],
            "licensed": bool(r.get("licensed")),
            "admin": bool(r.get("admin")),
            "code": r.get("code", ""),
            "bound_mc": r.get("bound_mc", ""),
            "bound_at": int(r.get("bound_at") or 0),
            "token": r.get("token"), "online": True}


def change_password(name: str, password: str) -> str:
    if len(password) < 6:
        return "Password must be at least 6 characters"
    u = current_user()
    if u and u.get("online") and u.get("key") == name.strip().lower():
        try:
            r = _server().account_password(u["token"], password)
        except Exception:
            return "Chat server unreachable"
        return r.get("error") or ""
    users = _load()
    rec = users.get(name.strip().lower())
    if not rec:
        return "Account not found"
    salt = secrets.token_bytes(16)
    rec["salt"], rec["hash"] = salt.hex(), _hash(password, salt)
    _save(users)
    return ""


def set_licensed(name: str, licensed: bool) -> None:
    users = _load()
    rec = users.get(name.strip().lower())
    if rec is not None:
        rec["licensed"] = bool(licensed)
        _save(users)


# --------------------------------------------------- mod username binding

BIND_COOLDOWN = 86400  # seconds between re-binds
_MC_NAME_RE = re.compile(r"^[A-Za-z0-9_]{3,16}$")


def binding_info() -> dict:
    """{bound_mc, bound_at, locked_for} for the current user."""
    u = current_user()
    if not u:
        return {}
    bound_mc, bound_at = u.get("bound_mc") or "", int(u.get("bound_at") or 0)
    locked = max(0, bound_at + BIND_COOLDOWN - int(time.time())) if bound_at else 0
    return {"bound_mc": bound_mc, "bound_at": bound_at, "locked_for": locked}


def bind_mc(mc_name: str) -> dict:
    """Bind the license to a Minecraft username. {ok} or {error, locked_for}."""
    u = current_user()
    if not u:
        return {"error": "not signed in"}
    if not u.get("licensed"):
        return {"error": "license required"}
    mc_name = mc_name.strip()
    if not _MC_NAME_RE.match(mc_name):
        return {"error": "not a valid Minecraft username"}
    info = binding_info()
    if info.get("locked_for"):
        return {"error": "locked", "locked_for": info["locked_for"],
                "bound_mc": info["bound_mc"]}
    if u.get("online"):
        srv = _server()
        try:
            r = srv.account_bind(u["token"], mc_name)
        except Exception:
            return {"error": "Chat server unreachable"}
        if r.get("error"):
            return {"error": r["error"], "locked_for": r.get("locked_for", 0),
                    "bound_mc": r.get("bound_mc", "")}
        u["bound_mc"], u["bound_at"] = mc_name, int(time.time())
        login(u)
        return {"ok": True}
    data = _load()
    rec = data.get(u["key"])
    if not rec:
        return {"error": "account not found"}
    rec["bound_mc"], rec["bound_at"] = mc_name, int(time.time())
    _save(data)
    u["bound_mc"], u["bound_at"] = mc_name, int(time.time())
    login(u)
    return {"ok": True}


# -------------------------------------------------------- purchase keys

KEYS_FILE = CONFIG_DIR / "purchase_keys.json"
_BUY_KEY_RE = re.compile(r"^BUY-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$")


def _gen_buy_key() -> str:
    return "BUY-" + "-".join(
        secrets.token_hex(2).upper() for _ in range(3))


def _keys_load() -> dict:
    if KEYS_FILE.is_file():
        try:
            data = json.loads(KEYS_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return data
        except (json.JSONDecodeError, OSError):
            pass
    return {}


def _keys_save(keys: dict) -> None:
    KEYS_FILE.write_text(json.dumps(keys, indent=2), encoding="utf-8")


_CHECKOUT_CACHE = {"url": None, "ts": 0}


def fetch_checkout_url() -> str:
    """Hits the server for the store link; results cached 5 min so the
    UI only pays the network cost on the first call."""
    now = time.time()
    if _CHECKOUT_CACHE["url"] is not None and now - _CHECKOUT_CACHE["ts"] < 300:
        return _CHECKOUT_CACHE["url"]
    url = ""
    srv = _server()
    if srv is not None:
        try:
            url = str(srv.server_config().get("checkout_url") or "")
        except Exception:
            pass
    if not url:
        from . import config
        url = str(config.load_settings().get("checkout_url") or "")
    _CHECKOUT_CACHE.update({"url": url, "ts": now})
    return url


def checkout_url() -> str:
    """Where the Buy button points: cached server config, else local setting."""
    if _CHECKOUT_CACHE["url"] is not None:
        return _CHECKOUT_CACHE["url"]
    return fetch_checkout_url()


def cached_checkout_url() -> str:
    """Non-blocking read - '' until the first fetch_checkout_url lands."""
    return _CHECKOUT_CACHE["url"] or ""


def set_checkout_url(url: str, password: str = "") -> None:
    """Save the store link locally and, for online admins, on the relay."""
    from . import config
    settings = config.load_settings()
    settings["checkout_url"] = url.strip()
    config.save_settings(settings)
    _CHECKOUT_CACHE.update({"url": url.strip(), "ts": time.time()})
    u = current_user()
    if u and u.get("admin") and u.get("online"):
        try:
            _server().admin_config(u["token"], url.strip(), password)
        except Exception:
            pass


def redeem_key(key: str) -> dict:
    """Redeem a BUY-XXXX-... purchase key -> {ok} or {error}."""
    u = current_user()
    if not u:
        return {"error": "not signed in"}
    key = key.strip().upper()
    if not _BUY_KEY_RE.match(key):
        return {"error": "not a valid purchase key"}
    if u.get("licensed"):
        return {"error": "account is already licensed"}
    if u.get("online"):
        try:
            r = _server().redeem(u["token"], key)
        except Exception:
            return {"error": "Chat server unreachable"}
        if r.get("error"):
            return {"error": r["error"]}
        u["licensed"] = True
        login(u)
        return {"ok": True}
    kh = hashlib.sha256(key.encode()).hexdigest()
    keys = _keys_load()
    rec = keys.get(kh)
    if not rec:
        return {"error": "invalid purchase key"}
    if rec.get("used_by"):
        return {"error": "key already used"}
    rec["used_by"], rec["used_at"] = u["key"], int(time.time())
    _keys_save(keys)
    set_licensed(u["key"], True)
    u["licensed"] = True
    login(u)
    return {"ok": True}


def gen_keys(count: int, password: str = "") -> dict:
    """Admin: mint purchase keys -> {keys: [...]} or {error}."""
    u = current_user()
    if not u or not u.get("admin"):
        return {"error": "not admin"}
    count = max(1, min(50, int(count or 1)))
    if u.get("online"):
        try:
            r = _server().admin_genkeys(u["token"], count, password)
        except Exception:
            return {"error": "Chat server unreachable"}
        if r.get("error"):
            return {"error": r["error"]}
        return {"keys": r.get("keys") or []}
    keys = _keys_load()
    out = []
    for _ in range(count):
        key = _gen_buy_key()
        keys[hashlib.sha256(key.encode()).hexdigest()] = {
            "used_by": None, "used_at": 0}
        out.append(key)
    _keys_save(keys)
    return {"keys": out}


# ------------------------------------------------------------ session

_ACTIVE = None  # this process's session; file is only the cross-run fallback


def login(session: dict) -> None:
    """Persist a session dict (key/name/licensed/admin/token/online)."""
    global _ACTIVE
    session = dict(session)
    session.pop("error", None)
    _ACTIVE = session
    SESSION_FILE.write_text(json.dumps(session))


def logout() -> None:
    global _ACTIVE
    _ACTIVE = None
    try:
        SESSION_FILE.unlink()
    except OSError:
        pass


def current_user() -> dict | None:
    """Logged-in user {key, name, licensed, admin, token, online} or None.

    Prefers this process's login so two launchers can hold different
    sessions for testing; falls back to the on-disk session.
    """
    sess = _ACTIVE
    if sess is None:
        if not SESSION_FILE.is_file():
            return None
        try:
            sess = json.loads(SESSION_FILE.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return None
    if not isinstance(sess, dict) or not sess.get("key"):
        return None
    if sess.get("online"):
        return sess
    # local mode: refresh flags from users.json so admin changes show up
    rec = _load().get(sess["key"])
    if not rec:
        return None
    sess.update({"name": rec.get("name", sess["key"]),
                 "licensed": bool(rec.get("licensed")),
                 "admin": bool(rec.get("admin")),
                 "code": rec.get("code", ""),
                 "bound_mc": rec.get("bound_mc", ""),
                 "bound_at": int(rec.get("bound_at") or 0)})
    return sess


def is_licensed() -> bool:
    u = current_user()
    return bool(u and u.get("licensed"))


def refresh() -> None:
    """Online accounts: pull /me so server-side flag changes (license
    grants, admin flips, binds) reach this session without re-login."""
    u = current_user()
    if not u or not u.get("token"):
        return
    srv = _server()
    if srv is None:
        return
    try:
        r = srv.me(u["token"])
    except Exception:
        return
    if r.get("error"):
        return
    u.update({"name": r["name"], "licensed": bool(r.get("licensed")),
              "admin": bool(r.get("admin")), "code": r.get("code", ""),
              "bound_mc": r.get("bound_mc", ""),
              "bound_at": int(r.get("bound_at") or 0)})
    login(u)
