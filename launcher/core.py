"""Core launcher logic: version listing, mod loader installs, launching."""

import base64
import json
import os
import re
import shlex
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import zipfile

import requests

import minecraft_launcher_lib.command as mll_command
import minecraft_launcher_lib.install as mll_install
import minecraft_launcher_lib.mod_loader as mll_mod_loader
import minecraft_launcher_lib.utils as mll_utils

from .config import APP_NAME, APP_VERSION, CONFIG_DIR

VANILLA = "vanilla"
LOADER_IDS = ["fabric", "forge", "neoforge", "quilt"]
LOADER_NAMES = {
    "vanilla": "Vanilla",
    "fabric": "Fabric",
    "forge": "Forge",
    "neoforge": "NeoForge",
    "quilt": "Quilt",
}


def get_minecraft_versions() -> list:
    """All Mojang versions, newest first."""
    return mll_utils.get_version_list()


def get_installed_versions(game_dir: str) -> list:
    return mll_utils.get_installed_versions(game_dir)


def get_loader(loader_id: str):
    return mll_mod_loader.get_mod_loader(loader_id)


def loader_supports(loader_id: str, minecraft_version: str) -> bool:
    if loader_id == VANILLA:
        return True
    try:
        return minecraft_version in get_loader(loader_id).get_minecraft_versions(False)
    except Exception:
        return False


def loader_mc_versions(loader_id: str, stable_only: bool = True) -> list:
    if loader_id == VANILLA:
        return [v["id"] for v in get_minecraft_versions()]
    try:
        return get_loader(loader_id).get_minecraft_versions(stable_only)
    except Exception:
        return []


def loader_versions(loader_id: str, minecraft_version: str, stable_only: bool = True) -> list:
    try:
        return get_loader(loader_id).get_loader_versions(minecraft_version, stable_only)
    except Exception:
        return []


def installed_version_id(loader_id: str, minecraft_version: str, loader_version: str) -> str:
    if loader_id == VANILLA:
        return minecraft_version
    return get_loader(loader_id).get_installed_version(minecraft_version, loader_version)


def is_installed(version_id: str, game_dir: str) -> bool:
    return os.path.isfile(os.path.join(game_dir, "versions", version_id, version_id + ".json"))


def install_version(loader_id: str, minecraft_version: str, loader_version: str,
                    game_dir: str, java: str, callback: dict) -> str:
    """Install the selected version+loader combo. Returns the version id to launch."""
    # mll spawns java subprocesses internally (forge/neoforge installers);
    # hide their console windows by patching Popen for the duration.
    restore = None
    if os.name == "nt":
        original = subprocess.Popen

        def no_window(*args, **kwargs):
            kwargs.setdefault("creationflags", subprocess.CREATE_NO_WINDOW)
            return original(*args, **kwargs)

        subprocess.Popen = no_window
        restore = original
    try:
        if loader_id == VANILLA:
            mll_install.install_minecraft_version(minecraft_version, game_dir,
                                                  callback=callback)
            return minecraft_version

        loader = get_loader(loader_id)
        version_id = loader.install(
            minecraft_version,
            game_dir,
            loader_version=loader_version,
            callback=callback,
            java=java,
        )
        return version_id
    finally:
        if restore:
            subprocess.Popen = restore


def find_java() -> str:
    """Locate a usable java executable (prefers console-less javaw)."""
    path = shutil.which("javaw")
    if path:
        return path
    java = shutil.which("java")
    if java:
        # javaw usually lives next to java in the same JDK/bin
        sibling = os.path.join(os.path.dirname(java), "javaw.exe")
        if os.path.isfile(sibling):
            return sibling
        return java
    return "javaw"


def _jvm_arguments(settings: dict, ram_mb: str = "") -> list:
    ram = str(ram_mb or "").strip() or str(settings.get("default_ram_mb", "4096")).strip() or "4096"
    if not ram.isdigit():
        ram = "4096"
    args = ["-Xms512M", f"-Xmx{ram}M"]

    extra = settings.get("jvm_arguments", "").strip()
    if extra:
        try:
            args += shlex.split(extra, posix=False)
        except ValueError:
            args += extra.split()
    return args


def launch_minecraft(version_id: str, profile: dict, settings: dict,
                     ram_mb: str = "", log_path: str | None = None,
                     game_dir_override: str | None = None,
                     server: str = "", port: str = "",
                     client_mod_jar: str | None = None) -> subprocess.Popen:
    """Build the game command and launch Minecraft. Returns the Popen."""
    game_dir = settings["game_dir"]
    run_dir = game_dir_override or game_dir
    os.makedirs(run_dir, exist_ok=True)

    jvm_args = _jvm_arguments(settings, ram_mb)
    if client_mod_jar:
        jars = (client_mod_jar if isinstance(client_mod_jar, (list, tuple))
                else [client_mod_jar])
        jars = [j for j in jars if j and os.path.isfile(j)]
        if jars:
            jvm_args.append("-Dfabric.addMods=" + os.pathsep.join(jars))

    options = {
        "username": profile.get("name", "Player"),
        "uuid": profile.get("id", ""),
        "token": profile.get("access_token", ""),
        "defaultExecutablePath": settings.get("java_path") or find_java(),
        "jvmArguments": jvm_args,
        "launcherName": APP_NAME,
        "launcherVersion": APP_VERSION,
        "gameDirectory": run_dir,
        "enableLoggingConfig": True,
    }

    if settings.get("java_path"):
        options["executablePath"] = settings["java_path"]

    if settings.get("custom_resolution"):
        options["customResolution"] = True
        options["resolutionWidth"] = str(settings.get("resolution_width", "854"))
        options["resolutionHeight"] = str(settings.get("resolution_height", "480"))

    server = server.strip() or settings.get("auto_join_server", "").strip()
    port = (port.strip() or str(settings.get("auto_join_port", "25565"))).strip() or "25565"
    if server:
        options["server"] = server
        options["port"] = port

    command = mll_command.get_minecraft_command(version_id, game_dir, options)
    flags = {}
    if os.name == "nt":
        flags["creationflags"] = subprocess.CREATE_NO_WINDOW
    if log_path:
        # Keep the handle open - the child process owns it for its lifetime.
        output = open(log_path, "wb", buffering=0)
        return subprocess.Popen(command, cwd=game_dir,
                                stdout=output, stderr=subprocess.STDOUT, **flags)
    return subprocess.Popen(command, cwd=game_dir, **flags)


def _ver_key(v: str) -> tuple:
    m = re.match(r"\s*\d+(?:\.\d+)*", v or "")
    nums = [int(x) for x in m.group(0).split(".")] if m else [0]
    while len(nums) < 3:
        nums.append(0)
    pre = 0 if re.search(r"pre|rc", v or "", re.I) else 1
    return (tuple(nums[:3]), pre)


