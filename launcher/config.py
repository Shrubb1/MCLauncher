import json
import os
from pathlib import Path

APP_NAME = "MCLauncher"
APP_VERSION = "1.2.84"
UPDATE_REPO = "Shrubb1/MCLauncher"
UPDATE_BRANCH = "master"

# where the launcher itself is installed - resolved from this file, so
# it is always correct wherever the folder lives or moves to
INSTALL_DIR = Path(__file__).resolve().parent.parent


def remote_version() -> str:
    """Latest published APP_VERSION, or '' if unreachable. The relay's
    /version is authoritative the moment a release deploys; GitHub is
    the fallback (its API edge can lag minutes behind a push)."""
    import json
    import re
    import urllib.request
    try:  # relay first - zero propagation lag
        base = str(load_settings().get("chat_server") or "").rstrip("/")
        if base:
            req = urllib.request.Request(
                base + "/version", headers={"User-Agent": "MCLauncher"})
            v = json.loads(
                urllib.request.urlopen(req, timeout=6).read()).get("version")
            if v:
                return v
    except Exception:
        pass
    try:
        req = urllib.request.Request(
            f"https://api.github.com/repos/{UPDATE_REPO}"
            f"/contents/launcher/config.py?ref={UPDATE_BRANCH}",
            headers={"Accept": "application/vnd.github.raw",
                     "User-Agent": "MCLauncher"})
        raw = urllib.request.urlopen(req, timeout=8).read().decode()
        m = re.search(r'APP_VERSION\s*=\s*"([^"]+)"', raw)
        return m.group(1) if m else ""
    except Exception:
        return ""


def version_newer(remote: str) -> bool:
    """True if remote (e.g. '1.2.8') is newer than APP_VERSION."""
    def t(v):
        try:
            return tuple(int(x) for x in str(v).split("."))
        except Exception:
            return (0,)
    return bool(remote) and t(remote) > t(APP_VERSION)


def default_game_dir() -> str:
    appdata = os.environ.get("APPDATA")
    if appdata:
        return str(Path(appdata) / ".minecraft")
    return str(Path.home() / ".minecraft")


CONFIG_DIR = Path(os.environ.get("APPDATA", Path.home() / ".config")) / APP_NAME
CONFIG_DIR.mkdir(parents=True, exist_ok=True)

SETTINGS_FILE = CONFIG_DIR / "settings.json"
ACCOUNTS_FILE = CONFIG_DIR / "accounts.json"
INSTANCES_FILE = CONFIG_DIR / "instances.json"
SAVED_MODS_FILE = CONFIG_DIR / "saved_mods.json"
SERVER_STATS_FILE = CONFIG_DIR / "server_stats.json"
INSTANCE_HISTORY_FILE = CONFIG_DIR / "instance_history.json"
SHARED_DIR = CONFIG_DIR / "shared"

DEFAULT_SETTINGS = {
    "game_dir": default_game_dir(),
    "java_path": "",
    "default_ram_mb": "4096",
    "custom_resolution": False,
    "resolution_width": "854",
    "resolution_height": "480",
    "jvm_arguments": "",
    "jvm_preset": "Default",
    "auto_join_server": "",
    "auto_join_port": "25565",
    "alert_patterns": "",
    "alert_sound": True,
    "theme": "onyx",
    "chat_server": "https://mclauncher-relay.fly.dev",
    "checkout_url": "",
    "window_geometry": "",
    "window_state": "normal",
    "read_msgs": {},
}


def load_settings() -> dict:
    settings = dict(DEFAULT_SETTINGS)
    if SETTINGS_FILE.is_file():
        try:
            data = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                settings.update({k: v for k, v in data.items() if k in settings})
        except (json.JSONDecodeError, OSError):
            pass
    return settings


def save_settings(settings: dict) -> None:
    SETTINGS_FILE.write_text(
        json.dumps({k: settings.get(k, v) for k, v in DEFAULT_SETTINGS.items()}, indent=2),
        encoding="utf-8",
    )


def load_accounts() -> list:
    if ACCOUNTS_FILE.is_file():
        try:
            data = json.loads(ACCOUNTS_FILE.read_text(encoding="utf-8"))
            if isinstance(data, list):
                return data
        except (json.JSONDecodeError, OSError):
            pass
    return []


def save_accounts(accounts: list) -> None:
    ACCOUNTS_FILE.write_text(json.dumps(accounts, indent=2), encoding="utf-8")


def load_instances() -> list:
    if INSTANCES_FILE.is_file():
        try:
            data = json.loads(INSTANCES_FILE.read_text(encoding="utf-8"))
            if isinstance(data, list):
                return data
        except (json.JSONDecodeError, OSError):
            pass
    return []


def save_instances(instances: list) -> None:
    INSTANCES_FILE.write_text(json.dumps(instances, indent=2), encoding="utf-8")


def load_saved_mods() -> list:
    if SAVED_MODS_FILE.is_file():
        try:
            data = json.loads(SAVED_MODS_FILE.read_text(encoding="utf-8"))
            if isinstance(data, list):
                return data
        except (json.JSONDecodeError, OSError):
            pass
    return []


def save_saved_mods(mods: list) -> None:
    SAVED_MODS_FILE.write_text(json.dumps(mods, indent=2), encoding="utf-8")


def load_server_stats() -> dict:
    if SERVER_STATS_FILE.is_file():
        try:
            data = json.loads(SERVER_STATS_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return data
        except (json.JSONDecodeError, OSError):
            pass
    return {}


def save_server_stats(stats: dict) -> None:
    SERVER_STATS_FILE.write_text(json.dumps(stats, indent=2), encoding="utf-8")


def load_instance_history() -> dict:
    if INSTANCE_HISTORY_FILE.is_file():
        try:
            data = json.loads(INSTANCE_HISTORY_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return data
        except (json.JSONDecodeError, OSError):
            pass
    return {}


def save_instance_history(history: dict) -> None:
    INSTANCE_HISTORY_FILE.write_text(json.dumps(history, indent=2), encoding="utf-8")
