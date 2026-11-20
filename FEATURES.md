# MCLauncher — What Everything Does

A custom Minecraft launcher. Pin it, click it, play. Everything below is what it can do.

---

## The Sidebar

- **Play** — quick-launch any Minecraft version with any loader
- **Instances** — your saved setups (modded or not)
- **Servers** — how long you've played on each server
- **Accounts** — your Microsoft/Minecraft accounts
- **Settings** — launcher-wide options

---

## Accounts

- **Add Microsoft Account** — opens your browser, you sign in, it comes back automatically. No passwords typed into the app
- Tokens are saved on your PC and refreshed every launch — you only sign in once per account
- You can add **multiple accounts** and switch between them in one click (dropdown on Play and Instances pages)
- **Remove** deletes an account from the launcher

## Play (quick launch)

- Pick **Vanilla, Fabric, Forge, NeoForge, or Quilt**
- Pick any Minecraft version — search to filter, checkboxes show **snapshots** and old **alpha/beta** versions
- Pick the loader version (newest is pre-selected, rarely needs changing)
- **PLAY** downloads everything the first time, then launches. Correct Java is handled for you
- **Save as Instance** — turns the current selection into a saved instance

## Instances

A saved combo of: name, MC version, loader, RAM, and optionally a server. Cards are sorted by **most recently played**.

Each card shows: `version · loader · RAM · hours played — last played`, plus:

- **Play** — launch it
- **Edit** — rename, change version/loader/RAM/server
- **Delete** — asks first, then removes the instance and its files
- **Mods** — opens the mod manager (below)
- **Folder** — opens its files in Explorer
- **Duplicate** — copies the setup
- **Worlds** — world list + backups (below)

**Every instance is isolated** — its own `mods/`, `saves/`, `resourcepacks/`, and settings. Mods for one version can never break another version's instance.

**Edit options:**
- Name auto-follows the version+loader ("1.21 Forge") until you type a custom one
- Version picker is a searchable scrollable list
- **RAM override** — blank uses the global default
- **Auto-join server** — this instance launches straight into a server; blank uses the global setting

## Mods (Modrinth browser)

Opened from an instance's **Mods** button. Four tabs:

- **Installed** — every mod in the folder. **Enable/Disable** a mod without deleting it, **Delete** (asks first), **Update all** upgrades every Modrinth-installed mod to the newest version for your MC/loader
- **Browse Modrinth** — the entire Modrinth catalogue (~50k mods), auto-filtered to your instance's version + loader. Shows top mods immediately, search to find more, sort by Downloads/Relevance/Follows/Newest/Updated, flip order with ↑↓, **Load more** for more results. If nothing matches your version, it falls back and shows everything with a note
- **Modpacks** — same browsing for modpacks. **Install** creates a whole new instance: right MC version, right loader, all the pack's mods and configs downloaded for you
- **Saved** — bookmarked mods/packs. **Save** on any result adds it here. Saved forever, shared across instances — handy for re-installing after deleting an instance

**Safety checks on every mod install:**
- Skips if already installed
- **Blocks** known-bad combos (e.g. OptiFine next to Sodium/Iris, duplicate mods)
- **Warns** when mods declare possible conflicts — you choose whether to install anyway

## Worlds

Per-instance world manager:

- Lists every world with its size
- **Backup** — zips the world into a timestamped backup
- **Restore** — brings a backup back (won't overwrite an existing world)
- **Delete** — asks first
- Backups live in `%APPDATA%\MCLauncher\backups\` — they survive deleting the instance

## Servers

Automatic server playtime tracker:

- Reads the game log while you play (checks every ~30 seconds)
- Every server you join gets a row: total hours + when you last played
- Sorted by most played — see how much of your life Hypixel owns

## Settings

- **Game directory** — where Minecraft files live (shared `.minecraft` by default; instances are still isolated from it)
- **Java path** — override which Java runs the game
- **Default RAM** — global memory limit (each instance can override)
- **Custom resolution** — window size on launch
- **JVM arguments** — extra flags appended to the launch
- **Auto-join server + port** — every launch joins this server (unless an instance overrides it)

## Crash help

If Minecraft dies, instead of a silent exit you get:

- **Plain-English findings** — it reads the log for you: "Sodium is built for 1.20 but this is 1.21", "missing dependency fabric-api", "out of memory", "session expired", "Java too old"
- **Fix buttons** — "Switch instance to X", "Find the missing mod on Modrinth", "Edit RAM"
- Full log tail in the window + a "Full log" button

## Behind the scenes

- **No console windows** — everything launches hidden
- **Taskbar** — the pinned icon merges with the running window, one icon total
- **Config location** — everything lives in `%APPDATA%\MCLauncher\`: `settings.json`, `accounts.json`, `instances.json`, `saved_mods.json`, `server_stats.json`, `instances\<id>\` (instance files), `backups\`, `game_output.log`