def _v_cmp(a: str, b: str) -> int:
    ka, kb = _ver_key(a), _ver_key(b)
    return (ka > kb) - (ka < kb)


def mc_satisfies(constraint: str, mc: str) -> bool:
    """Does <mc> satisfy a fabric.mod.json 'depends.minecraft' range?
    Supports *, exact, ~X.Y(-), >=/</<=/> , [a,b) ranges, and '||'/' '."""
    c = (constraint or "").strip()
    if not c or c == "*":
        return True
    for alt in c.split("||"):
        ok = True
        for part in alt.split():
            if not part or part == "*":
                continue
            if part.startswith("~"):
                base = part[1:].rstrip("-")
                nums = [int(x) for x in re.findall(r"\d+", base)]
                hi = ((nums[0] + 1, 0, 0) if len(nums) == 1
                      else (nums[0], nums[1] + 1, 0))
                # trailing '-' (e.g. ~26.2-) includes that series' prereleases
                lo_key = (_ver_key(base)[0],
                          0 if part.endswith("-") else 1)
                mk = _ver_key(mc)
                if mk < lo_key or mk >= (hi, 1):
                    ok = False
                    break
            elif part[0] in "[(":
                lo_s, _, hi_s = part.strip("[]()").partition(",")
                if lo_s.strip():
                    d = _v_cmp(mc, lo_s.strip())
                    if d < 0 or (d == 0 and not part.startswith("[")):
                        ok = False
                        break
                if hi_s.strip():
                    d = _v_cmp(mc, hi_s.strip())
                    if d > 0 or (d == 0 and not part.endswith("]")):
                        ok = False
                        break
            elif part[:2] in (">=", "<=", "==", "!="):
                d = _v_cmp(mc, part[2:])
                op = part[:2]
                if (op == ">=" and d < 0) or (op == "<=" and d > 0) or \
                        (op == "==" and d != 0) or (op == "!=" and d == 0):
                    ok = False
                    break
            elif part[0] in "<>":
                d = _v_cmp(mc, part[1:])
                if (part[0] == ">" and d <= 0) or (part[0] == "<" and d >= 0):
                    ok = False
                    break
            elif _v_cmp(mc, part) != 0:
                ok = False
                break
        if ok:
            return True
    return False


def write_client_activation(enabled: bool, names: list,
                            version: str = "", note: str = "",
                            admin: bool = False) -> None:
    """Activation file the client mod checks at runtime.

    Written on every launch and whenever the global toggle flips so the
    on/off state and the bound-name list follow the jar even when it's
    copied outside our instances - an unbound name gets vanilla behavior.
    version/note let the mod announce a fresh update in-game.
    """
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        (CONFIG_DIR / "activation.json").write_text(
            json.dumps({"enabled": bool(enabled),
                        "names": [n for n in names if n],
                        "version": version, "note": note,
                        "admin": bool(admin)}))
    except OSError:
        pass


def _mod_decl_ok(jar: str | None, mc_version: str):
    """(jar, None) when the jar's declared minecraft range covers
    mc_version, else (None, human reason)."""
    if not jar:
        return None, "not installed"
    try:
        import zipfile as _zf
        with _zf.ZipFile(jar) as z:
            meta = json.loads(z.read("fabric.mod.json"))
        decl = (meta.get("depends") or {}).get("minecraft") or ""
        if decl and not mc_satisfies(decl, mc_version):
            return None, f"built for Minecraft {decl}, not {mc_version}"
    except Exception:
        pass
    return jar, None


def client_mod_for(mc_version: str):
    """Free client mod jar if compatible with mc_version."""
    return _mod_decl_ok(client_mod_jar(), mc_version)


def client_pro_for(mc_version: str):
    """Paid client mod jar if compatible with mc_version."""
    return _mod_decl_ok(client_pro_jar(), mc_version)


def _bundled_jar(name: str) -> str | None:
    """CONFIG_DIR copy of an assets/ jar so the launch path stays fixed."""
    p = CONFIG_DIR / name
    if not p.is_file():
        from pathlib import Path
        bundled = Path(__file__).resolve().parent.parent / "assets" / name
        if bundled.is_file():
            try:
                CONFIG_DIR.mkdir(parents=True, exist_ok=True)
                shutil.copy2(bundled, p)
            except OSError:
                return str(bundled)
        else:
            return None
    return str(p)


def client_mod_jar() -> str | None:
    """Free client mod jar - ships to everyone."""
    return _bundled_jar("mclauncher-client.jar")


def client_pro_jar() -> str | None:
    """Paid client mod jar - obfuscated, activation-gated at runtime."""
    return _bundled_jar("mclauncher-client-pro.jar")


# -------------------------------------------------------------- Worlds

def _dir_size(path: str) -> int:
    total = 0
    for root, _dirs, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


def list_worlds(saves_dir: str) -> list:
    worlds = []
    if not os.path.isdir(saves_dir):
        return worlds
    for name in sorted(os.listdir(saves_dir), key=str.lower):
        path = os.path.join(saves_dir, name)
        if os.path.isdir(path):
            worlds.append({
                "name": name,
                "path": path,
                "size": _dir_size(path),
                "modified": int(os.path.getmtime(path)),
            })
    return worlds


def backup_world(world_dir: str, backups_dir: str, world_name: str) -> str:
    import zipfile as _zip
    os.makedirs(backups_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = os.path.join(backups_dir, f"{world_name}-{stamp}.zip")
    with _zip.ZipFile(dest, "w", _zip.ZIP_DEFLATED) as z:
        for root, _dirs, files in os.walk(world_dir):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, os.path.dirname(world_dir))
                z.write(full, rel)
    return dest


def list_backups(backups_dir: str) -> list:
    out = []
    if not os.path.isdir(backups_dir):
        return out
    for name in sorted(os.listdir(backups_dir), reverse=True):
        if name.endswith(".zip"):
            path = os.path.join(backups_dir, name)
            out.append({"name": name, "path": path,
                        "size": os.path.getsize(path),
                        "modified": int(os.path.getmtime(path))})
    return out


def restore_world(zip_path: str, saves_dir: str) -> str:
    import zipfile as _zip
    base = os.path.basename(zip_path)[:-4]
    world_name = re.sub(r"-\d{8}-\d{6}$", "", base) or base
    dest = os.path.join(saves_dir, world_name)
    if os.path.exists(dest):
        raise FileExistsError(f"World '{world_name}' already exists")
    with _zip.ZipFile(zip_path) as z:
        for member in z.namelist():
            rel = member
            if rel.startswith(world_name + "/"):
                rel = rel[len(world_name) + 1:]
            if not rel or ".." in rel.split("/"):
                continue
            out = os.path.join(dest, rel)
            if member.endswith("/"):
                os.makedirs(out, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(out), exist_ok=True)
                with open(out, "wb") as f:
                    f.write(z.read(member))
    return world_name


