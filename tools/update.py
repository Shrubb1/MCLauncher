"""Self-update: pull the latest launcher code from GitHub.

Fetches the remote APP_VERSION from raw.githubusercontent.com, and if
newer downloads the branch zipball and extracts it over this folder.
Falls back to the chat relay's /version + /update.zip for installs
that predate the GitHub channel.

Settings and accounts live in %APPDATA% - nothing personal is touched.

    python tools/update.py
"""

import json
import re
import sys
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from launcher import config  # noqa: E402


def _ver(v):
    try:
        return tuple(int(x) for x in str(v).split("."))
    except Exception:
        return (0,)


def _fetch(url, timeout):
    req = urllib.request.Request(url, headers={"User-Agent": "MCLauncher"})
    return urllib.request.urlopen(req, timeout=timeout).read()


def _github():
    """Return (version, zip_bytes) from GitHub, or None."""
    repo = getattr(config, "UPDATE_REPO", "")
    branch = getattr(config, "UPDATE_BRANCH", "main")
    if not repo:
        return None
    try:
        raw = _fetch(
            f"https://raw.githubusercontent.com/{repo}/{branch}"
            f"/launcher/config.py?v={int(time.time())}", 10).decode()
        remote = (re.search(r'APP_VERSION\s*=\s*"([^"]+)"', raw) or [""])[1]
        if not remote or _ver(remote) <= _ver(config.APP_VERSION):
            return (remote or config.APP_VERSION), None
        return remote, _fetch(
            f"https://codeload.github.com/{repo}/zip/refs/heads/{branch}",
            120)
    except Exception:
        return None


def _relay():
    """Return (version, zip_bytes) from the chat relay, or None."""
    base = str(config.load_settings().get("chat_server") or "").rstrip("/")
    if not base:
        return None
    try:
        remote = json.loads(_fetch(base + "/version", 10)).get("version") or ""
        if not remote or _ver(remote) <= _ver(config.APP_VERSION):
            return remote or config.APP_VERSION, None
        return remote, _fetch(base + "/update.zip", 120)
    except Exception:
        return None


def _extract(data):
    """Extract a source zip over the install; strips the top folder
    ('repo-sha/' from GitHub or 'MCLauncher/' from release zips)."""
    tmp = Path(tempfile.mkdtemp()) / "u.zip"
    tmp.write_bytes(data)
    count = 0
    with zipfile.ZipFile(tmp) as z:
        names = z.namelist()
        top = names[0].split("/")[0] if names else ""
        for n in names:
            if n.endswith("/") or not n.startswith(top + "/"):
                continue
            rel = n[len(top) + 1:]
            if rel.startswith((".venv", "__pycache__")):
                continue
            dest = ROOT / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(z.read(n))
            count += 1
    return count


def main():
    print(f"Installed: {config.APP_VERSION}")
    for name, source in (("GitHub", _github), ("relay", _relay)):
        got = source()
        if not got:
            continue
        remote, data = got
        print(f"Latest ({name}): {remote}")
        if data is None:
            print("Already up to date")
            return 0
        print("Downloading update...")
        count = _extract(data)
        print(f"Updated to {remote} ({count} files) - "
              "restart the launcher")
        return 0
    print("Update check failed: could not reach GitHub or the relay")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
