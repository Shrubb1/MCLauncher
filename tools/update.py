"""Self-update: pull the latest launcher zip from the configured relay.

Checks <chat_server>/version against the installed APP_VERSION; if the
server is newer it downloads <chat_server>/update.zip and extracts it
over this folder. Settings and accounts live in %APPDATA% so nothing
personal is touched.

    python tools/update.py
"""

import json
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
import sys
sys.path.insert(0, str(ROOT))

from launcher import config  # noqa: E402


def _ver(v):
    try:
        return tuple(int(x) for x in str(v).split("."))
    except Exception:
        return (0,)


def main():
    base = str(config.load_settings().get("chat_server") or "").rstrip("/")
    if not base:
        print("No chat server configured - cannot check for updates")
        return 1
    try:
        remote = json.loads(urllib.request.urlopen(
            base + "/version", timeout=10).read()).get("version") or ""
    except Exception as e:
        print(f"Update check failed: {e}")
        return 1
    print(f"Installed: {config.APP_VERSION}    Latest: {remote or '?'}")
    if not remote or _ver(remote) <= _ver(config.APP_VERSION):
        print("Already up to date")
        return 0

    print("Downloading update...")
    try:
        data = urllib.request.urlopen(
            base + "/update.zip", timeout=120).read()
    except Exception as e:
        print(f"Download failed: {e}")
        return 1

    tmp = Path(tempfile.mkdtemp()) / "update.zip"
    tmp.write_bytes(data)
    count = 0
    with zipfile.ZipFile(tmp) as z:
        for n in z.namelist():
            if not n.startswith("MCLauncher/") or n.endswith("/"):
                continue
            rel = n[len("MCLauncher/"):]
            if rel.startswith((".venv", "__pycache__")):
                continue
            dest = ROOT / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(z.read(n))
            count += 1
    print(f"Updated to {remote} ({count} files) - restart the launcher")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