def server_from_log(log_text: str) -> tuple:
    """Last server the game connected to, as (host, port) or (None, None)."""
    found = re.findall(r"Connecting to ([\w.\-]+)(?::(\d+)|,\s*(\d+))", log_text)
    if not found:
        return None, None
    host, p1, p2 = found[-1]
    return host, p1 or p2


# ----------------------------------------------------------------- Modrinth

MODRINTH_API = "https://api.modrinth.com/v2"
_GH_REPO = "https://api.github.com/repos/Shrubb1/MCLauncher"
_ANN_URL = _GH_REPO + "/contents/announcements.txt?ref=master"
_ANN_CACHE = {"ts": 0.0, "items": []}


def _ann_from_tags(timeout) -> list:
    """The newest 3 releases as announcements: tag = version pill,
    commit message = title/body. Auto-updates on every tagged push."""
    tags = requests.get(f"{_GH_REPO}/tags?per_page=30",
                        headers={"User-Agent": "mclauncher"},
                        timeout=timeout).json()
    by_sha = {t["commit"]["sha"]: t["name"].lstrip("v") for t in tags}
    commits = requests.get(f"{_GH_REPO}/commits?per_page=25&sha=master",
                           headers={"User-Agent": "mclauncher"},
                           timeout=timeout).json()
    items = []
    for c in commits:
        ver = by_sha.get(c["sha"])
        if not ver:
            continue
        lines = (c["commit"]["message"] or "").splitlines()
        items.append({
            "version": ver,
            "title": (lines[0] if lines else "Update").strip(),
            "body": "\n".join(l for l in lines[1:] if l.strip()).strip(),
            "date": (c["commit"]["committer"]["date"] or "")[:10]})
        if len(items) >= 3:
            break
    return items


def _ann_from_file(timeout) -> list:
    """announcements.txt fallback ('date | title' head blocks)."""
    resp = requests.get(
        _ANN_URL, timeout=timeout,
        headers={"Accept": "application/vnd.github.raw",
                 "User-Agent": "mclauncher"})
    resp.raise_for_status()
    items = []
    for block in resp.text.split("\n---"):
        block = block.strip().strip("-").strip()
        if not block:
            continue
        head, _, body = block.partition("\n")
        date, _, title = head.partition("|")
        items.append({"date": date.strip(), "title": title.strip(),
                      "body": body.strip()})
    return items


def fetch_announcements(timeout=8) -> list:
    """Newest release notes, cached 5 min. The curated announcements.txt
    wins - raw commit messages are dev-facing, not public copy."""
    if time.time() - _ANN_CACHE["ts"] < 300 and _ANN_CACHE["items"]:
        return _ANN_CACHE["items"]
    items = []
    try:
        items = _ann_from_file(timeout)
    except Exception:
        pass
    if not items:
        try:
            items = _ann_from_tags(timeout)
        except Exception:
            return _ANN_CACHE["items"]
    _ANN_CACHE.update({"ts": time.time(), "items": items})
    return items
_MODRINTH_HEADERS = {"User-Agent": f"{APP_NAME}/{APP_VERSION} (MCLauncher)"}


def modrinth_search(query: str, mc_version: str, loader_id: str,
                    limit: int = 15, offset: int = 0, index: str = "downloads",
                    project_type: str = "mod") -> list:
    facets = [[f"project_type:{project_type}"]]
    if mc_version:
        facets.append([f"versions:{mc_version}"])
    if loader_id and loader_id != VANILLA:
        facets.append([f"categories:{loader_id}"])
    resp = requests.get(
        f"{MODRINTH_API}/search",
        params={"query": query, "limit": limit, "offset": offset,
                "index": index, "facets": json.dumps(facets)},
        headers=_MODRINTH_HEADERS, timeout=15,
    )
    resp.raise_for_status()
    return resp.json().get("hits", [])


def modrinth_project_version(project_id: str, mc_version: str,
                             loader_id: str) -> dict:
    """Newest matching full version object (files + dependencies)."""
    params = {}
    if mc_version:
        params["game_versions"] = json.dumps([mc_version])
    if loader_id and loader_id != VANILLA:
        params["loaders"] = json.dumps([loader_id])
    resp = requests.get(f"{MODRINTH_API}/project/{project_id}/version",
                        params=params, headers=_MODRINTH_HEADERS, timeout=15)
    resp.raise_for_status()
    versions = resp.json()
    if not versions:
        raise ValueError("No version found for this MC/loader combination")
    return versions[0]


def modrinth_project_file(project_id: str, mc_version: str,
                          loader_id: str) -> dict:
    """Latest file info {"filename", "url"} for a project+MC+loader."""
    version = modrinth_project_version(project_id, mc_version, loader_id)
    files = version.get("files", [])
    return next((f for f in files if f.get("primary")), files[0])


def download_mod_file(file_info: dict, mods_dir: str) -> str:
    os.makedirs(mods_dir, exist_ok=True)
    dest = os.path.join(mods_dir, file_info["filename"])
    with requests.get(file_info["url"], headers=_MODRINTH_HEADERS,
                      stream=True, timeout=120) as r:
        r.raise_for_status()
        with open(dest, "wb") as f:
            shutil.copyfileobj(r.raw, f)
    return dest


def modrinth_download_with_deps(project_id: str, mc_version: str,
                                loader_id: str, mods_dir: str,
                                progress=None, _depth=0) -> str:
    """Download a mod plus its required dependencies (recursively).

    Returns the primary saved path.
    """
    if _depth > 4:
        raise ValueError("Dependency chain too deep")
    version = modrinth_project_version(project_id, mc_version, loader_id)
    files = version.get("files", [])
    primary = download_mod_file(next((f for f in files if f.get("primary")),
                                     files[0]), mods_dir)
    _record_mod_project(mods_dir, project_id, os.path.basename(primary),
                        version.get("id", ""))
    for dep in version.get("dependencies", []):
        if dep.get("dependency_type") != "required":
            continue
        dep_pid = dep.get("project_id")
        if not dep_pid or installed_project_file(mods_dir, dep_pid):
            continue
        try:
            if progress:
                progress(f"+ dependency {dep_pid}")
            modrinth_download_with_deps(dep_pid, mc_version, loader_id,
                                        mods_dir, progress, _depth + 1)
        except Exception:
            continue  # optional-dep-like failure shouldn't kill the install
    return primary


