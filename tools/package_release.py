"""Build release/MCLauncher.zip - the clean friend-facing download.

Copies the project into release/MCLauncher/ minus venv, caches, build
output, databases and junk, then zips it. The zip is what you attach to
a GitHub Release (or send directly).

    python tools/package_release.py
"""

import importlib
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from launcher import config  # noqa: E402
OUT = ROOT / "release"
STAGE = OUT / "MCLauncher"
ZIP = OUT / "MCLauncher.zip"

IGNORE_NAMES = {
    ".venv", ".git", ".gradle", "__pycache__", "build", "release",
    "ok_test.txt", "relay.db", "update.zip", "version.txt", "nul",
}
IGNORE_EXTS = {".pyc", ".db", ".wal", ".shm", ".log"}


def ignore(_dir, names):
    return [n for n in names
            if n in IGNORE_NAMES or Path(n).suffix in IGNORE_EXTS]


def bump_version():
    """Auto-bump the patch number in launcher/config.py so every
    packaged build has a fresh version the updater can detect."""
    cfg = ROOT / "launcher" / "config.py"
    text = cfg.read_text()
    m = re.search(r'APP_VERSION = "(\d+)\.(\d+)\.(\d+)"', text)
    if not m:
        raise SystemExit("APP_VERSION not found in config.py")
    new = f"{m.group(1)}.{m.group(2)}.{int(m.group(3)) + 1}"
    cfg.write_text(text.replace(m.group(0), f'APP_VERSION = "{new}"'))
    importlib.reload(config)
    return new


def main():
    print(f"version -> {bump_version()}")
    if STAGE.exists():
        shutil.rmtree(STAGE, ignore_errors=True)
        if (STAGE / ".venv").is_dir():
            raise SystemExit(
                "release/MCLauncher/.venv is locked - a copy of the app "
                "is running from the release folder. Close it first.")
        shutil.rmtree(STAGE, ignore_errors=True)
    OUT.mkdir(exist_ok=True)
    shutil.copytree(ROOT, STAGE, ignore=ignore)

    # hard fail if any live data slipped through
    for bad in ("relay.db", "users.json", "accounts.json",
                "session.json", "purchase_keys.json"):
        hits = list(STAGE.rglob(bad))
        if hits:
            raise SystemExit(f"REFUSING to package: {bad} found at {hits}")

    if ZIP.exists():
        ZIP.unlink()
    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(STAGE.rglob("*")):
            if f.is_file():
                z.write(f, f.relative_to(OUT))
    # stage the update payload for the relay image - 'fly deploy' then
    # ships it at /version + /update.zip for self-updating clients
    shutil.copy2(ZIP, ROOT / "server" / "update.zip")
    (ROOT / "server" / "version.txt").write_text(config.APP_VERSION)

    print(f"{ZIP}  ({ZIP.stat().st_size // 1024} KB)")
    print("Contents:", sum(1 for _ in STAGE.rglob('*') if _.is_file()), "files")
    print(f"Version: {config.APP_VERSION}  ->  server/update.zip staged")

    if "--push" in sys.argv:
        subprocess.run(["git", "add", "-A"], cwd=ROOT, check=True)
        subprocess.run(
            ["git", "-c", "user.name=Devin",
             "-c", "user.email=158243242+devin-ai-integration[bot]"
             "@users.noreply.github.com",
             "commit", "-q", "-m", f"release v{config.APP_VERSION}"],
            cwd=ROOT)
        subprocess.run(["git", "push", "-q"], cwd=ROOT, check=True)
        print("pushed to GitHub")


if __name__ == "__main__":
    raise SystemExit(main())
