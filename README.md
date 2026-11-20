# MCLauncher

A lightweight Minecraft Java Edition launcher with Microsoft account support,
mod loader installation (Fabric, Forge, NeoForge, Quilt), per-instance
profiles, friends/chat, and a bundled client-side HUD & QoL mod.

## Quick start

1. Install **Python 3.10+** from https://python.org
   (tick "Add python.exe to PATH" during setup).
2. Unzip this folder anywhere.
3. Double-click **`MCLauncher.vbs`** - the first run installs
   dependencies in a visible console (about a minute); after that the
   launcher opens silently with no console window.
   (`run.bat` does the same thing but keeps a console open - use it if
   something fails and you want to see the error.)

## Using it

- **Profile** - sign up for a launcher account (friends, client mod
  activation). Already connected to the hosted chat server - no setup
  needed.
- **Play** - pick a version + loader and launch.
- **Instances** - save setups as one-click instances (per-instance mods,
  worlds, RAM).
- **Accounts** - add your Microsoft account(s) to actually launch games.
- **Friends** - add people by username and chat with them.
- **Client Mod** - HUD & QoL mod features, license + activation.

## Features

- Microsoft account login (OAuth2 + PKCE, tokens stored locally)
- All Mojang versions: releases, snapshots, legacy alpha/beta
- Mod loaders: Vanilla, Fabric, Forge, NeoForge, Quilt
- Automatic Java runtime download per version
- Isolated per-instance game directories, worlds, mods, backups
- Friends + chat over a hosted relay, license-key client mod activation
- 20 built-in themes (Settings -> Theme)

## Notes

- Accounts and settings are stored in `%APPDATA%\MCLauncher\`
- Game files go to `%APPDATA%\.minecraft\` by default (shared with the
  official launcher) - changeable in Settings
- Microsoft sign-in shows "Prism Launcher" on the consent screen - the
  launcher uses Prism's public Azure app ID (Microsoft requires manual
  approval for custom ones). Your account and tokens are still yours.
- `server/` holds the self-hostable chat relay (Python, zero deps);
  `tools/package_release.py` rebuilds this zip.