def modrinth_download(project_id: str, mc_version: str, loader_id: str,
                      mods_dir: str) -> str:
    """Download the newest matching mod file. Returns the saved filename."""
    version = modrinth_project_version(project_id, mc_version, loader_id)
    files = version.get("files", [])
    file_info = next((f for f in files if f.get("primary")), files[0])
    dest = download_mod_file(file_info, mods_dir)
    _record_mod_project(mods_dir, project_id, file_info["filename"],
                        version.get("id", ""))
    return dest


def update_all_mods(mods_dir: str, mc_version: str, loader_id: str,
                    progress=None) -> dict:
    """Update every Modrinth-tracked mod to its newest matching file.

    Returns {"updated": [...], "current": [...], "failed": [(project, err)]}.
    """
    result = {"updated": [], "current": [], "failed": []}
    for project_id, entry in list(_read_mod_map(mods_dir).items()):
        filename = _map_filename(entry)
        try:
            if progress:
                progress(project_id)
            version = modrinth_project_version(project_id, mc_version, loader_id)
            files = version.get("files", [])
            latest = next((f for f in files if f.get("primary")), files[0])
            old_path = os.path.join(mods_dir, filename)
            was_disabled = filename.endswith(".disabled")
            if latest["filename"] == filename or \
                    latest["filename"] + ".disabled" == filename:
                result["current"].append(project_id)
                continue
            dest = download_mod_file(latest, mods_dir)
            if was_disabled:
                disabled = dest + ".disabled"
                os.replace(dest, disabled)
                _record_mod_project(mods_dir, project_id,
                                    os.path.basename(disabled),
                                    version.get("id", ""))
            else:
                _record_mod_project(mods_dir, project_id, latest["filename"],
                                    version.get("id", ""))
            if os.path.isfile(old_path):
                os.remove(old_path)
            result["updated"].append(latest["filename"])
        except Exception as e:
            result["failed"].append((project_id, str(e)))
    return result


def check_mod_updates(mods_dir: str, mc_version: str,
                      loader_id: str) -> list:
    """List Modrinth-tracked mods with a newer file available (no download).

    Returns [{"project", "current", "latest"}] for each outdated mod."""
    out = []
    for project_id, entry in list(_read_mod_map(mods_dir).items()):
        filename = _map_filename(entry)
        try:
            version = modrinth_project_version(
                project_id, mc_version, loader_id)
            files = version.get("files", [])
            latest = next((f for f in files if f.get("primary")),
                          files[0])
            if latest["filename"] != filename and \
                    latest["filename"] + ".disabled" != filename:
                out.append({"project": project_id, "current": filename,
                            "latest": latest["filename"],
                            "version_id": version.get("id", "")})
        except Exception:
            continue
    return out


def update_single_mod(mods_dir: str, project_id: str, mc_version: str,
                      loader_id: str) -> str:
    """Update one tracked mod. Returns the new filename."""
    entry = _read_mod_map(mods_dir).get(project_id)
    filename = _map_filename(entry or "")
    version = modrinth_project_version(project_id, mc_version, loader_id)
    files = version.get("files", [])
    latest = next((f for f in files if f.get("primary")), files[0])
    dest = download_mod_file(latest, mods_dir)
    if filename.endswith(".disabled"):
        os.replace(dest, dest + ".disabled")
        _record_mod_project(mods_dir, project_id,
                            os.path.basename(dest) + ".disabled",
                            version.get("id", ""))
    else:
        _record_mod_project(mods_dir, project_id, latest["filename"],
                            version.get("id", ""))
    old = os.path.join(mods_dir, filename)
    if filename and os.path.isfile(old):
        os.remove(old)
    return latest["filename"]


def _mod_map_path(mods_dir: str) -> str:
    return os.path.join(mods_dir, ".modrinth_map.json")


def _read_mod_map(mods_dir: str) -> dict:
    try:
        with open(_mod_map_path(mods_dir), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}


