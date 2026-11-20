import json
import os
from pathlib import Path

APP_NAME = "MCLauncher"
APP_VERSION = "1.2.1"
UPDATE_REPO = "Shrubb1/MCLauncher"
UPDATE_BRANCH = "master"

# where the launcher itself is installed - resolved from this file, so
# it is always correct wherever the folder lives or moves to
INSTALL_DIR = Path(__file__).resolve().parent.parent


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