def _record_mod_project(mods_dir: str, project_id: str, filename: str,
                        version_id: str = "") -> None:
    data = _read_mod_map(mods_dir)
    data[project_id] = {"file": filename, "version": version_id}
    try:
        with open(_mod_map_path(mods_dir), "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except OSError:
        pass


def _map_filename(entry) -> str:
    return entry["file"] if isinstance(entry, dict) else entry


def _map_version(entry) -> str:
    return entry.get("version", "") if isinstance(entry, dict) else ""


def installed_project_file(mods_dir: str, project_id: str) -> str | None:
    """Return the filename already installed for a Modrinth project, if any."""
    name = _map_filename(_read_mod_map(mods_dir).get(project_id) or "")
    if name and os.path.isfile(os.path.join(mods_dir, name)):
        return name
    return None


MODPACK_LOADER_KEYS = {
    "fabric-loader": "fabric",
    "quilt-loader": "quilt",
    "neoforge": "neoforge",
    "forge": "forge",
}


def modrinth_latest_file_url(project_id: str) -> str:
    """Primary download URL of the newest version of a project."""
    resp = requests.get(f"{MODRINTH_API}/project/{project_id}/version",
                        params={"limit": 1}, headers=_MODRINTH_HEADERS, timeout=15)
    resp.raise_for_status()
    versions = resp.json()
    if not versions or not versions[0].get("files"):
        raise ValueError("No downloadable version found")
    files = versions[0]["files"]
    file_info = next((f for f in files if f.get("primary")), files[0])
    return file_info["url"]


def modrinth_install_modpack(pack_url: str, dest_dir: str,
                             progress=None) -> dict:
    """Download + unpack a .mrpack into dest_dir. Returns index dependencies."""
    import tempfile
    import zipfile as _zip

    fd, tmp = tempfile.mkstemp(suffix=".mrpack")
    os.close(fd)
    try:
        with requests.get(pack_url, headers=_MODRINTH_HEADERS,
                          stream=True, timeout=300) as r:
            r.raise_for_status()
            with open(tmp, "wb") as f:
                shutil.copyfileobj(r.raw, f)

        with _zip.ZipFile(tmp) as z:
            index = json.loads(z.read("modrinth.index.json").decode("utf-8"))
            os.makedirs(dest_dir, exist_ok=True)
            for member in z.namelist():
                if member.startswith("overrides/") and not member.endswith("/"):
                    rel = member[len("overrides/"):]
                    out = os.path.join(dest_dir, rel)
                    os.makedirs(os.path.dirname(out), exist_ok=True)
                    with open(out, "wb") as f:
                        f.write(z.read(member))
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass

    files = index.get("files") or []
    total = len(files)
    for i, entry in enumerate(files):
        env = (entry.get("env") or {}).get("client", "required")
        if env == "unsupported":
            continue
        rel = entry.get("path", "")
        url = (entry.get("downloads") or [None])[0]
        if not url or not rel or ".." in rel.split("/"):
            continue
        out = os.path.join(dest_dir, rel)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        if progress:
            progress(f"{i + 1}/{total} {os.path.basename(rel)}")
        with requests.get(url, headers=_MODRINTH_HEADERS,
                          stream=True, timeout=180) as fr:
            fr.raise_for_status()
            with open(out, "wb") as f:
                shutil.copyfileobj(fr.raw, f)
    return index.get("dependencies") or {}


# -------------------------------------------------------------------- Mods

def list_mods(mods_dir: str) -> list:
    mods = []
    if not os.path.isdir(mods_dir):
        return mods
    for name in sorted(os.listdir(mods_dir), key=str.lower):
        path = os.path.join(mods_dir, name)
        if not os.path.isfile(path):
            continue
        if name.endswith(".jar"):
            mods.append({"name": name[:-4], "enabled": True, "path": path})
        elif name.endswith(".jar.disabled"):
            mods.append({"name": name[:-13], "enabled": False, "path": path})
    return mods


def set_mod_enabled(path: str, enabled: bool) -> str:
    new_path = path[:-9] if enabled else path + ".disabled"
    os.replace(path, new_path)
    return new_path


def delete_mod(path: str) -> None:
    os.remove(path)


def jar_mod_info(path: str) -> dict:
    """Extract mod ids + declared incompatibilities from a mod jar.

    Returns {"ids": set[str], "breaks": set[str], "name": str}.
    """
    info = {"ids": set(), "breaks": set(), "name": os.path.basename(path)}
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            if "fabric.mod.json" in names:
                meta = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"))
                info["ids"].add(meta.get("id", ""))
                info["ids"] |= set(meta.get("provides") or [])
                info["name"] = meta.get("name") or meta.get("id") or info["name"]
                info["breaks"] |= set((meta.get("breaks") or {}).keys())
            elif "quilt.mod.json" in names:
                meta = json.loads(z.read("quilt.mod.json").decode("utf-8", "replace"))
                loader = meta.get("quilt_loader") or {}
                info["ids"].add(loader.get("id", ""))
                info["name"] = (meta.get("metadata") or {}).get("name") or info["name"]
                info["breaks"] |= set((loader.get("breaks") or {}).keys())
            else:
                for toml_name in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
                    if toml_name in names:
                        text = z.read(toml_name).decode("utf-8", "replace")
                        info["ids"] |= set(re.findall(r'modId\s*=\s*"([^"]+)"', text))
                        info["ids"] |= set(re.findall(r"modid\s*=\s*'([^']+)'", text))
                        m = re.search(r'displayName\s*=\s*"([^"]+)"', text)
                        if m:
                            info["name"] = m.group(1)
                        break
    except (zipfile.BadZipFile, OSError, json.JSONDecodeError, KeyError):
        pass
    info["ids"].discard("")
    return info


RENDERING_MODS = {"sodium", "embeddium", "iris", "rubidium", "optifine", "optifabric"}
KNOWN_ENEMIES = {
    "optifine": "OptiFine",
    "optifabric": "OptiFabric",
}


def check_mod_conflicts(new_jar_path: str, mods_dir: str) -> dict:
    """Find incompatibilities between a new jar and jars already in mods_dir.

    Returns {"blockers": [...], "warnings": [...]}:
    - blockers: duplicate mod ids and well-known fatal pairs (hard-stop installs)
    - warnings: declared `breaks` metadata (version-ranged; may still work)
    """
    new_info = jar_mod_info(new_jar_path)
    new_real = os.path.realpath(new_jar_path)
    blockers, warnings = [], []

    for mod in list_mods(mods_dir):
        if os.path.realpath(mod["path"]) == new_real:
            continue
        other = jar_mod_info(mod["path"])
        shared = new_info["ids"] & other["ids"]
        if shared:
            blockers.append(f"Already installed: {other['name']} ({sorted(shared)[0]})")
            continue
        if new_info["breaks"] & other["ids"]:
            warnings.append(
                f"'{new_info['name']}' may conflict with '{other['name']}'")
        if other["breaks"] & new_info["ids"]:
            warnings.append(
                f"'{other['name']}' may conflict with '{new_info['name']}'")

    for enemy_id, enemy_name in KNOWN_ENEMIES.items():
        new_is_enemy = enemy_id in new_info["ids"]
        for mod in list_mods(mods_dir):
            if os.path.realpath(mod["path"]) == new_real:
                continue
            other = jar_mod_info(mod["path"])
            if new_is_enemy and other["ids"] & (RENDERING_MODS - {enemy_id}):
                blockers.append(f"{enemy_name} breaks rendering mods like {other['name']}")
            elif enemy_id in other["ids"] and new_info["ids"] & RENDERING_MODS:
                blockers.append(f"'{new_info['name']}' cannot run alongside {enemy_name}")

    return {"blockers": blockers, "warnings": warnings}


# --------------------------------------------------------- Crash diagnosis

def diagnose_crash(log_text: str) -> dict:
    """Scan game output for known failure patterns.

    Returns {"findings": [str, ...], "fix": {"type": ..., "value": ...} | None}.
    """
    findings = []
    fix = None

    for line in log_text.splitlines():
        m = re.search(
            r"Mod '([^']+)'.*?requires.*?(?:version ([\w.\-]+)|any version between ([\w.\-]+) and "
            r"([\w.\-]+)).*?of 'Minecraft'.*?wrong version is present: ([\w.\-]+)", line)
        if m:
            required = m.group(2) or m.group(3) or "?"
            findings.append(
                f"'{m.group(1)}' is built for Minecraft {required} "
                f"but this instance runs {m.group(5)}")
            if not fix:
                fix = {"type": "mc_version", "value": required}
            continue

        m = re.search(r"requires version ([\w.\-]+) of ([\w\-.]+), which is missing", line)
        if m:
            findings.append(f"Missing dependency: '{m.group(2)}' {m.group(1)}")
            if not fix:
                fix = {"type": "missing_mod", "value": m.group(2)}
            continue

        m = re.search(r"requires ([\w\-.]+) ([\w.\-]+) or later", line)
        if m:
            findings.append(f"Missing dependency: '{m.group(1)}' {m.group(2)}+")
            if not fix:
                fix = {"type": "missing_mod", "value": m.group(1)}

    if re.search(r"OutOfMemoryError|GC overhead limit", log_text):
        findings.append("Out of memory - raise the instance's RAM setting")
        if not fix:
            fix = {"type": "ram"}

    m = re.search(r"class file version ([\d.]+)", log_text)
    if m:
        findings.append("A mod needs a newer Java - update the Java path in Settings")

    if re.search(r"Failed to log in|Invalid session|access token", log_text, re.I):
        findings.append("Account session expired - remove and re-add the account")

    return {"findings": findings, "fix": fix}


# --------------------------------------------------------- Share codes

def make_share_code(inst: dict, mods_dir: str) -> str:
    """Encode an instance + its Modrinth mod list as a compact code."""
    payload = {
        "name": inst.get("name", "Shared instance"),
        "mc": inst.get("mc_version", ""),
        "loader": inst.get("loader_id", "vanilla"),
        "lv": inst.get("loader_version", ""),
        "mods": sorted(_read_mod_map(mods_dir).keys()),
    }
    raw = json.dumps(payload, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def parse_share_code(code: str) -> dict:
    code = code.strip()
    code += "=" * (-len(code) % 4)
    return json.loads(base64.urlsafe_b64decode(code).decode("utf-8"))


# ----------------------------------------------------- World snapshots

def _link_or_copy(src: str, dst: str) -> None:
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def snapshot_world(world_dir: str, snaps_root: str) -> str:
    """Incremental snapshot: unchanged files are hardlinked (0 bytes).

    Returns the snapshot directory path.
    """
    previous = None
    if os.path.isdir(snaps_root):
        snaps = sorted(os.listdir(snaps_root), reverse=True)
        if snaps:
            previous = os.path.join(snaps_root, snaps[0])

    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = os.path.join(snaps_root, stamp)
    suffix = 1
    while os.path.exists(dest):
        dest = os.path.join(snaps_root, f"{stamp}-{suffix}")
        suffix += 1
    os.makedirs(dest, exist_ok=True)
    for root, _dirs, files in os.walk(world_dir):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, world_dir)
            out = os.path.join(dest, rel)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            prev_file = os.path.join(previous, rel) if previous else None
            if prev_file and os.path.isfile(prev_file):
                try:
                    a, b = os.stat(full), os.stat(prev_file)
                    if a.st_size == b.st_size and \
                            a.st_mtime_ns == b.st_mtime_ns:
                        _link_or_copy(prev_file, out)
                        continue
                except OSError:
                    pass
            shutil.copy2(full, out)
    return dest


def list_snapshots(snaps_root: str) -> list:
    out = []
    if not os.path.isdir(snaps_root):
        return out
    for name in sorted(os.listdir(snaps_root), reverse=True):
        path = os.path.join(snaps_root, name)
        if os.path.isdir(path):
            out.append({"name": name, "path": path,
                        "modified": int(os.path.getmtime(path))})
    return out


def restore_snapshot(snap_dir: str, world_dir: str) -> None:
    for root, _dirs, files in os.walk(snap_dir):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, snap_dir)
            out = os.path.join(world_dir, rel)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            shutil.copy2(full, out)


# ------------------------------------------------------ Shared folders

SHARED_FOLDER_NAMES = ("resourcepacks", "shaderpacks", "screenshots",
                       "schematics")


def ensure_shared_link(inst_dir: str, name: str, shared_root: str) -> bool:
    """Junction instance subfolder -> shared central folder (Win10+, no admin).

    Migrates existing files into the shared folder on first link.
    """
    target = os.path.join(shared_root, name)
    os.makedirs(target, exist_ok=True)
    link = os.path.join(inst_dir, name)
    try:
        if os.path.islink(link) or os.path.isdir(link) and \
                os.path.realpath(link) == os.path.realpath(target):
            return True
        if os.path.isdir(link):
            # migrate existing content into the shared dir, then junction
            for item in os.listdir(link):
                src = os.path.join(link, item)
                dst = os.path.join(target, item)
                if not os.path.exists(dst):
                    shutil.move(src, dst)
            shutil.rmtree(link)
        elif os.path.exists(link):
            os.remove(link)
        if os.name == "nt":
            subprocess.run(["cmd", "/c", "mklink", "/J", link, target],
                           capture_output=True, creationflags=getattr(
                               subprocess, "CREATE_NO_WINDOW", 0))
            return os.path.isdir(link)
        os.symlink(target, link, target_is_directory=True)
        return True
    except OSError:
        return False


# ---------------------------------------------------------- JVM monitor

def required_java_major(mc_version: str) -> int:
    """Java major an MC version needs: 1.20.5+ -> 21, 1.18-1.20.4 -> 17,
    1.17.x -> 16, older -> 8. New 26.x scheme -> newest LTS."""
    parts = re.findall(r"\d+", mc_version)
    if len(parts) >= 2:
        major, minor = int(parts[0]), int(parts[1])
        if major >= 26:
            return 25
        if major >= 21:
            return major
        if major == 1:
            if minor >= 21:
                return 21
            if minor == 20:
                patch = int(parts[2]) if len(parts) >= 3 else 0
                return 21 if patch >= 5 else 17
            if minor == 18 or minor == 19:
                return 17
            if minor == 17:
                return 16
            return 8
    return 17


def download_java(major: int, dest_root, progress=None) -> str | None:
    """Fetch Temurin JRE <major> (windows x64) into dest_root and return
    the javaw.exe path inside it. None on failure."""
    import zipfile
    url = ("https://api.adoptium.net/v3/binary/latest/"
           f"{major}/ga/windows/x64/jre/hotspot/normal/eclipse")
    dest_root = Path(dest_root)
    dest_root.mkdir(parents=True, exist_ok=True)
    # already downloaded?
    for jw in dest_root.rglob("javaw.exe"):
        return str(jw)
    tmp = dest_root / f"_jre{major}.zip"
    try:
        with requests.get(url, stream=True, timeout=30,
                          headers={"User-Agent": "mclauncher"}) as resp:
            resp.raise_for_status()
            total = int(resp.headers.get("Content-Length") or 0)
            done = 0
            with open(tmp, "wb") as f:
                for chunk in resp.iter_content(1 << 20):
                    f.write(chunk)
                    done += len(chunk)
                    if progress and total:
                        progress(int(done * 100 / total))
        with zipfile.ZipFile(tmp) as z:
            z.extractall(dest_root / f"temurin-{major}")
        tmp.unlink(missing_ok=True)
        for jw in dest_root.rglob("javaw.exe"):
            return str(jw)
        return None
    except Exception:
        tmp.unlink(missing_ok=True)
        return None


def java_major_version(java_path: str) -> int | None:
    """Parse 'java -version' output into a major version (25, 21, 17, 8...)."""
    try:
        out = subprocess.run(
            [java_path, "-version"], capture_output=True, text=True,
            timeout=10, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        text = out.stderr + out.stdout
        m = re.search(r'version "(\d+)(?:\.(\d+))?', text)
        if not m:
            return None
        first, second = int(m.group(1)), int(m.group(2) or 0)
        return second if first == 1 else first
    except Exception:
        return None


def find_jstat() -> str | None:
    java = find_java()
    cand = os.path.join(os.path.dirname(java), "jstat.exe")
    return cand if os.path.isfile(cand) else None


def jstat_heap(pid: int) -> dict | None:
    """Sample heap via jstat. Returns {"used_kb", "cap_kb", "gc_secs"}."""
    jstat = find_jstat()
    if not jstat:
        return None
    try:
        out = subprocess.run(
            [jstat, "-gc", str(pid)], capture_output=True, text=True,
            timeout=10, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        lines = out.stdout.strip().splitlines()
        if len(lines) < 2:
            return None
        cols = lines[0].split()
        vals = [float(v) for v in lines[1].split()]
        data = dict(zip(cols, vals))
        used = data.get("S0U", 0) + data.get("S1U", 0) + data.get("EU", 0) + \
            data.get("OU", 0)
        cap = data.get("S0C", 0) + data.get("S1C", 0) + data.get("EC", 0) + \
            data.get("OC", 0)
        return {"used_kb": used, "cap_kb": cap, "gc_secs": data.get("GCT", 0)}
    except Exception:
        return None


# ---------------------------------------------------------- Server ping

def _varint(value: int) -> bytes:
    value &= 0xFFFFFFFF
    out = b""
    while True:
        b = value & 0x7F
        value >>= 7
        out += bytes([b | (0x80 if value else 0)])
        if not value:
            return out


def _srv_lookup(host: str, timeout: float = 5.0):
    """Resolve _minecraft._tcp.<host> SRV via the system resolver (nslookup).

    Returns (target_host, port) or None. Matches how the game client joins
    bare domains - needed for Hypixel-style networks fronted by Cloudflare.
    """
    try:
        out = subprocess.run(
            ["nslookup", "-type=SRV", f"_minecraft._tcp.{host}"],
            capture_output=True, text=True, timeout=timeout,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        target = re.search(r"(?i)svr hostname\s*=\s*(\S+)", out.stdout) or \
            re.search(r"(?i)hostname\s*=\s*(\S+\.\S+)", out.stdout)
        port_m = re.search(r"(?i)\bport\s*=\s*(\d+)", out.stdout)
        if target:
            return target.group(1).rstrip("."), \
                int(port_m.group(1)) if port_m else 25565
    except Exception:
        pass
    return None


def ping_server(host: str, port: int = None, timeout: float = 5.0) -> dict:
    """Minecraft server list ping. Returns the status JSON as a dict.

    When port is None, an SRV lookup is attempted first (same as the game
    client), falling back to 25565.
    """
    connect_host, connect_port = host, port or 25565
    if port is None:
        srv = _srv_lookup(host, timeout=min(timeout, 4.0))
        if srv:
            connect_host, connect_port = srv
    # the handshake still carries the original hostname (virtual host routing)
    host_b = host.encode("utf-8")
    handshake = b"\x00" + _varint(-1) + _varint(len(host_b)) + host_b + \
        struct.pack(">H", connect_port) + b"\x01"
    request = b"\x00"
    with socket.create_connection((connect_host, connect_port),
                                  timeout=timeout) as s:
        s.sendall(_varint(len(handshake)) + handshake +
                  _varint(len(request)) + request)
        # response: varint len, varint packet id, varint str len, json
        def read_varint():
            num = 0
            for i in range(5):
                chunk = s.recv(1)
                if not chunk:
                    raise ConnectionError("server closed the connection")
                b = chunk[0]
                num |= (b & 0x7F) << (7 * i)
                if not b & 0x80:
                    return num
            raise ValueError("bad varint")

        read_varint()          # packet length
        read_varint()          # packet id
        strlen = read_varint()
        data = b""
        while len(data) < strlen:
            chunk = s.recv(strlen - len(data))
            if not chunk:
                raise ConnectionError("server closed mid-response")
            data += chunk
    return json.loads(data.decode("utf-8", "replace"))


def probe_server(host: str, port: int = None, timeout: float = 5.0) -> dict:
    """Ping a server, falling back to the mcsrvstat.us cache if the edge
    drops raw TCP probes (Hypixel-style filtering).

    Returns the standard SLP-shaped dict, with an extra 'via' key.
    """
    try:
        r = ping_server(host, port, timeout)
        r["via"] = "direct"
        return r
    except Exception:
        pass
    addr = host if port in (None, 25565) else f"{host}:{port}"
    resp = requests.get(f"https://api.mcsrvstat.us/3/{addr}", timeout=timeout + 4)
    resp.raise_for_status()
    d = resp.json()
    if not d.get("online"):
        raise ConnectionError("server is offline")
    motd = (d.get("motd") or {}).get("clean") or [""]
    return {
        "version": {"name": str(d.get("version", "?")),
                    "protocol": d.get("protocol", 0)},
        "players": {"online": (d.get("players") or {}).get("online", 0),
                    "max": (d.get("players") or {}).get("max", 0)},
        "description": {"text": "\n".join(motd)},
        "favicon": d.get("icon"),
        "via": "cached status",
    }


_MC_FMT = re.compile("§[0-9A-FK-OR]", re.IGNORECASE)


def clean_motd(description) -> str:
    """Extract plain text from a server MOTD and strip § formatting codes.

    'description' may be a plain string, a chat component dict
    ({text, extra:[...]}) or a list of components.
    """
    if isinstance(description, str):
        text = description
    elif isinstance(description, list):
        text = "".join(clean_motd(p) for p in description)
    elif isinstance(description, dict):
        text = (description.get("text") or "") + "".join(
            clean_motd(e) for e in description.get("extra") or [])
    else:
        return ""
    return _MC_FMT.sub("", text)


# ----------------------------------------------------------- level.dat

def read_level_dat(path: str) -> dict:
    """Minimal gzip-NBT reader: seed, gamemode, day count from level.dat."""
    import gzip

    def read_name(buf, i):
        ln = struct.unpack(">H", buf[i:i + 2])[0]
        return buf[i + 2:i + 2 + ln].decode("utf-8", "replace"), i + 2 + ln

    def skip(buf, i, tag):
        sizes = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
        if tag in sizes:
            return i + sizes[tag]
        if tag == 7:
            n = struct.unpack(">i", buf[i:i + 4])[0]
            return i + 4 + n
        if tag == 8:
            ln = struct.unpack(">H", buf[i:i + 2])[0]
            return i + 2 + ln
        if tag == 9:
            inner = buf[i]
            n = struct.unpack(">i", buf[i + 1:i + 5])[0]
            i += 5
            for _ in range(max(0, n)):
                i = skip(buf, i, inner)
            return i
        if tag == 10:
            while buf[i] != 0:
                inner = buf[i]
                _name, i = read_name(buf, i + 1)
                i = skip(buf, i, inner)
            return i + 1
        if tag == 11:
            n = struct.unpack(">i", buf[i:i + 4])[0]
            return i + 4 + n * 4
        if tag == 12:
            n = struct.unpack(">i", buf[i:i + 4])[0]
            return i + 4 + n * 8
        return i

    def walk(buf, i, end_tag=None):
        result = {}
        while i < len(buf):
            tag = buf[i]
            if tag == 0:
                return result, i + 1
            name, j = read_name(buf, i + 1)
            if tag == 3:
                result[name] = struct.unpack(">i", buf[j:j + 4])[0]
                i = j + 4
            elif tag == 4:
                result[name] = struct.unpack(">q", buf[j:j + 8])[0]
                i = j + 8
            elif tag == 8:
                ln = struct.unpack(">H", buf[j:j + 2])[0]
                result[name] = buf[j + 2:j + 2 + ln].decode("utf-8", "replace")
                i = j + 2 + ln
            elif tag == 10:
                sub, i = walk(buf, j)
                result[name] = sub
            else:
                i = skip(buf, j, tag)
        return result, i

    try:
        raw = gzip.open(path, "rb").read()
        _name, start = read_name(raw, 1)  # root: tag byte + name, then entries
        data, _ = walk(raw, start)
        d = data.get("Data", {}) if isinstance(data, dict) else {}
        return {
            "seed": d.get("RandomSeed") or (d.get("WorldGenSettings") or {}).get("seed"),
            "gamemode": d.get("GameType"),
            "days": (d.get("DayTime") or 0) // 24000,
            "level_name": d.get("LevelName", ""),
        }
    except Exception:
        return {}


# --------------------------------------------------------------- Toast

def toast_notify(title: str, message: str) -> None:
    """Fire a Windows balloon notification (no extra deps)."""
    if os.name != "nt":
        return
    script = (
        "Add-Type -AssemblyName System.Windows.Forms;"
        "$n = New-Object System.Windows.Forms.NotifyIcon;"
        "$n.Icon = [System.Drawing.SystemIcons]::Information;"
        "$n.Visible = $true;"
        f"$n.ShowBalloonTip(3000, {json.dumps(title)}, {json.dumps(message)},"
        " 'Info');"
        "Start-Sleep -Seconds 4"
    )
    subprocess.Popen(
        ["powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script],
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


# --------------------------------------------------- Update rollback

def snapshot_mod_map(mods_dir: str) -> None:
    src = _mod_map_path(mods_dir)
    if os.path.isfile(src):
        shutil.copy2(src, src + ".rollback")


def rollback_mods(mods_dir: str, mc_version: str, loader_id: str,
                  progress=None) -> int:
    """Reinstall the mod versions captured before the last Update-all."""
    rb = _mod_map_path(mods_dir) + ".rollback"
    try:
        old_map = json.load(open(rb, encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return 0
    restored = 0
    for pid, entry in old_map.items():
        filename = _map_filename(entry)
        vid = _map_version(entry)
        try:
            if progress:
                progress(pid)
            if vid:
                resp = requests.get(f"{MODRINTH_API}/version/{vid}",
                                    headers=_MODRINTH_HEADERS, timeout=15)
                resp.raise_for_status()
                ver = resp.json()
            else:
                ver = modrinth_project_version(pid, mc_version, loader_id)
            files = ver.get("files", [])
            f = next((x for x in files if x.get("primary")), files[0])
            final = os.path.join(mods_dir, filename)
            if not os.path.isfile(final):
                downloaded = download_mod_file(f, mods_dir)
                if os.path.basename(downloaded) != filename and \
                        os.path.isfile(downloaded):
                    os.replace(downloaded, final)
            cur = _map_filename(_read_mod_map(mods_dir).get(pid) or "")
            cur_path = os.path.join(mods_dir, cur) if cur else ""
            if cur and cur != filename and os.path.isfile(cur_path):
                os.remove(cur_path)
            _record_mod_project(mods_dir, pid, filename, vid)
            restored += 1
        except Exception:
            continue
    return restored


# -------------------------------------------------------- Server export

def mojang_server_url(mc_version: str) -> str:
    """Vanilla server jar download URL for an MC version."""
    manifest = requests.get(
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
        timeout=15).json()
    entry = next((v for v in manifest["versions"] if v["id"] == mc_version), None)
    if not entry:
        raise ValueError(f"Unknown version {mc_version}")
    data = requests.get(entry["url"], timeout=15).json()
    url = (data.get("downloads") or {}).get("server", {}).get("url")
    if not url:
        raise ValueError("No server jar for this version")
    return url


def project_side(project_id: str) -> dict:
    """{'client_side', 'server_side'} flags for a Modrinth project."""
    resp = requests.get(f"{MODRINTH_API}/project/{project_id}",
                        headers=_MODRINTH_HEADERS, timeout=15)
    resp.raise_for_status()
    d = resp.json()
    return {"client_side": d.get("client_side", "required"),
            "server_side": d.get("server_side", "optional")}


def export_server(inst: dict, mods_dir: str, dest_dir: str,
                  settings: dict, progress=None) -> int:
    """Build a ready-to-run server folder. Returns count of mods copied."""
    os.makedirs(dest_dir, exist_ok=True)
    if progress:
        progress("Downloading server jar...")
    url = mojang_server_url(inst["mc_version"])
    with requests.get(url, stream=True, timeout=300) as r:
        r.raise_for_status()
        with open(os.path.join(dest_dir, "server.jar"), "wb") as f:
            shutil.copyfileobj(r.raw, f)

    copied = 0
    for pid, entry in _read_mod_map(mods_dir).items():
        filename = _map_filename(entry)
        try:
            side = project_side(pid)
            if side["server_side"] == "unsupported":
                continue
            src = os.path.join(mods_dir, filename.replace(".disabled", ""))
            out_dir = os.path.join(dest_dir, "mods")
            os.makedirs(out_dir, exist_ok=True)
            if os.path.isfile(src):
                shutil.copy2(src, os.path.join(out_dir, os.path.basename(src)))
                copied += 1
            if progress:
                progress(f"Mods: {copied}")
        except Exception:
            continue

    with open(os.path.join(dest_dir, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    ram = str(inst.get("ram_mb") or settings.get("default_ram_mb", "4096"))
    loader = inst.get("loader_id", "vanilla")
    with open(os.path.join(dest_dir, "start.bat"), "w") as f:
        f.write("@echo off\n")
        if loader == "fabric":
            f.write("echo Download fabric-installer.jar, run: java -jar "
                    "fabric-installer.jar server -mcversion "
                    f"{inst['mc_version']} -downloadMinecraft\n")
        f.write(f'java -Xmx{ram}M -Xms1024M -jar server.jar nogui\npause\n')
    return copied
