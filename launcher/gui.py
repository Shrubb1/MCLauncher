"""CustomTkinter GUI for the launcher."""

import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import tkinter as tk
import uuid
import webbrowser
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog

import customtkinter as ctk

from . import auth, chat, config, core, users
from .themes import CTK_THEME_PATH, PALETTE, THEME_NAME, THEMES

ctk.set_appearance_mode("light" if THEME_NAME == "white" else "dark")
ctk.set_default_color_theme(CTK_THEME_PATH)

# Font objects are created in LauncherApp.__init__ (a Tk root must exist first)
FONT = None
FONT_BOLD = None
FONT_TITLE = None
FONT_SMALL = None


def _init_fonts():
    global FONT, FONT_BOLD, FONT_TITLE, FONT_SMALL
    FONT = ctk.CTkFont(family="Segoe UI", size=13)
    FONT_BOLD = ctk.CTkFont(family="Segoe UI", size=13, weight="bold")
    FONT_TITLE = ctk.CTkFont(family="Segoe UI", size=20, weight="bold")
    FONT_SMALL = ctk.CTkFont(family="Segoe UI", size=11)

LEGACY_TYPES = {"old_alpha", "old_beta"}
MAX_RENDERED_VERSIONS = 200
FILTER_DEBOUNCE_MS = 250

JVM_PRESETS = {
    "Standard (recommended)": "",
    "Low memory / older PC": "-XX:+UseG1GC -XX:MaxGCPauseMillis=130 "
                             "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC",
    "Lots of RAM / new Java (ZGC)": "-XX:+UseZGC",
    "Max performance (Aikar's flags)": "-XX:+UseG1GC -XX:+ParallelRefProcEnabled "
                     "-XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions "
                     "-XX:+DisableExplicitGC -XX:+AlwaysPreTouch "
                     "-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 "
                     "-XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 "
                     "-XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 "
                     "-XX:InitiatingHeapOccupancyPercent=15 "
                     "-XX:G1MixedGCLiveThresholdPercent=90 "
                     "-XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 "
                     "-XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1",
}
ZGC_PRESET = "Lots of RAM / new Java (ZGC)"


def _scroll_frame(parent, **kw):
    """CTkScrollableFrame with a fat, clearly-visible scrollbar thumb."""
    f = ctk.CTkScrollableFrame(parent, **kw)
    f._scrollbar.configure(width=16, button_color=PALETTE["muted_hover"],
                           button_hover_color=PALETTE["accent"])
    return f


def _title_box(parent, text):
    """Page title in an accent-bordered chip."""
    box = ctk.CTkFrame(parent, fg_color="transparent", border_width=1,
                       border_color=PALETTE["accent"], corner_radius=4)
    ctk.CTkLabel(box, text=text, font=FONT_TITLE,
                 text_color=PALETTE["accent"]).pack(padx=10, pady=2)
    return box


class LauncherApp(ctk.CTk):
    def __init__(self):
        super().__init__()
        _init_fonts()
        self.title("MCLauncher" + (f"  [{THEME_NAME}]"
                                   if os.environ.get("MCL_THEME") else ""))

        if os.name == "nt":
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("MCLauncher.App")
        icon_path = Path(__file__).resolve().parent.parent / "assets" / "icon.png"
        if icon_path.is_file():
            self._icon_img = tk.PhotoImage(file=str(icon_path))
            self.iconphoto(True, self._icon_img)
        self.geometry("1020x680")
        pos = os.environ.get("MCL_POS")
        if pos:
            self.geometry(f"+{pos}")
        self.minsize(900, 580)

        self.settings = config.load_settings()
        if self.settings.get("install_dir") != str(config.INSTALL_DIR):
            self.settings["install_dir"] = str(config.INSTALL_DIR)
            config.save_settings(self.settings)
        self._geom_after = None
        preview = bool(os.environ.get("MCL_THEME"))
        if not preview:
            geo = str(self.settings.get("window_geometry") or "")
            if re.match(r"^\d+x\d+[-+]-?\d+[-+]-?\d+$", geo):
                self.geometry(geo)
            if self.settings.get("window_state") == "zoomed":
                self.after(120, lambda: self.state("zoomed"))
        self.bind("<Configure>", self._on_configure)
        self.protocol("WM_DELETE_WINDOW", self._on_close)
        self.accounts = config.load_accounts()
        self.instances = config.load_instances()
        self._launching = False
        self._running = {}      # instance_id -> Popen
        self._stopping = set()  # instance_ids killed via Stop button
        self._hung = set()      # instance_ids with stalled log output
        self._live_heap = {}    # instance_id -> last jstat sample dict
        self._session_advice = {}  # instance_id -> RAM suggestion text
        self._nav_dots = {}     # nav name -> red-dot badge widget
        self._unread = set()    # friends with unread messages
        self._new_requests = False
        self._chat_open = set()  # friends with an open chat window
        self._last_read = dict(self.settings.get("read_msgs") or {})

        self._ui_queue = queue.Queue()
        self.after(50, self._poll_ui_queue)

        self.mc_versions = []          # list of {"id","type"}
        self.supported_versions = {}   # loader_id -> set of mc version ids
        self.version_buttons = {}      # mc_version -> CTkButton
        self.selected_mc_version = None
        self.show_legacy = tk.BooleanVar(value=False)
        self.show_snapshots = tk.BooleanVar(value=False)
        self.loader_id = "vanilla"
        self.loader_versions = []
        self._supported_pending = set()
        self._filter_job = None
        self._account_var = tk.StringVar(value="")

        self._build_ui()
        self._show_page("home")
        self._refresh_account_menu()
        self._update_dismissed = None
        self._pending_update = None
        self.after(2000, self._update_check)
        self.after(4000, self._msg_poll)
        threading.Thread(target=self._load_versions, daemon=True).start()

    # ------------------------------------------------------------- helpers

    def ui_call(self, fn, *args, **kwargs):
        """Marshal a callable onto the Tk main thread (safe from workers)."""
        self._ui_queue.put((fn, args, kwargs))

    def _poll_ui_queue(self):
        while True:
            try:
                fn, args, kwargs = self._ui_queue.get_nowait()
            except queue.Empty:
                break
            try:
                fn(*args, **kwargs)
            except Exception:
                import traceback
                traceback.print_exc()
        self.after(50, self._poll_ui_queue)

    # ------------------------------------------------------------------ UI

    def _build_ui(self):
        self.grid_columnconfigure(1, weight=1)
        self.grid_rowconfigure(0, weight=1)

        sidebar = ctk.CTkFrame(self, width=170, corner_radius=0, fg_color=PALETTE["sidebar"])
        sidebar.grid(row=0, column=0, sticky="nsew")
        sidebar.grid_propagate(False)

        _title_box(sidebar, "MCLauncher").pack(pady=(24, 4))
        ctk.CTkLabel(sidebar, text="Minecraft launcher", font=FONT_SMALL,
                     text_color=("gray35", "gray60")).pack(pady=(0, 20))

        self.nav_buttons = {}
        for name, label in (("profile", "Profile"), ("home", "Play"),
                            ("instances", "Instances"),
                            ("servers", "Servers"), ("accounts", "Accounts"),
                            ("friends", "Friends"), ("settings", "Settings"),
                            ("mod", "Client Mod")):
            btn = ctk.CTkButton(
                sidebar, text=label, anchor="w", height=38,
                font=FONT_BOLD, fg_color="transparent", text_color=("gray20", "gray80"),
                hover_color=PALETTE["nav"], command=lambda n=name: self._show_page(n),
            )
            btn.pack(fill="x", padx=12, pady=2)
            btn.bind("<Enter>",
                     lambda e, n=name: self._sync_badge_bg(n), add="+")
            btn.bind("<Leave>",
                     lambda e, n=name: self._sync_badge_bg(n), add="+")
            btn.bind("<ButtonRelease-1>",
                     lambda e, n=name: self._sync_badge_bg(n), add="+")
            self.nav_buttons[name] = btn

        self.version_label = ctk.CTkLabel(
            sidebar, text=f"v{config.APP_VERSION}", font=FONT_SMALL,
            text_color=PALETTE["muted_hover"])
        self.version_label.pack(side="bottom", pady=12)

        self._content = ctk.CTkFrame(self, fg_color="transparent")
        content = self._content
        content.grid(row=0, column=1, sticky="nsew")
        content.grid_columnconfigure(0, weight=1)
        content.grid_rowconfigure(1, weight=1)

        self.pages = {
            "home": self._build_home(content),
            "instances": self._build_instances(content),
            "servers": self._build_servers(content),
            "accounts": self._build_accounts(content),
            "profile": self._build_profile(content),
            "friends": self._build_friends(content),
            "settings": self._build_settings(content),
            "mod": self._build_mod(content),
        }
        for frame in self.pages.values():
            frame.grid(row=1, column=0, sticky="nsew")
            frame.grid_remove()

    def _show_page(self, name):
        if name == "servers":
            self._render_servers()
        elif name == "profile":
            self._render_profile()
        elif name == "friends":
            self._render_friends()
        elif name == "mod":
            self._render_mod()
        self._active_page = name
        for page_name, frame in self.pages.items():
            if page_name == name:
                frame.grid()
                self.nav_buttons[page_name].configure(fg_color=PALETTE["accent"],
                                                      text_color=("white", "white"))
            else:
                frame.grid_remove()
                self.nav_buttons[page_name].configure(fg_color="transparent",
                                                      text_color=("gray20", "gray80"))
        for n in self.nav_buttons:
            self._sync_badge_bg(n)

    # ------------------------------------------------------------- Home page

    def _build_home(self, parent):
        frame = ctk.CTkFrame(parent, fg_color="transparent")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(4, weight=1)

        _title_box(frame, "Play").grid(row=0, column=0, sticky="w", padx=8, pady=(20, 4))

        # Account row
        acct_row = ctk.CTkFrame(frame, fg_color="transparent")
        acct_row.grid(row=1, column=0, sticky="ew", padx=8, pady=8)
        ctk.CTkLabel(acct_row, text="Account", font=FONT).pack(side="left")
        self.account_menu = ctk.CTkOptionMenu(acct_row, values=["No accounts"], width=220,
                                              variable=self._account_var, font=FONT)
        self.account_menu.pack(side="left", padx=12)
        ctk.CTkButton(acct_row, text="Manage", width=90, font=FONT,
                      command=lambda: self._show_page("accounts")).pack(side="left")

        # Loader selector
        loader_row = ctk.CTkFrame(frame, fg_color="transparent")
        loader_row.grid(row=2, column=0, sticky="ew", padx=8, pady=8)
        ctk.CTkLabel(loader_row, text="Version type", font=FONT).pack(side="left")
        self.loader_seg = ctk.CTkSegmentedButton(
            loader_row,
            values=["Vanilla", "Fabric", "Forge", "NeoForge", "Quilt"],
            command=self._on_loader_changed, font=FONT,
        )
        self.loader_seg.set("Vanilla")
        self.loader_seg.pack(side="left", padx=12)

        # Loader version row (hidden for vanilla)
        self.loader_ver_row = ctk.CTkFrame(frame, fg_color="transparent")
        self.loader_ver_row.grid(row=3, column=0, sticky="ew", padx=8, pady=(0, 4))
        ctk.CTkLabel(self.loader_ver_row, text="Loader version", font=FONT).pack(side="left")
        self.loader_ver_menu = ctk.CTkOptionMenu(self.loader_ver_row, values=["..."],
                                                 width=220, font=FONT)
        self.loader_ver_menu.pack(side="left", padx=12)

        # Version picker
        ver_frame = ctk.CTkFrame(frame)
        ver_frame.grid(row=4, column=0, sticky="nsew", padx=8, pady=8)
        ver_frame.grid_columnconfigure(0, weight=1)
        ver_frame.grid_rowconfigure(1, weight=1)

        top = ctk.CTkFrame(ver_frame, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=8, pady=8)
        ctk.CTkLabel(top, text="Minecraft version", font=FONT_BOLD).pack(side="left")
        self.version_filter = ctk.CTkEntry(top, placeholder_text="Filter...", width=160, font=FONT)
        self.version_filter.pack(side="right")
        self.version_filter.bind("<KeyRelease>", self._on_filter_typing)
        ctk.CTkCheckBox(top, text="Show legacy (alpha/beta)", variable=self.show_legacy,
                        command=self._render_version_list, font=FONT_SMALL).pack(side="right", padx=12)
        ctk.CTkCheckBox(top, text="Snapshots", variable=self.show_snapshots,
                        command=self._render_version_list, font=FONT_SMALL).pack(side="right")

        self.version_scroll = _scroll_frame(ver_frame, label_text="")
        self.version_scroll.grid(row=1, column=0, sticky="nsew", padx=8, pady=(0, 8))

        # Status + progress + play
        bottom = ctk.CTkFrame(frame, fg_color="transparent")
        bottom.grid(row=5, column=0, sticky="ew", padx=8, pady=(4, 16))

        self.status_label = ctk.CTkLabel(bottom, text="", font=FONT_SMALL,
                                         text_color=("gray35", "gray60"), anchor="w")
        self.status_label.pack(fill="x", pady=(0, 4))

        self._progress_visible = False
        self.progress_bar = ctk.CTkProgressBar(bottom, height=8)
        self.progress_bar.set(0)

        btn_row = ctk.CTkFrame(bottom, fg_color="transparent")
        btn_row.pack(fill="x")
        btn_row.grid_columnconfigure(0, weight=1)

        self.play_button = ctk.CTkButton(btn_row, text="PLAY", height=52, font=ctk.CTkFont(size=18, weight="bold"),
                                         command=self._on_play)
        self.play_button.grid(row=0, column=0, sticky="ew", padx=(0, 8))

        ctk.CTkButton(btn_row, text="Save as Instance", height=52, width=140, font=FONT,
                      fg_color=PALETTE["nav"], text_color=("gray15", "gray85"),
                      hover_color=PALETTE["muted"],
                      command=self._save_current_as_instance).grid(row=0, column=1)

        return frame

    # --------------------------------------------------------- Instances page

    def _build_instances(self, parent):
        frame = ctk.CTkFrame(parent, fg_color="transparent")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)

        top = ctk.CTkFrame(frame, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=8, pady=(20, 8))
        _title_box(top, "Instances").pack(side="left")
        ctk.CTkButton(top, text="Import code", width=100, height=28, font=FONT_SMALL,
                      fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                      command=self._import_share_code).pack(side="right", padx=(0, 10))
        self.instances_account_menu = ctk.CTkOptionMenu(
            top, values=["No accounts"], width=180,
            variable=self._account_var, font=FONT)
        self.instances_account_menu.pack(side="right", padx=(0, 12))
        ctk.CTkLabel(top, text="Accounts:", font=FONT).pack(side="right", padx=(0, 6))

        self.instances_scroll = _scroll_frame(frame, label_text="")
        self.instances_scroll.grid(row=1, column=0, sticky="nsew", padx=8, pady=(4, 16))
        self._render_instances()

        return frame

    def _render_instances(self):
        for child in self.instances_scroll.winfo_children():
            child.destroy()
        if not self.instances:
            ctk.CTkLabel(
                self.instances_scroll,
                text="No instances yet - pick a version on the Play tab and click 'Save as Instance'",
                font=FONT, text_color=("gray35", "gray60"),
            ).pack(pady=20)
            return

        ordered = sorted(self.instances, key=lambda i: i.get("last_played", 0), reverse=True)
        for inst in ordered:
            card = ctk.CTkFrame(self.instances_scroll, fg_color=PALETTE["card"])
            card.pack(fill="x", pady=4, padx=4)

            info = ctk.CTkFrame(card, fg_color="transparent")
            info.pack(side="left", padx=12, pady=10, fill="x", expand=True)

            name_text = inst.get("name", "Instance")
            if inst["id"] in self._running:
                name_text = "● " + name_text
            if inst["id"] in self._hung:
                name_text += "  (quiet 90s+ - may be hung)"
            ctk.CTkLabel(info, text=name_text, font=FONT_BOLD,
                         text_color=("#e0a030", "#e0a030") if inst["id"] in self._hung
                         else ("#25a34a", "#25a34a") if inst["id"] in self._running
                         else ("gray15", "gray85")).pack(anchor="w")
            meta = inst["mc_version"]
            heap = self._live_heap.get(inst["id"])
            if inst["id"] in self._running and heap:
                meta += f" · heap {heap['used_kb'] / 1048576:.1f}GB"
            if inst.get("loader_id") != "vanilla":
                meta += f" · {core.LOADER_NAMES.get(inst.get('loader_id'), inst.get('loader_id'))}"
                if inst.get("loader_version"):
                    meta += f" {inst['loader_version']}"
            if inst.get("ram_mb"):
                meta += f" · {inst['ram_mb']}MB"
            secs = int(inst.get("playtime_secs", 0) or 0)
            if secs:
                meta += f" · {self._fmt_duration(secs)} played"
            if inst.get("account_name"):
                meta += f" · {inst['account_name']}"
            last = inst.get("last_played", 0)
            meta += f"  —  {self._fmt_last_played(last)}"
            ctk.CTkLabel(info, text=meta, font=FONT_SMALL,
                         text_color=("gray35", "gray60")).pack(anchor="w")

            mini_row = ctk.CTkFrame(info, fg_color="transparent")
            mini_row.pack(anchor="w", pady=(6, 0))
            ctk.CTkButton(mini_row, text="Mods", width=58, height=24, font=FONT_SMALL,
                          fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                          command=lambda i=inst: self._open_mod_browser(i)
                          ).pack(side="left", padx=(0, 4))
            ctk.CTkButton(mini_row, text="Folder", width=58, height=24, font=FONT_SMALL,
                          fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                          command=lambda i=inst: self._instance_open_folder(i)
                          ).pack(side="left", padx=4)
            ctk.CTkButton(mini_row, text="Duplicate", width=58, height=24, font=FONT_SMALL,
                          fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                          command=lambda i=inst: self._instance_duplicate(i)
                          ).pack(side="left", padx=4)
            ctk.CTkButton(mini_row, text="Worlds", width=58, height=24, font=FONT_SMALL,
                          fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                          command=lambda i=inst: self._open_worlds_dialog(i)
                          ).pack(side="left", padx=4)
            ctk.CTkButton(mini_row, text="Shots", width=58, height=24, font=FONT_SMALL,
                          fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                          command=lambda i=inst: self._open_screenshots(i)
                          ).pack(side="left", padx=4)
            ctk.CTkButton(mini_row, text="Share", width=58, height=24, font=FONT_SMALL,
                          fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                          command=lambda i=inst: self._share_instance(i)
                          ).pack(side="left", padx=4)
            ctk.CTkButton(mini_row, text="Server", width=58, height=24, font=FONT_SMALL,
                          fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                          command=lambda i=inst: self._export_server(i)
                          ).pack(side="left", padx=4)

            ctk.CTkButton(card, text="Delete", width=70, font=FONT_SMALL,
                          fg_color=("#a33", "#a33"), hover_color=("#c44", "#c44"),
                          command=lambda iid=inst["id"]: self._instance_delete(iid)
                          ).pack(side="right", padx=(4, 12), pady=8)
            ctk.CTkButton(card, text="Edit", width=70, font=FONT_SMALL,
                          fg_color=PALETTE["muted"],
                          command=lambda i=inst: self._open_instance_editor(i)
                          ).pack(side="right", padx=4, pady=8)
            if inst["id"] in self._running:
                ctk.CTkButton(card, text="Stop", width=80, font=FONT_SMALL,
                              fg_color=("#a33", "#a33"), hover_color=("#c44", "#c44"),
                              command=lambda iid=inst["id"]: self._instance_stop(iid)
                              ).pack(side="right", padx=4, pady=8)
            else:
                ctk.CTkButton(card, text="Play", width=80, font=FONT_SMALL,
                              fg_color=("#1a7f37", "#1a7f37"),
                              hover_color=("#25a34a", "#25a34a"),
                              command=lambda i=inst: self._instance_play(i)
                              ).pack(side="right", padx=4, pady=8)

    @staticmethod
    def _fmt_last_played(ts):
        if not ts:
            return "never played"
        delta = int(time.time()) - ts
        if delta < 60:
            return "just now"
        if delta < 3600:
            return f"{delta // 60}m ago"
        if delta < 86400:
            return f"{delta // 3600}h ago"
        return f"{delta // 86400}d ago"

    @staticmethod
    def _fmt_duration(secs):
        if secs < 60:
            return f"{secs}s"
        if secs < 3600:
            return f"{secs // 60}m"
        hours, mins = secs // 3600, (secs % 3600) // 60
        return f"{hours}h {mins}m" if mins else f"{hours}h"

    # ------------------------------------------------------------ Servers page

    def _build_servers(self, parent):
        frame = ctk.CTkFrame(parent, fg_color="transparent")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)

        top = ctk.CTkFrame(frame, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=8, pady=(20, 8))
        _title_box(top, "Servers").pack(side="left")

        probe = ctk.CTkFrame(frame, fg_color="transparent")
        probe.grid(row=1, column=0, sticky="ew", padx=8, pady=(0, 8))
        probe.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(probe, text="Probe:", font=FONT).grid(row=0, column=0, padx=(0, 8))
        self.probe_entry = ctk.CTkEntry(
            probe, font=FONT,
            placeholder_text="server address, e.g. hypixel.net")
        self.probe_entry.grid(row=0, column=1, sticky="ew", padx=(0, 8))
        self.probe_entry.bind("<Return>", lambda e: self._probe_server())
        ctk.CTkButton(probe, text="Check", width=90, font=FONT,
                      command=self._probe_server).grid(row=0, column=2)
        self.probe_status = ctk.CTkLabel(probe, text="", font=FONT_SMALL,
                                         text_color=("gray35", "gray60"), anchor="w")
        self.probe_status.grid(row=1, column=0, columnspan=3, sticky="w", pady=(6, 0))
        self.probe_result = None

        self.servers_scroll = _scroll_frame(frame)
        self.servers_scroll.grid(row=2, column=0, sticky="nsew", padx=8, pady=(4, 16))
        frame.grid_rowconfigure(2, weight=1)
        return frame

    def _probe_server(self):
        addr = self.probe_entry.get().strip()
        if not addr:
            return
        host, sep, port_s = addr.partition(":")
        try:
            port = int(port_s) if sep else None  # None -> SRV lookup first
        except ValueError:
            self.probe_status.configure(text="Invalid port")
            return
        self.probe_status.configure(text=f"Pinging {addr}...")
        if self.probe_result:
            self.probe_result.destroy()
            self.probe_result = None

        def work():
            try:
                r = core.probe_server(host, port, timeout=6)
                self.ui_call(self._show_probe_result, host, port, r)
            except Exception as e:
                msg = {"TimeoutError": "timed out - offline or wrong port",
                       "gaierror": "address not found",
                       "ConnectionRefusedError": "connection refused"}.get(
                           type(e).__name__, f"failed: {e}")
                self.ui_call(self.probe_status.configure,
                             {"text": f"{host}: {msg}"})

        threading.Thread(target=work, daemon=True).start()

    def _show_probe_result(self, host, port, r):
        port = port or 25565
        ver = r.get("version") or {}
        players = r.get("players") or {}
        motd = core.clean_motd(r.get("description")).replace("\n", " ").strip()
        ver_name = ver.get("name", "?")
        via = f" [{r['via']}]" if r.get("via") == "cached status" else ""
        self.probe_status.configure(
            text=f"{host}:{port} — {ver_name} · "
                 f"{players.get('online', '?')}/{players.get('max', '?')} online"
                 + (f" · {motd[:60]}" if motd else "") + via)

        self.probe_result = ctk.CTkFrame(self.pages["servers"], fg_color=PALETTE["card"])
        self.probe_result.grid(row=3, column=0, sticky="ew", padx=8, pady=(0, 12))

        mc_guess = next((v["id"] for v in self.mc_versions
                         if v.get("type") == "release" and v["id"] in str(ver_name)),
                        None)

        def make_instance():
            mc = mc_guess or ver_name
            existing = {i["mc_version"] for i in self.instances}
            if mc not in existing or True:
                self._open_instance_editor(prefill={
                    "name": f"{host} {mc}", "loader_id": "vanilla",
                    "mc_version": mc, "loader_version": "",
                    "server": host, "server_port": str(port)})

        ctk.CTkButton(self.probe_result, text=f"Create {ver_name} instance →",
                      font=FONT_SMALL, fg_color=("#1a7f37", "#1a7f37"),
                      hover_color=("#25a34a", "#25a34a"),
                      command=make_instance).pack(side="right", padx=10, pady=8)
        ctk.CTkLabel(self.probe_result,
                     text=f"Server runs {ver_name}"
                     + (f" (matched {mc_guess})" if mc_guess else " - version not in your list"),
                     font=FONT).pack(side="left", padx=10, pady=8)

    def _render_servers(self):
        if not hasattr(self, "servers_scroll"):
            return
        for child in self.servers_scroll.winfo_children():
            child.destroy()
        stats = config.load_server_stats()
        if not stats:
            ctk.CTkLabel(
                self.servers_scroll,
                text="No server playtime yet - launch a game and join a server",
                font=FONT, text_color=("gray35", "gray60")).pack(pady=20)
            return
        ordered = sorted(stats.items(), key=lambda kv: kv[1].get("secs", 0),
                         reverse=True)
        for host, data in ordered:
            row = ctk.CTkFrame(self.servers_scroll, fg_color=PALETTE["card"])
            row.pack(fill="x", pady=4, padx=4)
            ctk.CTkLabel(row, text=host, font=FONT_BOLD).pack(
                side="left", padx=12, pady=10)
            meta = f"{self._fmt_duration(data.get('secs', 0))}  —  " \
                   f"{self._fmt_last_played(data.get('last_played', 0))}"
            ctk.CTkLabel(row, text=meta, font=FONT_SMALL,
                         text_color=("gray35", "gray60")).pack(side="left", padx=8)

            inst_id = data.get("last_instance_id")
            inst = next((i for i in self.instances if i.get("id") == inst_id), None)
            if inst:
                if inst_id in self._running:
                    ctk.CTkLabel(row, text="● running", font=FONT_SMALL,
                                 text_color=("#25a34a", "#25a34a")
                                 ).pack(side="right", padx=12)
                else:
                    ctk.CTkButton(
                        row, text=f"Play ({inst.get('name', '?')})", width=150,
                        height=26, font=FONT_SMALL,
                        fg_color=("#1a7f37", "#1a7f37"),
                        hover_color=("#25a34a", "#25a34a"),
                        command=lambda i=inst: self._instance_play(i)
                        ).pack(side="right", padx=12)

    def _save_current_as_instance(self):
        loader_id = self._current_loader()
        mc_version = self.selected_mc_version
        if not mc_version:
            messagebox.showwarning("No version", "Select a Minecraft version first.")
            return
        loader_version = self.loader_ver_menu.get() if loader_id != "vanilla" else ""
        if loader_id != "vanilla" and loader_version in ("...", "(none)", "Loading..."):
            messagebox.showwarning("Loader version", "Loader versions are still loading.")
            return
        name = mc_version if loader_id == "vanilla" else f"{mc_version} {core.LOADER_NAMES[loader_id]}"
        self._open_instance_editor(prefill={
            "name": name, "loader_id": loader_id,
            "mc_version": mc_version, "loader_version": loader_version or "",
        })

    def _instance_delete(self, inst_id):
        inst = next((i for i in self.instances if i.get("id") == inst_id), {})
        name = inst.get("name", "this instance")
        if not messagebox.askyesno("Delete instance",
                                   f"Delete '{name}'? Its game folder will be removed too."):
            return
        self.instances = [i for i in self.instances if i.get("id") != inst_id]
        config.save_instances(self.instances)
        shutil.rmtree(config.CONFIG_DIR / "instances" / inst_id, ignore_errors=True)
        self._render_instances()

    def _instance_dir(self, inst):
        if inst.get("separate_game_dir", True):
            return config.CONFIG_DIR / "instances" / inst["id"]
        return Path(self.settings["game_dir"])

    def _instance_mods_dir(self, inst):
        mods = self._instance_dir(inst) / "mods"
        mods.mkdir(parents=True, exist_ok=True)
        return mods

    def _instance_stop(self, inst_id):
        proc = self._running.get(inst_id)
        if proc and proc.poll() is None:
            self._stopping.add(inst_id)
            proc.terminate()
        else:
            self._running.pop(inst_id, None)
            self._render_instances()

    def _instance_open_folder(self, inst):
        path = self._instance_dir(inst)
        path.mkdir(parents=True, exist_ok=True)
        os.startfile(str(path))

    def _instance_duplicate(self, inst):
        copy = dict(inst)
        copy["id"] = uuid.uuid4().hex
        copy["name"] = f"{inst.get('name', 'Instance')} Copy"
        copy["last_played"] = 0
        copy["created"] = int(time.time())
        self.instances = config.load_instances()
        self.instances.append(copy)
        config.save_instances(self.instances)
        self._render_instances()

    # ------------------------------------------------ Share / import codes

    def _share_instance(self, inst):
        code = core.make_share_code(inst, str(self._instance_mods_dir(inst)))

        dlg = ctk.CTkToplevel(self)
        dlg.title(f"Share '{inst.get('name', 'Instance')}'")
        dlg.geometry("560x240")
        dlg.transient(self)
        dlg.grid_columnconfigure(0, weight=1)

        ctk.CTkLabel(
            dlg, text="Send this code to a friend - they paste it into "
                      "'Import code' on the Instances page and get the same "
                      "version + mods downloaded automatically.",
            font=FONT_SMALL, text_color=("gray35", "gray60"), wraplength=520,
            justify="left").grid(row=0, column=0, sticky="w", padx=16, pady=(14, 6))

        box = ctk.CTkTextbox(dlg, height=80, font=FONT_SMALL, wrap="char")
        box.grid(row=1, column=0, sticky="ew", padx=16, pady=6)
        box.insert("1.0", code)
        box.configure(state="disabled")

        btn_row = ctk.CTkFrame(dlg, fg_color="transparent")
        btn_row.grid(row=2, column=0, sticky="e", padx=16, pady=10)
        ctk.CTkButton(btn_row, text="Close", width=90,
                      fg_color=PALETTE["muted"],
                      command=dlg.destroy).pack(side="right", padx=6)
        ctk.CTkButton(btn_row, text="Copy to clipboard", width=130,
                      command=lambda: (self.clipboard_clear(),
                                       self.clipboard_append(code),
                                       dlg.destroy())).pack(side="right", padx=6)

    def _import_share_code(self):
        dlg = ctk.CTkToplevel(self)
        dlg.title("Import setup code")
        dlg.geometry("560x250")
        dlg.transient(self)
        dlg.grid_columnconfigure(0, weight=1)

        ctk.CTkLabel(dlg, text="Paste a code someone shared with you:",
                     font=FONT).grid(row=0, column=0, sticky="w", padx=16, pady=(16, 6))
        entry = ctk.CTkEntry(dlg, font=FONT_SMALL)
        entry.grid(row=1, column=0, sticky="ew", padx=16, pady=4)
        status = ctk.CTkLabel(dlg, text="", font=FONT_SMALL,
                              text_color=("gray35", "gray60"), anchor="w")
        status.grid(row=2, column=0, sticky="w", padx=16)

        btn_row = ctk.CTkFrame(dlg, fg_color="transparent")
        btn_row.grid(row=3, column=0, sticky="e", padx=16, pady=14)
        ctk.CTkButton(btn_row, text="Cancel", width=90,
                      fg_color=PALETTE["muted"],
                      command=dlg.destroy).pack(side="right", padx=6)
        import_btn = ctk.CTkButton(btn_row, text="Import", width=90)
        import_btn.pack(side="right", padx=6)

        def do_import():
            code = entry.get().strip()
            if not code:
                return
            try:
                data = core.parse_share_code(code)
            except Exception:
                status.configure(text="That doesn't look like a valid code.")
                return
            import_btn.configure(state="disabled")
            status.configure(text="Creating instance...")

            def work():
                try:
                    new_id = uuid.uuid4().hex
                    inst_dir = config.CONFIG_DIR / "instances" / new_id
                    mods_dir = inst_dir / "mods"
                    mods_dir.mkdir(parents=True, exist_ok=True)
                    mods = data.get("mods") or []
                    ok, failed = 0, 0
                    for i, pid in enumerate(mods):
                        self.ui_call(status.configure, {
                            "text": f"Mod {i + 1}/{len(mods)}: {pid}"})
                        try:
                            core.modrinth_download(
                                pid, data.get("mc", ""),
                                data.get("loader", "vanilla"), str(mods_dir))
                            ok += 1
                        except Exception:
                            failed += 1
                    insts = config.load_instances()
                    insts.append({
                        "id": new_id,
                        "name": data.get("name") or "Imported instance",
                        "loader_id": data.get("loader", "vanilla"),
                        "mc_version": data.get("mc", ""),
                        "loader_version": data.get("lv", ""),
                        "ram_mb": "", "separate_game_dir": True,
                        "created": int(time.time()), "last_played": 0,
                        "playtime_secs": 0,
                    })
                    config.save_instances(insts)

                    def done():
                        self.instances = insts
                        self._render_instances()
                        msg = f"Imported '{data.get('name', 'instance')}' - " \
                              f"{ok} mod(s) installed"
                        if failed:
                            msg += f", {failed} unavailable for this version/loader"
                        status.configure(text=msg)
                        import_btn.configure(state="normal", text="Done")
                    self.ui_call(done)
                except Exception as e:
                    self.ui_call(lambda: (
                        status.configure(text=f"Import failed: {e}"),
                        import_btn.configure(state="normal")))

            threading.Thread(target=work, daemon=True).start()

        import_btn.configure(command=do_import)

    # -------------------------------------------------------- Server export

    def _export_server(self, inst):
        dest = filedialog.askdirectory(
            title=f"Pick where to build the {inst.get('name', 'instance')} server")
        if not dest:
            return
        out = Path(dest) / f"{inst.get('name', 'server')}-server"
        if out.exists() and any(out.iterdir()):
            messagebox.showwarning(
                "Folder not empty",
                f"'{out}' already has files - pick an empty spot.")
            return

        dlg = ctk.CTkToplevel(self)
        dlg.title("Exporting server")
        dlg.geometry("460x150")
        dlg.transient(self)
        status = ctk.CTkLabel(dlg, text="Starting...", font=FONT, anchor="w",
                              wraplength=420, justify="left")
        status.pack(padx=18, pady=24, fill="x")

        def work():
            try:
                n = core.export_server(
                    inst, str(self._instance_mods_dir(inst)), str(out),
                    self.settings,
                    progress=lambda m: self.ui_call(status.configure,
                                                    {"text": m}))
                loader = inst.get("loader_id", "vanilla")
                note = "" if loader == "vanilla" else \
                    f"\n\n{core.LOADER_NAMES.get(loader, loader)} servers need the " \
                    "loader's server installer - see the note inside start.bat."
                self.ui_call(lambda: (
                    status.configure(
                        text=f"Done - {n} server-compatible mod(s) copied to\n{out}"
                             f"{note}\n\nRun start.bat inside that folder."),
                    ctk.CTkButton(dlg, text="Open folder", width=110,
                                  command=lambda: os.startfile(str(out))
                                  ).pack(pady=(0, 14))))
            except Exception as e:
                self.ui_call(status.configure, {"text": f"Export failed: {e}"})

        threading.Thread(target=work, daemon=True).start()

    # ----------------------------------------------------------- Worlds UI

    def _open_worlds_dialog(self, inst):
        saves_dir = self._instance_dir(inst) / "saves"
        saves_dir.mkdir(parents=True, exist_ok=True)
        backups_dir = config.CONFIG_DIR / "backups" / inst["id"]

        dlg = ctk.CTkToplevel(self)
        dlg.title(f"Worlds - {inst.get('name', 'Instance')}")
        dlg.geometry("560x480")
        dlg.transient(self)
        dlg.grid_columnconfigure(0, weight=1)
        dlg.grid_rowconfigure(1, weight=1)

        top = ctk.CTkFrame(dlg, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=16, pady=(14, 4))
        ctk.CTkLabel(top, text="Worlds", font=FONT_BOLD).pack(side="left")
        ctk.CTkButton(top, text="Open saves folder", width=130, height=26, font=FONT_SMALL,
                      fg_color=PALETTE["muted"],
                      command=lambda: os.startfile(str(saves_dir))).pack(side="right")

        scroll = _scroll_frame(dlg)
        scroll.grid(row=1, column=0, sticky="nsew", padx=16, pady=(0, 14))

        def fmt_size(n):
            for unit in ("B", "KB", "MB", "GB"):
                if n < 1024:
                    return f"{n:.0f}{unit}"
                n /= 1024
            return f"{n:.1f}TB"

        def render():
            for child in scroll.winfo_children():
                child.destroy()

            worlds = core.list_worlds(str(saves_dir))
            ctk.CTkLabel(scroll, text=f"{len(worlds)} world(s)", font=FONT_SMALL,
                         text_color=("gray35", "gray60")).pack(anchor="w", pady=(4, 2))
            GAMEMODES = {0: "survival", 1: "creative", 2: "adventure", 3: "spectator"}
            for w in worlds:
                row = ctk.CTkFrame(scroll, fg_color=PALETTE["card"])
                row.pack(fill="x", pady=2)
                info = ctk.CTkFrame(row, fg_color="transparent")
                info.pack(side="left", padx=10, pady=6, fill="x", expand=True)
                ctk.CTkLabel(info, text=f"{w['name']}  ·  {fmt_size(w['size'])}",
                             font=FONT, anchor="w").pack(anchor="w")
                ld = core.read_level_dat(str(Path(w["path"]) / "level.dat"))
                if ld:
                    parts = []
                    if ld.get("gamemode") in GAMEMODES:
                        parts.append(GAMEMODES[ld["gamemode"]])
                    if ld.get("days"):
                        parts.append(f"day {ld['days']}")
                    ctk.CTkLabel(info, text=" · ".join(parts), font=FONT_SMALL,
                                 text_color=("gray35", "gray60"), anchor="w"
                                 ).pack(anchor="w")
                    if ld.get("seed") is not None:
                        seed_row = ctk.CTkFrame(info, fg_color="transparent")
                        seed_row.pack(anchor="w")
                        ctk.CTkLabel(seed_row, text=f"seed: {ld['seed']}",
                                     font=FONT_SMALL, text_color=("gray30", "gray55")
                                     ).pack(side="left")
                        ctk.CTkButton(seed_row, text="copy", width=40, height=18,
                                      font=FONT_SMALL, fg_color=PALETTE["muted"],
                                      command=lambda s=str(ld["seed"]):
                                      (self.clipboard_clear(),
                                       self.clipboard_append(s))
                                      ).pack(side="left", padx=6)
                ctk.CTkButton(row, text="Delete", width=56, height=24, font=FONT_SMALL,
                              fg_color=("#a33", "#a33"), hover_color=("#c44", "#c44"),
                              command=lambda p=w["path"], n=w["name"]:
                              messagebox.askyesno("Delete world",
                                                  f"Delete '{n}' permanently?", parent=dlg)
                              and (shutil.rmtree(p), render())
                              ).pack(side="right", padx=6)
                ctk.CTkButton(row, text="Backup", width=56, height=24, font=FONT_SMALL,
                              fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                              command=lambda p=w["path"], n=w["name"]:
                              (core.backup_world(p, str(backups_dir), n), render())
                              ).pack(side="right")
                snaps_root = config.CONFIG_DIR / "snapshots" / inst["id"] / w["name"]

                def do_snapshot(p=w["path"], root=snaps_root):
                    core.snapshot_world(p, str(root))
                    render()

                ctk.CTkButton(row, text="Snapshot", width=64, height=24, font=FONT_SMALL,
                              fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                              command=do_snapshot).pack(side="right", padx=4)

            backups = core.list_backups(str(backups_dir))
            ctk.CTkLabel(scroll, text=f"{len(backups)} backup(s)", font=FONT_SMALL,
                         text_color=("gray35", "gray60")).pack(anchor="w", pady=(14, 2))
            for b in backups:
                row = ctk.CTkFrame(scroll, fg_color=("gray92", "gray22"))
                row.pack(fill="x", pady=2)
                ctk.CTkLabel(row, text=b["name"], font=FONT_SMALL).pack(
                    side="left", padx=10, pady=6)
                ctk.CTkLabel(row, text=fmt_size(b["size"]), font=FONT_SMALL,
                             text_color=("gray35", "gray60")).pack(side="left")
                ctk.CTkButton(row, text="Delete", width=56, height=24, font=FONT_SMALL,
                              fg_color=("#a33", "#a33"), hover_color=("#c44", "#c44"),
                              command=lambda p=b["path"], n=b["name"]:
                              messagebox.askyesno("Delete backup",
                                                  f"Delete '{n}'?", parent=dlg)
                              and (os.remove(p), render())
                              ).pack(side="right", padx=6)

                def do_restore(p=b["path"], n=b["name"]):
                    try:
                        name = core.restore_world(p, str(saves_dir))
                        messagebox.showinfo("Restored", f"World '{name}' restored.",
                                            parent=dlg)
                        render()
                    except FileExistsError as e:
                        messagebox.showwarning("Restore", str(e), parent=dlg)

                ctk.CTkButton(row, text="Restore", width=56, height=24, font=FONT_SMALL,
                              fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                              command=do_restore).pack(side="right")

            # incremental snapshots (time-machine), grouped per world
            snaps_base = config.CONFIG_DIR / "snapshots" / inst["id"]
            any_snaps = False
            for w in worlds:
                snaps = core.list_snapshots(str(snaps_base / w["name"]))
                if not snaps:
                    continue
                if not any_snaps:
                    ctk.CTkLabel(scroll, text="Snapshots (time-machine)",
                                 font=FONT_SMALL, text_color=("gray35", "gray60")
                                 ).pack(anchor="w", pady=(14, 2))
                    any_snaps = True
                for s in snaps:
                    srow = ctk.CTkFrame(scroll, fg_color=("gray94", "gray20"))
                    srow.pack(fill="x", pady=1)
                    ctk.CTkLabel(srow, text=f"{w['name']} — {s['name']}",
                                 font=FONT_SMALL).pack(side="left", padx=10, pady=5)

                    def do_snap_restore(sd=s["path"], wp=w["path"], wn=w["name"]):
                        if messagebox.askyesno(
                                "Restore snapshot",
                                f"This overwrites the current '{wn}' world "
                                "with the snapshot. Continue?", parent=dlg):
                            core.restore_snapshot(sd, wp)
                            messagebox.showinfo("Restored",
                                                f"'{wn}' rolled back.", parent=dlg)
                            render()

                    def do_snap_delete(sd=s["path"], n=s["name"]):
                        if messagebox.askyesno("Delete snapshot",
                                               f"Delete snapshot {n}?", parent=dlg):
                            shutil.rmtree(sd, ignore_errors=True)
                            render()

                    ctk.CTkButton(srow, text="Delete", width=56, height=22,
                                  font=FONT_SMALL, fg_color=("#a33", "#a33"),
                                  hover_color=("#c44", "#c44"),
                                  command=do_snap_delete).pack(side="right", padx=6)
                    ctk.CTkButton(srow, text="Restore", width=56, height=22,
                                  font=FONT_SMALL, fg_color=PALETTE["muted"],
                                  hover_color=PALETTE["muted_hover"],
                                  command=do_snap_restore).pack(side="right")

        render()

    # ------------------------------------------------------ Screenshots UI

    def _open_screenshots(self, inst):
        from PIL import Image

        shots_dir = self._instance_dir(inst) / "screenshots"
        shots_dir.mkdir(parents=True, exist_ok=True)

        dlg = ctk.CTkToplevel(self)
        dlg.title(f"Screenshots - {inst.get('name', 'Instance')}")
        dlg.geometry("680x520")
        dlg.transient(self)
        dlg.grid_columnconfigure(0, weight=1)
        dlg.grid_rowconfigure(1, weight=1)

        top = ctk.CTkFrame(dlg, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=16, pady=(14, 4))
        ctk.CTkLabel(top, text="Screenshots", font=FONT_BOLD).pack(side="left")
        ctk.CTkButton(top, text="Open folder", width=110, height=26, font=FONT_SMALL,
                      fg_color=PALETTE["muted"],
                      command=lambda: os.startfile(str(shots_dir))).pack(side="right")

        scroll = _scroll_frame(dlg)
        scroll.grid(row=1, column=0, sticky="nsew", padx=16, pady=(0, 14))
        scroll._images = []  # keep refs so thumbs aren't garbage-collected

        def render():
            for child in scroll.winfo_children():
                child.destroy()
            scroll._images.clear()
            shots = sorted(
                [f for f in shots_dir.iterdir()
                 if f.suffix.lower() in (".png", ".jpg", ".jpeg")],
                key=lambda f: f.stat().st_mtime, reverse=True)
            if not shots:
                ctk.CTkLabel(scroll, text="No screenshots yet - take some with F2",
                             font=FONT, text_color=("gray35", "gray60")).pack(pady=20)
                return
            for i, path in enumerate(shots):
                cell = ctk.CTkFrame(scroll, fg_color=PALETTE["card"])
                cell.grid(row=i // 3, column=i % 3, padx=6, pady=6)
                try:
                    img = Image.open(path)
                    w, h = img.size
                    scale = min(190 / w, 107 / h)
                    tkimg = ctk.CTkImage(
                        light_image=img, dark_image=img,
                        size=(max(1, int(w * scale)), max(1, int(h * scale))))
                    scroll._images.append(tkimg)
                    ctk.CTkButton(cell, image=tkimg, text="", width=190,
                                  fg_color="transparent",
                                  hover_color=("gray85", "gray35"),
                                  command=lambda p=path: os.startfile(str(p))
                                  ).pack(padx=4, pady=(4, 0))
                except Exception:
                    ctk.CTkLabel(cell, text="(unreadable)", font=FONT_SMALL,
                                 width=190).pack(padx=4, pady=(4, 0))
                ctk.CTkLabel(cell, text=path.name, font=FONT_SMALL,
                             text_color=("gray35", "gray60")).pack(pady=2)
                ctk.CTkButton(
                    cell, text="Delete", width=70, height=20, font=FONT_SMALL,
                    fg_color=("#a33", "#a33"), hover_color=("#c44", "#c44"),
                    command=lambda p=path:
                    messagebox.askyesno("Delete screenshot", f"Delete '{p.name}'?",
                                        parent=dlg)
                    and (p.unlink(), render())).pack(pady=(0, 6))

        render()

    # ------------------------------------------------------------ Mods UI

    def _open_mod_browser(self, inst, search_preset=""):
        mods_dir = self._instance_mods_dir(inst)

        dlg = ctk.CTkToplevel(self)
        dlg.title(f"Mods - {inst.get('name', 'Instance')}")
        dlg.geometry("700x560")
        dlg.transient(self)
        dlg.grid_columnconfigure(0, weight=1)
        dlg.grid_rowconfigure(2, weight=1)

        header = f"{inst['mc_version']} · {core.LOADER_NAMES.get(inst.get('loader_id'), inst.get('loader_id'))}"
        top = ctk.CTkFrame(dlg, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=16, pady=(14, 4))
        ctk.CTkLabel(top, text=header, font=FONT_BOLD).pack(side="left")
        ctk.CTkButton(top, text="Open mods folder", width=130, height=26, font=FONT_SMALL,
                      fg_color=PALETTE["muted"],
                      command=lambda: os.startfile(str(mods_dir))).pack(side="right")

        seg = ctk.CTkSegmentedButton(
            dlg, font=FONT,
            values=["Installed", "Mods", "Packs", "Shaders", "Modpacks", "Saved"])
        seg.set("Installed")
        seg.grid(row=1, column=0, sticky="w", padx=16, pady=6)

        content = ctk.CTkFrame(dlg, fg_color="transparent")
        content.grid(row=2, column=0, sticky="nsew", padx=16, pady=(0, 14))
        content.grid_columnconfigure(0, weight=1)
        content.grid_rowconfigure(0, weight=1)

        # ---- Installed tab
        installed_frame = ctk.CTkFrame(content)
        installed_frame.grid(row=0, column=0, sticky="nsew")
        installed_frame.grid_columnconfigure(0, weight=1)
        installed_frame.grid_rowconfigure(1, weight=1)

        inst_header = ctk.CTkFrame(installed_frame, fg_color="transparent")
        inst_header.grid(row=0, column=0, sticky="ew", padx=8, pady=(8, 0))
        inst_status = ctk.CTkLabel(inst_header, text="", font=FONT_SMALL,
                                   text_color=("gray35", "gray60"))
        inst_status.pack(side="left")
        rollback_btn = ctk.CTkButton(inst_header, text="Roll back", width=90, height=26,
                                     font=FONT_SMALL, fg_color=PALETTE["muted"],
                                     hover_color=PALETTE["muted_hover"])
        update_btn = ctk.CTkButton(inst_header, text="Update all", width=90, height=26,
                                   font=FONT_SMALL, fg_color=PALETTE["muted"],
                                   hover_color=PALETTE["muted_hover"])
        update_btn.pack(side="right")

        def refresh_rollback_state():
            has_snap = os.path.isfile(core._mod_map_path(str(mods_dir)) + ".rollback")
            if has_snap:
                rollback_btn.pack(side="right", padx=(0, 6))
            else:
                rollback_btn.pack_forget()

        refresh_rollback_state()
        installed_list = _scroll_frame(installed_frame)
        installed_list.grid(row=1, column=0, sticky="nsew", padx=8, pady=8)

        def update_all():
            update_btn.configure(state="disabled", text="...")
            inst_status.configure(text="Checking for updates...")
            core.snapshot_mod_map(str(mods_dir))

            def work():
                try:
                    res = core.update_all_mods(
                        str(mods_dir), inst["mc_version"], inst.get("loader_id", "vanilla"),
                        progress=lambda p: self.ui_call(
                            inst_status.configure, {"text": f"Checking {p}..."}))
                    msg = f"{len(res['updated'])} updated, {len(res['current'])} current"
                    if res["failed"]:
                        msg += f", {len(res['failed'])} failed"
                    self.ui_call(lambda: (
                        inst_status.configure(text=msg),
                        update_btn.configure(state="normal", text="Update all"),
                        refresh_rollback_state(),
                        render_installed()))
                except Exception as e:
                    self.ui_call(lambda: (
                        inst_status.configure(text=f"Update failed: {e}"),
                        update_btn.configure(state="normal", text="Update all")))

            threading.Thread(target=work, daemon=True).start()

        update_btn.configure(command=update_all)

        def rollback():
            if not messagebox.askyesno(
                    "Roll back mods",
                    "Restore the mod versions you had before the last Update all?",
                    parent=dlg):
                return
            rollback_btn.configure(state="disabled", text="...")
            inst_status.configure(text="Restoring previous versions...")

            def work():
                try:
                    n = core.rollback_mods(
                        str(mods_dir), inst["mc_version"],
                        inst.get("loader_id", "vanilla"),
                        progress=lambda p: self.ui_call(
                            inst_status.configure, {"text": f"Restoring {p}..."}))
                    self.ui_call(lambda: (
                        inst_status.configure(text=f"Restored {n} mod(s)"),
                        rollback_btn.configure(state="normal", text="Roll back"),
                        render_installed()))
                except Exception as e:
                    self.ui_call(lambda: (
                        inst_status.configure(text=f"Rollback failed: {e}"),
                        rollback_btn.configure(state="normal", text="Roll back")))

            threading.Thread(target=work, daemon=True).start()

        rollback_btn.configure(command=rollback)

        def render_installed():
            for child in installed_list.winfo_children():
                child.destroy()
            mods = core.list_mods(str(mods_dir))
            if not mods:
                ctk.CTkLabel(installed_list, text="No mods yet - install some from Browse Modrinth",
                             font=FONT, text_color=("gray35", "gray60")).pack(pady=16)
                return
            for mod in mods:
                row = ctk.CTkFrame(installed_list, fg_color=PALETTE["card"])
                row.pack(fill="x", pady=2)
                state_color = ("gray15", "gray85") if mod["enabled"] else ("gray30", "gray55")
                ctk.CTkLabel(row, text=mod["name"], font=FONT,
                             text_color=state_color).pack(side="left", padx=10, pady=6)
                ctk.CTkButton(row, text="Delete", width=64, height=26, font=FONT_SMALL,
                              fg_color=("#a33", "#a33"), hover_color=("#c44", "#c44"),
                              command=lambda p=mod["path"], n=mod["name"]:
                              messagebox.askyesno("Delete mod", f"Delete '{n}'?", parent=dlg)
                              and (core.delete_mod(p), render_installed())
                              ).pack(side="right", padx=6)
                toggle = "Disable" if mod["enabled"] else "Enable"
                ctk.CTkButton(row, text=toggle, width=64, height=26, font=FONT_SMALL,
                              fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                              command=lambda p=mod["path"], e=mod["enabled"]:
                              (core.set_mod_enabled(p, e), render_installed())
                              ).pack(side="right")

        # ---- Generic search tab (mods + modpacks share this)
        SORT_INDEXES = ["Downloads", "Relevance", "Follows", "Newest", "Updated"]

        def make_search_tab(with_sort=True):
            frame = ctk.CTkFrame(content)
            frame.grid(row=0, column=0, sticky="nsew")
            frame.grid_columnconfigure(0, weight=1)

            search_row = ctk.CTkFrame(frame, fg_color="transparent")
            search_row.grid(row=0, column=0, sticky="ew", padx=8, pady=(8, 4))
            search_row.grid_columnconfigure(0, weight=1)
            entry = ctk.CTkEntry(search_row, font=FONT)
            entry.grid(row=0, column=0, sticky="ew", padx=(0, 6))
            go_btn = ctk.CTkButton(search_row, text="Search", width=90, font=FONT)
            go_btn.grid(row=0, column=1)

            state = {"offset": 0, "query": "", "sort": "downloads",
                     "desc": True, "on_change": None}
            res_row = 1
            if with_sort:
                sort_row = ctk.CTkFrame(frame, fg_color="transparent")
                sort_row.grid(row=1, column=0, sticky="w", padx=8, pady=(0, 4))
                res_row = 2
                ctk.CTkLabel(sort_row, text="Sort:", font=FONT_SMALL,
                             text_color=("gray35", "gray60")).pack(side="left", padx=(0, 4))
                sort_menu = ctk.CTkOptionMenu(
                    sort_row, values=SORT_INDEXES, font=FONT_SMALL, width=130,
                    command=lambda v: (state.update(sort=v.lower()),
                                       state["on_change"] and state["on_change"]()))
                sort_menu.set("Downloads")
                sort_menu.pack(side="left")
                dir_btn = ctk.CTkButton(sort_row, text="↓", width=36, font=FONT,
                                        fg_color=PALETTE["muted"],
                                        hover_color=PALETTE["muted_hover"])

                def flip_dir():
                    state["desc"] = not state["desc"]
                    dir_btn.configure(text="↓" if state["desc"] else "↑")
                    if state["on_change"]:
                        state["on_change"]()

                dir_btn.configure(command=flip_dir)
                dir_btn.pack(side="left", padx=6)

            results = _scroll_frame(frame)
            results.grid(row=res_row, column=0, sticky="nsew", padx=8, pady=(0, 8))
            frame.grid_rowconfigure(res_row, weight=1)
            status = ctk.CTkLabel(frame, text="", font=FONT_SMALL,
                                  text_color=("gray35", "gray60"))
            status.grid(row=res_row + 1, column=0, sticky="w", padx=10, pady=(0, 6))

            return {"frame": frame, "entry": entry, "go": go_btn,
                    "results": results, "status": status, "state": state}

        def render_results(tab, hits, install_fn, append=False, load_more=None,
                           save_type=None):
            for child in tab["results"].winfo_children():
                if isinstance(child, ctk.CTkButton) and child.cget("text") == "Load more":
                    child.destroy()
                elif not append:
                    child.destroy()
            if not hits and not append:
                ctk.CTkLabel(tab["results"], text="No results", font=FONT,
                             text_color=("gray35", "gray60")).pack(pady=16)
                return
            if not tab["state"]["desc"]:
                hits = hits[::-1]
            for hit in hits:
                row = ctk.CTkFrame(tab["results"], fg_color=PALETTE["card"])
                row.pack(fill="x", pady=3)
                text_col = ctk.CTkFrame(row, fg_color="transparent")
                text_col.pack(side="left", fill="x", expand=True, padx=10, pady=6)
                ctk.CTkLabel(text_col, text=hit.get("title", "?"), font=FONT_BOLD,
                             anchor="w").pack(anchor="w")
                desc = (hit.get("description") or "")[:110]
                ctk.CTkLabel(text_col, text=desc, font=FONT_SMALL,
                             text_color=("gray35", "gray60"), anchor="w",
                             wraplength=400, justify="left").pack(anchor="w")
                dl = hit.get("downloads", 0)
                ctk.CTkLabel(text_col, text=f"{dl:,} downloads", font=FONT_SMALL,
                             text_color=("gray30", "gray55"), anchor="w").pack(anchor="w")
                btn = ctk.CTkButton(row, text="Install", width=80, height=28, font=FONT_SMALL)
                btn.configure(command=lambda h=hit, b=btn: install_fn(h, b))
                btn.pack(side="right", padx=10)
                if save_type:
                    save_btn = ctk.CTkButton(
                        row, text="Saved" if is_saved(hit["project_id"]) else "Save",
                        width=56, height=28, font=FONT_SMALL,
                        fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"])
                    save_btn.configure(
                        command=lambda h=hit, b=save_btn, t=save_type: toggle_save(h, b, t))
                    save_btn.pack(side="right")
            if len(hits) >= 15:
                ctk.CTkButton(tab["results"], text="Load more", width=110, font=FONT_SMALL,
                              fg_color=PALETTE["muted"],
                              command=load_more).pack(pady=8)

        mods_tab = make_search_tab(with_sort=True)
        packs_tab = make_search_tab(with_sort=True)
        res_tab = make_search_tab(with_sort=True)
        shader_tab = make_search_tab(with_sort=True)

        res_dir = self._instance_dir(inst) / "resourcepacks"
        res_dir.mkdir(parents=True, exist_ok=True)
        shader_dir = self._instance_dir(inst) / "shaderpacks"
        shader_dir.mkdir(parents=True, exist_ok=True)

        def make_file_installer(tab, dest_dir):
            def install(hit, btn):
                btn.configure(state="disabled", text="...")
                tab["status"].configure(text=f"Downloading {hit['title']}...")

                def work():
                    try:
                        existing = core.installed_project_file(str(dest_dir),
                                                               hit["project_id"])
                        if existing:
                            self.ui_call(lambda: (
                                tab["status"].configure(
                                    text=f"{hit['title']} is already installed"),
                                btn.configure(text="Installed")))
                            return
                        dest = core.modrinth_download(hit["project_id"],
                                                      inst["mc_version"], "",
                                                      str(dest_dir))
                        self.ui_call(lambda: (
                            tab["status"].configure(
                                text=f"Installed {os.path.basename(dest)}"),
                            btn.configure(text="Installed")))
                    except Exception as e:
                        self.ui_call(lambda: (
                            tab["status"].configure(text=f"Failed: {e}"),
                            btn.configure(state="normal", text="Install")))

                threading.Thread(target=work, daemon=True).start()
            return install

        res_install = make_file_installer(res_tab, res_dir)
        shader_install = make_file_installer(shader_tab, shader_dir)

        def install_mod(hit, btn):
            btn.configure(state="disabled", text="...")
            mods_tab["status"].configure(text=f"Downloading {hit['title']}...")
            status = mods_tab["status"]

            def work():
                try:
                    existing = core.installed_project_file(str(mods_dir), hit["project_id"])
                    if existing:
                        self.ui_call(lambda: (
                            status.configure(text=f"{hit['title']} is already installed"),
                            btn.configure(text="Installed")))
                        return
                    dest = core.modrinth_download_with_deps(
                        hit["project_id"], inst["mc_version"],
                        inst.get("loader_id", "vanilla"), str(mods_dir),
                        progress=lambda m: self.ui_call(
                            status.configure, {"text": f"Downloading {m}..."}))
                    name = os.path.basename(dest)
                    res = core.check_mod_conflicts(dest, str(mods_dir))
                    if res["blockers"]:
                        core.delete_mod(dest)
                        msg = "\n".join(res["blockers"][:4])
                        self.ui_call(lambda: (
                            status.configure(text=f"Blocked: {hit['title']}"),
                            btn.configure(state="normal", text="Install"),
                            messagebox.showwarning(
                                "Incompatible mod",
                                f"Install of {hit['title']} was blocked:\n\n{msg}",
                                parent=dlg)))
                    elif res["warnings"]:
                        warn = "\n".join(res["warnings"][:4])

                        def ask():
                            ok = messagebox.askyesno(
                                "Possible conflict",
                                f"{hit['title']} may conflict with installed mods:\n\n"
                                f"{warn}\n\nInstall anyway?", parent=dlg)
                            if ok:
                                status.configure(text=f"Installed {name}")
                                btn.configure(text="Installed")
                                render_installed()
                            else:
                                core.delete_mod(dest)
                                status.configure(text=f"Skipped {hit['title']}")
                                btn.configure(state="normal", text="Install")

                        self.ui_call(ask)
                    else:
                        self.ui_call(lambda: (status.configure(text=f"Installed {name}"),
                                              btn.configure(text="Installed"),
                                              render_installed()))
                except Exception as e:
                    self.ui_call(lambda: (status.configure(text=f"Failed: {e}"),
                                          btn.configure(state="normal", text="Install")))

            threading.Thread(target=work, daemon=True).start()

        def install_pack(hit, btn):
            btn.configure(state="disabled", text="...")
            status = packs_tab["status"]
            status.configure(text=f"Fetching {hit['title']}...")

            def work():
                try:
                    url = core.modrinth_latest_file_url(hit["project_id"])
                    new_id = uuid.uuid4().hex
                    dest = config.CONFIG_DIR / "instances" / new_id
                    deps = core.modrinth_install_modpack(
                        url, str(dest),
                        progress=lambda m: self.ui_call(
                            status.configure, {"text": f"Files: {m}"}))
                    mc = deps.get("minecraft", inst["mc_version"])
                    lid, lv = "vanilla", ""
                    for key, mapped in core.MODPACK_LOADER_KEYS.items():
                        if deps.get(key):
                            lid, lv = mapped, deps[key]
                            break
                    entry = {
                        "id": new_id, "name": hit["title"], "loader_id": lid,
                        "mc_version": mc, "loader_version": lv, "ram_mb": "",
                        "separate_game_dir": True,
                        "created": int(time.time()), "last_played": 0,
                    }
                    insts = config.load_instances()
                    insts.append(entry)
                    config.save_instances(insts)
                    self.instances = insts

                    def done():
                        self._render_instances()
                        status.configure(text=f"Created instance '{hit['title']}'")
                        messagebox.showinfo(
                            "Modpack installed",
                            f"Created a new instance '{hit['title']}' "
                            f"({mc} · {core.LOADER_NAMES.get(lid, lid)}).",
                            parent=dlg)
                        dlg.destroy()

                    self.ui_call(done)
                except Exception as e:
                    self.ui_call(lambda: (status.configure(text=f"Failed: {e}"),
                                          btn.configure(state="normal", text="Install")))

            threading.Thread(target=work, daemon=True).start()

        # ---- Saved tab
        saved_frame = ctk.CTkFrame(content)
        saved_frame.grid(row=0, column=0, sticky="nsew")
        saved_frame.grid_columnconfigure(0, weight=1)
        saved_frame.grid_rowconfigure(0, weight=1)
        saved_list = _scroll_frame(saved_frame)
        saved_list.grid(row=0, column=0, sticky="nsew", padx=8, pady=8)

        def is_saved(project_id):
            return any(m.get("project_id") == project_id
                       for m in config.load_saved_mods())

        def render_saved():
            for child in saved_list.winfo_children():
                child.destroy()
            mods = config.load_saved_mods()
            if not mods:
                ctk.CTkLabel(
                    saved_list,
                    text="Nothing saved - hit Save on a mod in Browse Modrinth",
                    font=FONT, text_color=("gray35", "gray60")).pack(pady=16)
                return
            for m in mods:
                row = ctk.CTkFrame(saved_list, fg_color=PALETTE["card"])
                row.pack(fill="x", pady=2)
                info = ctk.CTkFrame(row, fg_color="transparent")
                info.pack(side="left", fill="x", expand=True, padx=10, pady=6)
                tag = "modpack" if m.get("type") == "modpack" else "mod"
                ctk.CTkLabel(info, text=f"{m.get('title', '?')} · {tag}", font=FONT,
                             anchor="w").pack(anchor="w")
                ctk.CTkLabel(info, text=f"{m.get('downloads', 0):,} downloads",
                             font=FONT_SMALL, text_color=("gray30", "gray55"),
                             anchor="w").pack(anchor="w")
                pid = m.get("project_id")
                ctk.CTkButton(row, text="Remove", width=64, height=26, font=FONT_SMALL,
                              fg_color=("#a33", "#a33"), hover_color=("#c44", "#c44"),
                              command=lambda p=pid: (
                                  config.save_saved_mods(
                                      [x for x in config.load_saved_mods()
                                       if x.get("project_id") != p]),
                                  render_saved())).pack(side="right", padx=6)
                inst_btn = ctk.CTkButton(row, text="Install", width=64, height=26,
                                         font=FONT_SMALL)
                inst_fn = install_pack if m.get("type") == "modpack" else install_mod
                inst_btn.configure(command=lambda h=m, b=inst_btn, f=inst_fn: f(h, b))
                inst_btn.pack(side="right")

        def toggle_save(hit, btn, ptype="mod"):
            mods = config.load_saved_mods()
            if is_saved(hit["project_id"]):
                mods = [m for m in mods if m.get("project_id") != hit["project_id"]]
                btn.configure(text="Save")
            else:
                mods.append({"project_id": hit["project_id"],
                             "type": ptype,
                             "title": hit.get("title", ""),
                             "description": hit.get("description", ""),
                             "downloads": hit.get("downloads", 0)})
                btn.configure(text="Saved")
            config.save_saved_mods(mods)
            render_saved()

        def do_search(tab, project_type, install_fn, append=False, loader_id=None):
            st = tab["state"]
            if not append:
                st["offset"] = 0
                st["query"] = tab["entry"].get().strip()
                for child in tab["results"].winfo_children():
                    child.destroy()
            else:
                st["offset"] += 15
            tab["status"].configure(text="Searching...")
            lid = inst.get("loader_id", "vanilla") if loader_id is None else loader_id

            def work():
                try:
                    hits = core.modrinth_search(
                        st["query"], inst["mc_version"], lid,
                        offset=st["offset"], index=st["sort"], project_type=project_type)
                    note = ""
                    if not hits and not append:
                        hits = core.modrinth_search(st["query"], "", "",
                                                    index=st["sort"],
                                                    project_type=project_type)
                        note = " - no version-specific matches, showing all"
                    self.ui_call(lambda: (
                        render_results(tab, hits, install_fn, append,
                                       lambda: do_search(tab, project_type,
                                                         install_fn, append=True),
                                       save_type=project_type),
                        tab["status"].configure(text=f"{len(hits)} result(s){note}")))
                except Exception as e:
                    self.ui_call(lambda: tab["status"].configure(
                        text=f"Search failed: {e}"))

            threading.Thread(target=work, daemon=True).start()

        wiring = (
            (mods_tab, "mod", install_mod, None),
            (packs_tab, "modpack", install_pack, None),
            (res_tab, "resourcepack", res_install, ""),
            (shader_tab, "shader", shader_install, ""),
        )
        for tab, ptype, fn, lid in wiring:
            tab["go"].configure(
                command=lambda t=tab, p=ptype, f=fn, l=lid: do_search(t, p, f,
                                                                      loader_id=l))
            tab["entry"].bind(
                "<Return>", lambda e, t=tab, p=ptype, f=fn, l=lid: do_search(
                    t, p, f, loader_id=l))
            tab["state"]["on_change"] = \
                lambda t=tab, p=ptype, f=fn, l=lid: do_search(t, p, f, loader_id=l)

        tabs = {"Installed": installed_frame,
                "Mods": mods_tab["frame"],
                "Packs": res_tab["frame"],
                "Shaders": shader_tab["frame"],
                "Modpacks": packs_tab["frame"],
                "Saved": saved_frame}
        auto_loaded = set()

        def on_tab(name):
            for f in tabs.values():
                f.grid_remove()
            tabs[name].grid()
            if name == "Mods" and name not in auto_loaded:
                auto_loaded.add(name)
                do_search(mods_tab, "mod", install_mod)
            elif name == "Packs" and name not in auto_loaded:
                auto_loaded.add(name)
                do_search(res_tab, "resourcepack", res_install, loader_id="")
            elif name == "Shaders" and name not in auto_loaded:
                auto_loaded.add(name)
                do_search(shader_tab, "shader", shader_install, loader_id="")
            elif name == "Modpacks" and name not in auto_loaded:
                auto_loaded.add(name)
                do_search(packs_tab, "modpack", install_pack)
            elif name == "Saved":
                render_saved()

        seg.configure(command=on_tab)
        for key in ("Mods", "Packs", "Shaders", "Modpacks", "Saved"):
            tabs[key].grid_remove()
        render_installed()
        if inst.get("loader_id") == "vanilla":
            mods_tab["status"].configure(
                text="Note: vanilla can't run mods - install a loader instance")
        if search_preset:
            mods_tab["entry"].insert(0, search_preset)
            seg.set("Mods")
            on_tab("Mods")

    def _instance_play(self, inst):
        account = self._selected_account()
        if not account:
            messagebox.showinfo("No account", "Add a Microsoft account first (Accounts tab).")
            self._show_page("accounts")
            return
        if inst["id"] in self._running:
            self._set_status(f"'{inst.get('name', 'Instance')}' is already running")
            return
        if self._launching:
            return
        try:
            version_id = core.installed_version_id(
                inst["loader_id"], inst["mc_version"], inst.get("loader_version") or "")
        except Exception as e:
            messagebox.showerror("Error", f"Could not resolve version: {e}")
            return

        self._launching = True
        self.play_button.configure(state="disabled", text="Working...")
        self._set_status(f"Preparing {inst.get('name', 'instance')}...")
        threading.Thread(
            target=self._launch_worker,
            args=(account, inst["loader_id"], inst["mc_version"],
                  inst.get("loader_version") or None, version_id,
                  inst.get("ram_mb", ""), inst["id"],
                  inst.get("separate_game_dir", True), inst),
            daemon=True,
        ).start()

    # -------------------------------------------------------- Instance editor

    def _open_instance_editor(self, inst=None, prefill=None):
        data = {"name": "", "loader_id": "vanilla", "mc_version": "",
                "loader_version": "", "ram_mb": ""}
        if inst:
            data.update(inst)
        if prefill:
            data.update(prefill)

        dlg = ctk.CTkToplevel(self)
        dlg.title("Edit Instance" if inst else "New Instance")
        dlg.geometry("520x750")
        dlg.transient(self)
        dlg.grab_set()
        dlg.grid_columnconfigure(1, weight=1)

        ctk.CTkLabel(dlg, text="Name", font=FONT).grid(row=0, column=0, sticky="w", padx=20, pady=(18, 6))
        name_var = tk.StringVar(value=data["name"])
        ctk.CTkEntry(dlg, textvariable=name_var, font=FONT).grid(row=0, column=1, sticky="ew", padx=(0, 20), pady=(18, 6))

        ctk.CTkLabel(dlg, text="Loader", font=FONT).grid(row=1, column=0, sticky="w", padx=20, pady=6)
        loader_menu = ctk.CTkOptionMenu(
            dlg, values=[core.LOADER_NAMES[k] for k in ("vanilla", "fabric", "forge", "neoforge", "quilt")],
            font=FONT)
        loader_menu.set(core.LOADER_NAMES.get(data["loader_id"], "Vanilla"))
        loader_menu.grid(row=1, column=1, sticky="w", padx=(0, 20), pady=6)

        ctk.CTkLabel(dlg, text="Minecraft version", font=FONT).grid(row=2, column=0, sticky="nw", padx=20, pady=6)
        mc_picker = ctk.CTkFrame(dlg)
        mc_picker.grid(row=2, column=1, sticky="ew", padx=(0, 20), pady=6)
        mc_picker.grid_columnconfigure(0, weight=1)

        mc_filter_entry = ctk.CTkEntry(mc_picker, placeholder_text="Type to filter versions...", font=FONT)
        mc_filter_entry.grid(row=0, column=0, sticky="ew", padx=6, pady=(6, 2))
        mc_selected_var = tk.StringVar(value=data["mc_version"])
        mc_sel_label = ctk.CTkLabel(mc_picker, text="", font=FONT_SMALL,
                                  text_color=("#4a9eff", "#4a9eff"), anchor="w")
        mc_sel_label.grid(row=1, column=0, sticky="w", padx=8)
        mc_version_list = _scroll_frame(mc_picker, height=130)
        mc_version_list.grid(row=2, column=0, sticky="ew", padx=6, pady=(0, 6))

        ctk.CTkLabel(dlg, text="Loader version", font=FONT).grid(row=3, column=0, sticky="w", padx=20, pady=6)
        lv_menu = ctk.CTkOptionMenu(dlg, values=["-"], font=FONT, dynamic_resizing=False)
        lv_menu.grid(row=3, column=1, sticky="ew", padx=(0, 20), pady=6)

        ctk.CTkLabel(dlg, text="RAM override (MB)", font=FONT).grid(row=4, column=0, sticky="w", padx=20, pady=6)
        ram_var = tk.StringVar(value=str(data.get("ram_mb", "") or ""))
        ctk.CTkEntry(dlg, textvariable=ram_var, font=FONT,
                     placeholder_text=f"default {self.settings.get('default_ram_mb', '4096')}").grid(
            row=4, column=1, sticky="w", padx=(0, 20), pady=6)

        ctk.CTkLabel(dlg, text="Own game directory", font=FONT).grid(row=5, column=0, sticky="w", padx=20, pady=6)
        sep_var = tk.BooleanVar(value=bool(data.get("separate_game_dir", True)))
        sep_row = ctk.CTkFrame(dlg, fg_color="transparent")
        sep_row.grid(row=5, column=1, sticky="w", padx=(0, 20), pady=6)
        ctk.CTkCheckBox(sep_row, text="Isolated", variable=sep_var, font=FONT).pack(side="left")
        ctk.CTkLabel(sep_row, text="own mods/saves - no conflicts with other versions",
                     font=FONT_SMALL, text_color=("gray35", "gray60")).pack(side="left", padx=8)

        ctk.CTkLabel(dlg, text="Shared assets", font=FONT).grid(row=6, column=0, sticky="w", padx=20, pady=6)
        shared_var = tk.BooleanVar(value=bool(data.get("shared_assets")))
        shared_row = ctk.CTkFrame(dlg, fg_color="transparent")
        shared_row.grid(row=6, column=1, sticky="w", padx=(0, 20), pady=6)
        ctk.CTkCheckBox(shared_row, text="Share packs & screenshots",
                        variable=shared_var, font=FONT).pack(side="left")
        ctk.CTkLabel(shared_row, text="resourcepacks/shaderpacks/screenshots/schematics "
                                      "shared across all your instances",
                     font=FONT_SMALL, text_color=("gray35", "gray60")).pack(side="left", padx=8)

        ctk.CTkLabel(dlg, text="Auto-join server", font=FONT).grid(row=7, column=0, sticky="w", padx=20, pady=6)
        server_var = tk.StringVar(value=data.get("server", "") or "")
        ctk.CTkEntry(dlg, textvariable=server_var, font=FONT,
                     placeholder_text="e.g. hypixel.net (blank = global setting)"
                     ).grid(row=7, column=1, sticky="ew", padx=(0, 20), pady=6)

        ctk.CTkLabel(dlg, text="Server port", font=FONT).grid(row=8, column=0, sticky="w", padx=20, pady=6)
        port_var = tk.StringVar(value=data.get("server_port", "") or "")
        ctk.CTkEntry(dlg, textvariable=port_var, font=FONT,
                     placeholder_text="25565").grid(row=8, column=1, sticky="w",
                                                    padx=(0, 20), pady=6)

        ctk.CTkLabel(dlg, text="Built-in mod", font=FONT).grid(row=9, column=0, sticky="w", padx=20, pady=6)
        cmod_var = tk.BooleanVar(value=bool(data.get("client_mod", True)))
        cmod_row = ctk.CTkFrame(dlg, fg_color="transparent")
        cmod_row.grid(row=9, column=1, sticky="w", padx=(0, 20), pady=6)
        cmod_cb = ctk.CTkCheckBox(cmod_row, text="HUD & QoL mod",
                                variable=cmod_var, font=FONT)
        cmod_cb.pack(side="left")
        if users.is_licensed():
            cmod_var.set(True)
            cmod_cb.configure(state="disabled")
        ctk.CTkLabel(cmod_row, text="FPS/ping/coords/zoom - vanilla runs through Fabric",
                     font=FONT_SMALL, text_color=("gray35", "gray60")).pack(side="left", padx=8)

        def selected_loader_id():
            label = loader_menu.get()
            return next((k for k, v in core.LOADER_NAMES.items() if v == label), "vanilla")

        def auto_name():
            lid = selected_loader_id()
            mc = mc_selected_var.get()
            if not mc:
                return ""
            return mc if lid == "vanilla" else f"{mc} {core.LOADER_NAMES[lid]}"

        last_auto_name = auto_name()

        def maybe_update_name():
            nonlocal last_auto_name
            new_auto = auto_name()
            current = name_var.get()
            if new_auto and (not current.strip() or current == last_auto_name):
                name_var.set(new_auto)
            last_auto_name = new_auto

        dlg_mc_versions = []
        mc_buttons = {}

        def select_mc_version(vid):
            mc_selected_var.set(vid)
            mc_sel_label.configure(text=f"Selected: {vid}")
            for v, b in mc_buttons.items():
                b.configure(
                    fg_color=("#2f6fed", "#1f5fd0") if v == vid else "transparent",
                    text_color="white" if v == vid else ("gray15", "gray85"),
                )
            refresh_loader_versions()
            maybe_update_name()

        def render_mc_list():
            for child in mc_version_list.winfo_children():
                child.destroy()
            mc_buttons.clear()
            text = mc_filter_entry.get().strip().lower()
            for vid in dlg_mc_versions:
                if text and text not in vid.lower():
                    continue
                btn = ctk.CTkButton(
                    mc_version_list, text=vid, anchor="w",
                    fg_color="transparent", text_color=("gray15", "gray85"),
                    hover_color=PALETTE["nav"], font=FONT, height=26,
                    command=lambda vid=vid: select_mc_version(vid),
                )
                btn.pack(fill="x", pady=1)
                mc_buttons[vid] = btn
            sel = mc_selected_var.get()
            if sel in mc_buttons:
                select_mc_version(sel)

        def refresh_mc_versions():
            lid = selected_loader_id()
            mc_sel_label.configure(text="Loading versions...")
            for child in mc_version_list.winfo_children():
                child.destroy()

            def work():
                versions = core.loader_mc_versions(lid, stable_only=True)
                self.ui_call(set_mc_values, lid, versions)

            threading.Thread(target=work, daemon=True).start()

        def set_mc_values(lid, versions):
            if selected_loader_id() != lid:
                return
            nonlocal dlg_mc_versions
            dlg_mc_versions = versions
            if versions:
                sel = mc_selected_var.get()
                if sel not in versions:
                    mc_selected_var.set(versions[0])
            mc_sel_label.configure(text="")
            render_mc_list()
            refresh_loader_versions()

        def refresh_loader_versions(_=None):
            lid = selected_loader_id()
            mc = mc_selected_var.get()
            if lid == "vanilla" or not mc:
                lv_menu.configure(values=["-"], state="disabled")
                lv_menu.set("-")
                return
            lv_menu.configure(state="normal", values=["Loading..."])

            def work():
                versions = core.loader_versions(lid, mc, stable_only=True) or \
                    core.loader_versions(lid, mc, stable_only=False)
                self.ui_call(set_lv_values, lid, mc, versions)

            threading.Thread(target=work, daemon=True).start()

        def set_lv_values(lid, mc, versions):
            if selected_loader_id() != lid or mc_selected_var.get() != mc:
                return
            if versions:
                lv_menu.configure(values=versions)
                lv_menu.set(data["loader_version"] if data["loader_version"] in versions else versions[0])
            else:
                lv_menu.configure(values=["(none)"])
                lv_menu.set("(none)")

        mc_filter_entry.bind("<KeyRelease>", lambda e: render_mc_list())
        loader_menu.configure(command=lambda _v: refresh_mc_versions())
        if data["mc_version"]:
            mc_sel_label.configure(text=f"Selected: {data['mc_version']}")

        def on_save():
            name = name_var.get().strip() or auto_name()
            mc = mc_selected_var.get()
            lid = selected_loader_id()
            lv = "" if lid == "vanilla" else (lv_menu.get() or "")
            if not name:
                messagebox.showwarning("Name", "Enter a name for the instance.", parent=dlg)
                return
            if not mc:
                messagebox.showwarning("Version", "Pick a Minecraft version.", parent=dlg)
                return
            if lid != "vanilla" and lv in ("", "-", "(none)", "Loading..."):
                messagebox.showwarning("Loader", "Pick a loader version.", parent=dlg)
                return
            ram = ram_var.get().strip()
            if ram and not ram.isdigit():
                messagebox.showwarning("RAM", "RAM must be a number in MB (e.g. 4096).", parent=dlg)
                return

            insts = config.load_instances()
            inst_id = data.get("id") or uuid.uuid4().hex
            entry = {
                "id": inst_id, "name": name, "loader_id": lid,
                "mc_version": mc, "loader_version": lv,
                "ram_mb": ram,
                "separate_game_dir": sep_var.get(),
                "shared_assets": shared_var.get(),
                "client_mod": cmod_var.get(),
                "server": server_var.get().strip(),
                "server_port": port_var.get().strip(),
                "created": data.get("created") or int(time.time()),
                "last_played": data.get("last_played", 0),
                "playtime_secs": data.get("playtime_secs", 0),
            }
            insts = [i for i in insts if i.get("id") != inst_id]
            insts.append(entry)
            config.save_instances(insts)
            self.instances = insts
            self._render_instances()
            dlg.destroy()
            if entry["separate_game_dir"] and shared_var.get():
                inst_dir = str(config.CONFIG_DIR / "instances" / inst_id)
                linked = sum(
                    core.ensure_shared_link(inst_dir, name,
                                            str(config.SHARED_DIR))
                    for name in core.SHARED_FOLDER_NAMES)
                self._set_status(
                    f"Shared {linked}/4 asset folders for '{name}'")

        btn_row = ctk.CTkFrame(dlg, fg_color="transparent")
        btn_row.grid(row=10, column=0, columnspan=2, sticky="e", padx=20, pady=20)
        ctk.CTkButton(btn_row, text="Cancel", width=90, fg_color=PALETTE["muted"],
                      command=dlg.destroy).pack(side="right", padx=6)
        ctk.CTkButton(btn_row, text="Save", width=90, command=on_save).pack(side="right", padx=6)

        refresh_mc_versions()

    def _build_accounts(self, parent):
        frame = ctk.CTkFrame(parent, fg_color="transparent")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(2, weight=1)

        top = ctk.CTkFrame(frame, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=8, pady=(20, 8))
        _title_box(top, "Accounts").pack(side="left")
        ctk.CTkButton(top, text="+ Add Microsoft Account", font=FONT,
                      command=self._on_add_account).pack(side="right")

        self.accounts_status = ctk.CTkLabel(frame, text="", font=FONT_SMALL,
                                            text_color=("gray35", "gray60"))
        self.accounts_status.grid(row=1, column=0, sticky="w", padx=8, pady=(0, 4))

        self.accounts_scroll = _scroll_frame(frame, label_text="")
        self.accounts_scroll.grid(row=2, column=0, sticky="nsew", padx=8, pady=(4, 16))
        self._render_accounts()

        return frame

    # ------------------------------------------------------------- Profile

    def _build_profile(self, parent):
        frame = ctk.CTkFrame(parent, fg_color="transparent")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)

        top = ctk.CTkFrame(frame, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=8, pady=(20, 8))
        _title_box(top, "Profile").pack(side="left")

        self.profile_scroll = _scroll_frame(frame, label_text="")
        self.profile_scroll.grid(row=1, column=0, sticky="nsew", padx=8, pady=(4, 16))
        return frame

    def _render_profile(self):
        for child in self.profile_scroll.winfo_children():
            child.destroy()
        user = users.current_user()
        if user:
            self._render_profile_user(user)
        else:
            self._render_profile_forms()

    def _form_card(self, title, parent=None, padx=4):
        card = ctk.CTkFrame(parent or self.profile_scroll,
                            fg_color=PALETTE["card"])
        card.pack(fill="x", pady=4, padx=padx)
        ctk.CTkLabel(card, text=title, font=FONT_BOLD).pack(anchor="w", padx=12, pady=(12, 4))
        return card

    def _render_profile_forms(self):
        login_card = self._form_card("Sign in")
        li_row = ctk.CTkFrame(login_card, fg_color="transparent")
        li_row.pack(fill="x", padx=12, pady=(0, 12))
        li_user = ctk.CTkEntry(li_row, placeholder_text="Username", width=170, font=FONT)
        li_user.pack(side="left", padx=(0, 8))
        li_pass = ctk.CTkEntry(li_row, placeholder_text="Password",
                               show="*", width=170, font=FONT)
        li_pass.pack(side="left", padx=8)

        def do_login():
            rec = users.authenticate(li_user.get(), li_pass.get())
            if rec is None:
                self.profile_status.configure(text="Wrong username or password")
                return
            if rec.get("error"):
                self.profile_status.configure(text=rec["error"])
                return
            users.login(rec)
            self._render_profile()

        ctk.CTkButton(li_row, text="Sign in", width=90, font=FONT,
                      command=do_login).pack(side="left", padx=8)
        li_pass.bind("<Return>", lambda e: do_login())

        su_card = self._form_card("Create account")
        su_row = ctk.CTkFrame(su_card, fg_color="transparent")
        su_row.pack(fill="x", padx=12, pady=(0, 4))
        su_user = ctk.CTkEntry(su_row, placeholder_text="Username", width=170, font=FONT)
        su_user.pack(side="left", padx=(0, 8))
        su_pass = ctk.CTkEntry(su_row, placeholder_text="Password (6+ chars)",
                               show="*", width=170, font=FONT)
        su_pass.pack(side="left", padx=8)
        su_pass2 = ctk.CTkEntry(su_row, placeholder_text="Repeat password",
                                show="*", width=170, font=FONT)
        su_pass2.pack(side="left", padx=8)

        def do_signup():
            if su_pass.get() != su_pass2.get():
                self.profile_status.configure(text="Passwords don't match")
                return
            err = users.create_user(su_user.get(), su_pass.get())
            if err:
                self.profile_status.configure(text=err)
                return
            rec = users.authenticate(su_user.get(), su_pass.get())
            if rec and not rec.get("error"):
                users.login(rec)
            self._render_profile()

        ctk.CTkButton(su_row, text="Sign up", width=90, font=FONT,
                      command=do_signup).pack(side="left", padx=8)
        note = ("Account on the chat server - works across PCs and unlocks "
                "Friends. Buying the client mod flips 'licensed' on it."
                if users.online_mode() else
                "Local-only account on this PC. "
                "Buying the mod flips 'licensed'.")
        ctk.CTkLabel(su_card, text=note,
                     font=FONT_SMALL, text_color=("gray35", "gray60"),
                     wraplength=560, justify="left"
                     ).pack(anchor="w", padx=12, pady=(0, 12))

        self.profile_status = ctk.CTkLabel(
            self.profile_scroll, text="", font=FONT_SMALL,
            text_color=("#e05252", "#e05252"))
        self.profile_status.pack(anchor="w", padx=8, pady=4)

    def _render_profile_user(self, user):
        card = self._form_card(user["name"])
        row = ctk.CTkFrame(card, fg_color="transparent")
        row.pack(fill="x", padx=12, pady=(0, 8))
        licensed = user["licensed"]
        ctk.CTkLabel(
            row, text="Mod: licensed" if licensed else "Mod: not licensed",
            font=FONT_SMALL,
            text_color=("#25a34a", "#25a34a") if licensed
            else ("#e0a030", "#e0a030")).pack(side="left")
        if user["admin"]:
            ctk.CTkLabel(row, text="  ·  admin", font=FONT_SMALL,
                         text_color=("gray35", "gray60")).pack(side="left")

        btns = ctk.CTkFrame(card, fg_color="transparent")
        btns.pack(fill="x", padx=12, pady=(0, 12))
        ctk.CTkButton(btns, text="Log out", width=100, font=FONT_SMALL,
                      fg_color=PALETTE["muted"],
                      hover_color=PALETTE["muted_hover"],
                      command=self._profile_logout).pack(side="left", padx=(0, 8))
        pw = ctk.CTkEntry(btns, placeholder_text="New password",
                          show="*", width=160, font=FONT)
        pw.pack(side="left", padx=8)

        def do_change():
            err = users.change_password(user["key"], pw.get())
            self.profile_status.configure(
                text=err or "Password changed",
                text_color=("#e05252", "#e05252") if err
                else ("#25a34a", "#25a34a"))
            if not err:
                pw.delete(0, "end")

        ctk.CTkButton(btns, text="Change", width=80, font=FONT_SMALL,
                      fg_color=PALETTE["muted"],
                      hover_color=PALETTE["muted_hover"],
                      command=do_change).pack(side="left", padx=8)

        self.profile_status = ctk.CTkLabel(
            self.profile_scroll, text="", font=FONT_SMALL,
            text_color=("#25a34a", "#25a34a"))
        self.profile_status.pack(anchor="w", padx=8, pady=4)

        if not licensed:
            ncard = self._form_card("Client mod")
            ctk.CTkLabel(
                ncard,
                text="The built-in HUD & QoL mod is locked until this account "
                     "is licensed. (Payment wiring comes later - for now the "
                     "flag is flipped manually.)",
                font=FONT_SMALL, text_color=("gray35", "gray60"),
                wraplength=560, justify="left").pack(anchor="w", padx=12, pady=(0, 12))

        if user["admin"]:
            acard = self._form_card("Admin - users")
            ctk.CTkLabel(
                acard,
                text="Type a profile username to manage its license - "
                     "asks for your password each time.",
                font=FONT_SMALL, text_color=("gray35", "gray60"),
                wraplength=560, justify="left").pack(
                anchor="w", padx=12, pady=(0, 6))
            grant_row = ctk.CTkFrame(acard, fg_color="transparent")
            grant_row.pack(fill="x", padx=12, pady=(0, 10))
            self.admin_grant_entry = ctk.CTkEntry(
                grant_row, placeholder_text="username", width=160,
                font=FONT)
            self.admin_grant_entry.pack(side="left")
            ctk.CTkButton(
                grant_row, text="Grant license", width=100,
                font=FONT_SMALL, fg_color=("#1a7f37", "#1a7f37"),
                hover_color=("#25a34a", "#25a34a"),
                command=lambda: self._admin_set_license(
                    self.admin_grant_entry.get(), True)
            ).pack(side="left", padx=8)
            ctk.CTkButton(
                grant_row, text="Revoke license", width=100,
                font=FONT_SMALL, fg_color=("#a33", "#a33"),
                hover_color=("#c44", "#c44"),
                command=lambda: self._admin_set_license(
                    self.admin_grant_entry.get(), False)
            ).pack(side="left", padx=4)
        self._refresh_session("profile")

    def _copy(self, text):
        self.clipboard_clear()
        self.clipboard_append(text)

    def _profile_logout(self):
        users.logout()
        self._render_profile()

    def _admin_pw(self, action):
        """Re-confirm the admin's password before a privileged write."""
        return simpledialog.askstring(
            "Confirm admin", f"Your password is required to {action}:",
            show="*", parent=self)

    def _admin_set_license(self, uname, licensed):
        uname = (uname or "").strip()
        if not uname:
            return
        user = users.current_user()
        if not user:
            return
        pw = self._admin_pw(
            f"{'grant' if licensed else 'revoke'} a license for {uname}")
        if pw is None:
            return
        if user.get("token"):
            from . import chat
            try:
                r = chat.admin_set_license(
                    user["token"], uname, licensed, pw)
            except Exception:
                r = {"error": "Chat server unreachable"}
            err = r.get("error")
        else:
            err = None if users.authenticate(user["name"], pw) \
                else "password required"
            if not err:
                users.set_licensed(uname, licensed)
        if err:
            messagebox.showerror("License", err, parent=self)
            return
        self._render_profile()

    # ------------------------------------------------------------- Friends

    def _build_friends(self, parent):
        frame = ctk.CTkFrame(parent, fg_color="transparent")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(2, weight=1)

        top = ctk.CTkFrame(frame, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=8, pady=(20, 8))
        _title_box(top, "Friends").pack(side="left")
        ctk.CTkButton(top, text="Refresh", width=90, height=28, font=FONT_SMALL,
                      fg_color=PALETTE["muted"],
                      hover_color=PALETTE["muted_hover"],
                      command=self._render_friends).pack(side="right")

        self.friends_status = ctk.CTkLabel(frame, text="", font=FONT_SMALL,
                                           text_color=("gray35", "gray60"))
        self.friends_status.grid(row=1, column=0, sticky="w", padx=8,
                                 pady=(0, 4))

        self.friends_scroll = _scroll_frame(frame, label_text="")
        self.friends_scroll.grid(row=2, column=0, sticky="nsew",
                                 padx=8, pady=(4, 16))
        return frame

    def _render_friends(self):
        draft = getattr(self, "friends_name_e", None)
        self._friends_draft = draft.get() if draft and draft.winfo_exists() \
            else getattr(self, "_friends_draft", "")
        for child in self.friends_scroll.winfo_children():
            child.destroy()
        user = users.current_user()
        if not user:
            ctk.CTkLabel(
                self.friends_scroll,
                text="Sign in on the Profile page to use Friends.",
                font=FONT, text_color=("gray35", "gray60")).pack(pady=20)
            return
        if not user.get("token"):
            ctk.CTkLabel(
                self.friends_scroll,
                text="Friends needs an online account - sign out and back "
                     "in on Profile.",
                font=FONT, text_color=("gray35", "gray60"),
                wraplength=560, justify="left").pack(pady=20)
            return
        ctk.CTkLabel(self.friends_scroll, text="Loading...",
                     font=FONT_SMALL,
                     text_color=("gray35", "gray60")).pack(pady=10)
        threading.Thread(target=self._fetch_friends, args=(user["token"],),
                         daemon=True).start()

    def _fetch_friends(self, token):
        from . import chat
        try:
            data = chat.friends(token)
            err = data.get("error")
        except Exception:
            data, err = None, "server unreachable"
        self.ui_call(self._fill_friends, token, data, err)

    def _fill_friends(self, token, data, err):
        if getattr(self, "_active_page", "") != "friends":
            return
        self._schedule_friends_poll()
        for child in self.friends_scroll.winfo_children():
            child.destroy()
        if err:
            self.friends_status.configure(text="Not connected")
            return
        self._last_friends_data = data
        self.friends_status.configure(text="Connected")

        # add friend
        card = self._form_card("Add friend", parent=self.friends_scroll,
                               padx=4)
        row = ctk.CTkFrame(card, fg_color="transparent")
        row.pack(fill="x", padx=12, pady=(0, 12))
        self.friends_name_e = ctk.CTkEntry(row, placeholder_text="Their username",
                                           width=200, font=FONT)
        self.friends_name_e.pack(side="left", padx=(0, 8))
        if getattr(self, "_friends_draft", ""):
            self.friends_name_e.insert(0, self._friends_draft)
        name_e = self.friends_name_e

        def do_add():
            target = name_e.get().strip()
            if not target:
                return
            name_e.delete(0, "end")
            def work():
                from . import chat
                try:
                    r = chat.friend_add(token, target)
                    self.ui_call(self.friends_status.configure,
                                 {"text": r.get("error") or
                                  ("Friend added" if r.get("friends")
                                   else "Request sent")})
                except Exception:
                    self.ui_call(self.friends_status.configure,
                                 {"text": "server unreachable"})
                self.ui_call(self._render_friends)
            threading.Thread(target=work, daemon=True).start()

        ctk.CTkButton(row, text="Send request", width=110, font=FONT,
                      command=do_add).pack(side="left", padx=8)
        name_e.bind("<Return>", lambda e: do_add())

        for title, names, kind in (
                ("Friend requests", data.get("incoming", []), "incoming"),
                ("Pending", data.get("outgoing", []), "outgoing")):
            if not names:
                continue
            card = self._form_card(title, parent=self.friends_scroll,
                                   padx=4)
            for name in names:
                r = ctk.CTkFrame(card, fg_color="transparent")
                r.pack(fill="x", padx=12, pady=2)
                ctk.CTkLabel(r, text=name, font=FONT).pack(side="left")
                if kind == "incoming":
                    ctk.CTkButton(
                        r, text="Accept", width=80, font=FONT_SMALL,
                        fg_color=("#1a7f37", "#1a7f37"),
                        hover_color=("#25a34a", "#25a34a"),
                        command=lambda n=name: self._friend_add(token, n)
                        ).pack(side="right")
                else:
                    ctk.CTkLabel(r, text="waiting...",
                                 font=FONT_SMALL,
                                 text_color=("gray35", "gray60")
                                 ).pack(side="right")

        card = self._form_card(f"Friends ({len(data.get('friends', []))})",
                               parent=self.friends_scroll, padx=4)
        names = data.get("friends", [])
        if not names:
            ctk.CTkLabel(card, text="No friends yet - add someone above.",
                         font=FONT_SMALL, text_color=("gray35", "gray60")
                         ).pack(anchor="w", padx=12, pady=(0, 12))
        for name in names:
            r = ctk.CTkFrame(card, fg_color="transparent")
            r.pack(fill="x", padx=12, pady=2)
            ctk.CTkLabel(r, text=name, font=FONT).pack(side="left")
            if name in self._unread:
                ctk.CTkLabel(r, text="●", font=FONT,
                             text_color="#e05252"
                             ).pack(side="left", padx=(8, 0))
            ctk.CTkButton(
                r, text="Remove", width=70, font=FONT_SMALL,
                fg_color=("#a33", "#a33"), hover_color=("#c44", "#c44"),
                command=lambda n=name: self._friend_remove(token, n)
                ).pack(side="right")
            ctk.CTkButton(
                r, text="Chat", width=70, font=FONT_SMALL,
                command=lambda n=name: self._open_chat(token, n)
                ).pack(side="right", padx=(0, 6))

    def _friend_add(self, token, name):
        def work():
            from . import chat
            try:
                chat.friend_add(token, name)
            except Exception:
                pass
            self.ui_call(self._render_friends)
        threading.Thread(target=work, daemon=True).start()

    def _friend_remove(self, token, name):
        def work():
            from . import chat
            try:
                chat.friend_remove(token, name)
            except Exception:
                pass
            self.ui_call(self._render_friends)
        threading.Thread(target=work, daemon=True).start()

    def _schedule_friends_poll(self):
        old = getattr(self, "_friends_poll_id", None)
        if old:
            self.after_cancel(old)
        self._friends_poll_id = self.after(10_000, self._friends_tick)

    def _friends_tick(self):
        """Re-fetch friends every 10s while the page is open; rebuilds
        only when the server data actually changed."""
        self._friends_poll_id = None
        if getattr(self, "_active_page", "") != "friends":
            return
        user = users.current_user()
        if not user or not user.get("token"):
            return
        token = user["token"]

        def work():
            from . import chat
            try:
                data = chat.friends(token)
            except Exception:
                data = None

            def finish():
                if getattr(self, "_active_page", "") != "friends":
                    return
                if data is not None and data != getattr(
                        self, "_last_friends_data", None):
                    self._fill_friends(token, data, None)
                else:
                    self._schedule_friends_poll()
            self.ui_call(finish)
        threading.Thread(target=work, daemon=True).start()

    def _open_chat(self, token, friend):
        from . import chat
        self._chat_open.add(friend)
        if friend in self._unread:
            self._unread.discard(friend)
            self._refresh_friends_badge()
            if getattr(self, "_active_page", "") == "friends":
                self._render_friends()
        dlg = ctk.CTkToplevel(self)
        dlg.title(f"Chat - {friend}")
        dlg.geometry("460x520")
        dlg.transient(self)
        dlg.grid_columnconfigure(0, weight=1)
        dlg.grid_rowconfigure(1, weight=1)

        ctk.CTkLabel(dlg, text=friend, font=FONT_BOLD).grid(
            row=0, column=0, sticky="w", padx=12, pady=(12, 4))

        scroll = _scroll_frame(dlg, label_text="")
        scroll.grid(row=1, column=0, sticky="nsew", padx=12, pady=4)

        entry = ctk.CTkEntry(dlg, placeholder_text="Message...", font=FONT)
        entry.grid(row=2, column=0, sticky="ew", padx=12, pady=(8, 14))
        entry.focus()

        state = {"since": 0, "open": True, "seen": set()}

        def render_msg(m):
            if m["id"] in state["seen"]:
                return
            state["seen"].add(m["id"])
            self._mark_read(friend, m["id"])
            mine = m["sender"].lower() == friend.lower()
            row = ctk.CTkFrame(scroll, fg_color="transparent")
            row.pack(fill="x", padx=6, pady=2)
            who = friend if mine else "You"
            col = PALETTE["accent"] if not mine else ("gray25", "gray75")
            bubble = ctk.CTkLabel(
                row, text=f"{who}: {m['text']}", font=FONT,
                text_color=col, wraplength=400, justify="left")
            bubble.pack(anchor="w")

        def poll():
            if not state["open"] or not dlg.winfo_exists():
                return
            def work():
                try:
                    data = chat.fetch(token, friend, state["since"])
                except Exception:
                    data = None
                if data:
                    msgs = data.get("messages", [])
                    if msgs:
                        state["since"] = max(m["id"] for m in msgs)
                        self.ui_call(lambda: [render_msg(m) for m in msgs])
                if state["open"]:
                    dlg.after(1500, poll)
            threading.Thread(target=work, daemon=True).start()

        def send_msg():
            text = entry.get().strip()
            if not text:
                return
            entry.delete(0, "end")
            def work():
                try:
                    chat.send(token, friend, text)
                except Exception:
                    pass
                try:
                    data = chat.fetch(token, friend, state["since"])
                    msgs = data.get("messages", [])
                    if msgs:
                        state["since"] = max(m["id"] for m in msgs)
                        self.ui_call(lambda: [render_msg(m) for m in msgs])
                except Exception:
                    pass
            threading.Thread(target=work, daemon=True).start()

        entry.bind("<Return>", lambda e: send_msg())
        dlg.protocol("WM_DELETE_WINDOW",
                     lambda: (state.update(open=False),
                              self._chat_open.discard(friend),
                              dlg.destroy()))
        poll()

    # ------------------------------------------------------------ Client mod

    MOD_FEATURES = [
        ("HUD widgets", "FPS, real ping, coords + facing, armor durability, "
                        "potion timers, keystrokes & CPS"),
        ("Zoom", "hold C + scroll to adjust (2-10x), smooth interpolation"),
        ("Fullbright", "gamma override - no options.txt edits"),
        ("Toggle sprint / sneak", "backtick = sprint, X = sneak"),
        ("Borderless window", "instant alt-tabbing, off by default"),
        ("Config screen", "Right-Shift in-game - toggles + drag widgets "
                          "anywhere"),
        ("Cross-instance", "one settings file shared by every instance"),
    ]

    def _build_mod(self, parent):
        frame = ctk.CTkFrame(parent, fg_color="transparent")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)

        top = ctk.CTkFrame(frame, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=8, pady=(20, 8))
        _title_box(top, "Client Mod").pack(side="left")

        self.mod_scroll = _scroll_frame(frame, label_text="")
        self.mod_scroll.grid(row=1, column=0, sticky="nsew", padx=8, pady=(4, 16))
        return frame

    def _render_mod(self):
        for child in self.mod_scroll.winfo_children():
            child.destroy()
        user = users.current_user()
        if not user:
            ctk.CTkLabel(self.mod_scroll,
                         text="Sign in on the Profile page to manage "
                              "the client mod.",
                         font=FONT, text_color=("gray35", "gray60")
                         ).pack(pady=20)
            return

        # features
        fcard = self._form_card("What it does", parent=self.mod_scroll)
        for title, desc in self.MOD_FEATURES:
            r = ctk.CTkFrame(fcard, fg_color="transparent")
            r.pack(fill="x", padx=12, pady=2)
            ctk.CTkLabel(r, text=f"•  {title}", font=FONT_BOLD,
                         width=170, anchor="w").pack(side="left")
            ctk.CTkLabel(r, text=desc, font=FONT_SMALL,
                         text_color=("gray35", "gray60"),
                         wraplength=430, justify="left").pack(side="left")
        ctk.CTkLabel(fcard, text="", height=4).pack()

        licensed = user.get("licensed")
        if not licensed:
            self._render_mod_purchase(user)
        else:
            self._render_mod_activation(user)
        if user.get("admin"):
            self._render_mod_seller()
        self._load_checkout_url()
        self._refresh_session("mod")

    def _refresh_session(self, page):
        """Pull fresh account flags off-thread; re-render if the
        server changed license/admin/binding since login."""
        user = users.current_user()
        if not user or not user.get("token"):
            return
        before = (user.get("licensed"), user.get("admin"),
                  user.get("bound_mc"), user.get("bound_at"))

        def work():
            users.refresh()
            u2 = users.current_user()
            after = (u2.get("licensed"), u2.get("admin"),
                     u2.get("bound_mc"), u2.get("bound_at")) if u2 else before
            if after != before and getattr(self, "_active_page", "") == page:
                self.ui_call(self._render_mod if page == "mod"
                             else self._render_profile)
        threading.Thread(target=work, daemon=True).start()

    def _load_checkout_url(self):
        """Fetch the store link off-thread so page render never stalls."""
        def work():
            try:
                users.fetch_checkout_url()
            except Exception:
                pass
            self.ui_call(self._fill_checkout_url)
        threading.Thread(target=work, daemon=True).start()

    def _fill_checkout_url(self):
        url = users.cached_checkout_url()
        try:
            if getattr(self, "mod_buy_btn", None):
                self.mod_buy_btn.configure(
                    state="normal" if url else "disabled")
                self.mod_buy_lbl.configure(
                    text=url or "store link not set up yet",
                    text_color=("gray35", "gray60") if url
                    else ("#e0a030", "#e0a030"))
            if getattr(self, "mod_store_entry", None) \
                    and url and not self.mod_store_entry.get():
                self.mod_store_entry.insert(0, url)
        except Exception:
            pass

    def _render_mod_purchase(self, user):
        pcard = self._form_card("Get the mod", parent=self.mod_scroll)
        ctk.CTkLabel(pcard,
                     text="One purchase unlocks the mod forever for one "
                          "Minecraft username.",
                     font=FONT, justify="left", wraplength=560
                     ).pack(anchor="w", padx=12, pady=(0, 8))

        prow = ctk.CTkFrame(pcard, fg_color="transparent")
        prow.pack(fill="x", padx=12, pady=(0, 6))
        url = users.cached_checkout_url()
        self.mod_buy_btn = ctk.CTkButton(
            prow, text="Buy now", width=110, font=FONT,
            command=lambda: webbrowser.open(users.cached_checkout_url()))
        self.mod_buy_btn.pack(side="left")
        if not url:
            self.mod_buy_btn.configure(state="disabled")
        self.mod_buy_lbl = ctk.CTkLabel(
            prow, text=url or "checking store...",
            font=FONT_SMALL,
            text_color=("gray35", "gray60") if url else ("#e0a030", "#e0a030"))
        self.mod_buy_lbl.pack(side="left", padx=10)

        ctk.CTkLabel(pcard, text="Have a purchase key?",
                     font=FONT_BOLD).pack(anchor="w", padx=12, pady=(8, 2))
        krow = ctk.CTkFrame(pcard, fg_color="transparent")
        krow.pack(fill="x", padx=12, pady=(0, 4))
        self.mod_key_entry = ctk.CTkEntry(krow, width=220, font=FONT,
                                          placeholder_text="BUY-XXXX-XXXX-XXXX")
        self.mod_key_entry.pack(side="left")
        self.mod_key_entry.bind("<Return>", lambda e: self._mod_redeem())
        self.mod_redeem_btn = ctk.CTkButton(
            krow, text="Redeem", width=90, font=FONT,
            command=self._mod_redeem)
        self.mod_redeem_btn.pack(side="left", padx=10)
        self.mod_key_status = ctk.CTkLabel(pcard, text="", font=FONT_SMALL,
                                           text_color=("gray35", "gray60"),
                                           wraplength=560, justify="left")
        self.mod_key_status.pack(anchor="w", padx=12, pady=(0, 12))

    def _render_mod_activation(self, user):
        # activation
        acard = self._form_card("Activation", parent=self.mod_scroll)
        info = users.binding_info()
        bound = info.get("bound_mc", "")
        locked = info.get("locked_for", 0)

        brow = ctk.CTkFrame(acard, fg_color="transparent")
        brow.pack(fill="x", padx=12, pady=(0, 4))
        ctk.CTkLabel(brow, text="Minecraft username:", font=FONT).pack(side="left")
        self.mod_bind_entry = ctk.CTkEntry(brow, width=200, font=FONT)
        self.mod_bind_entry.pack(side="left", padx=10)
        if bound:
            self.mod_bind_entry.insert(0, bound)
        self.mod_bind_btn = ctk.CTkButton(
            brow, text="Save", width=80, font=FONT,
            command=self._mod_bind)
        self.mod_bind_btn.pack(side="left")

        self.mod_bind_status = ctk.CTkLabel(acard, text="", font=FONT_SMALL,
                                            text_color=("gray35", "gray60"),
                                            wraplength=560, justify="left")
        self.mod_bind_status.pack(anchor="w", padx=12, pady=(0, 12))
        if bound and locked:
            hrs, mins = locked // 3600, (locked % 3600) // 60
            until = time.strftime("%H:%M %d %b",
                                  time.localtime(info["bound_at"] + 86400))
            self.mod_bind_status.configure(
                text=f"Locked to '{bound}' - {hrs}h {mins}m left "
                     f"(until {until})")
            self.mod_bind_entry.configure(state="disabled")
            self.mod_bind_btn.configure(state="disabled")
        elif bound:
            self.mod_bind_status.configure(
                text=f"Bound to '{bound}' - you can change it now")
        else:
            self.mod_bind_status.configure(
                text="The mod activates for this username only. "
                     "Once saved it locks for 24 hours.")

    def _render_mod_seller(self):
        scard = self._form_card("Seller tools", parent=self.mod_scroll)

        srow = ctk.CTkFrame(scard, fg_color="transparent")
        srow.pack(fill="x", padx=12, pady=(0, 4))
        ctk.CTkLabel(srow, text="Store link:", font=FONT).pack(side="left")
        self.mod_store_entry = ctk.CTkEntry(
            srow, width=260, font=FONT,
            placeholder_text="https://your-store/checkout")
        self.mod_store_entry.pack(side="left", padx=10)
        url = users.cached_checkout_url()
        if url:
            self.mod_store_entry.insert(0, url)
        self.mod_store_btn = ctk.CTkButton(
            srow, text="Save link", width=80, font=FONT,
            command=self._mod_store_save)
        self.mod_store_btn.pack(side="left")

        grow = ctk.CTkFrame(scard, fg_color="transparent")
        grow.pack(fill="x", padx=12, pady=(8, 4))
        ctk.CTkLabel(grow, text="Purchase keys:", font=FONT).pack(side="left")
        self.mod_gen_entry = ctk.CTkEntry(grow, width=60, font=FONT)
        self.mod_gen_entry.pack(side="left", padx=10)
        self.mod_gen_entry.insert(0, "5")
        self.mod_gen_btn = ctk.CTkButton(
            grow, text="Generate", width=90, font=FONT,
            command=self._mod_genkeys)
        self.mod_gen_btn.pack(side="left")
        ctk.CTkLabel(grow, text="shown once - copy before closing",
                     font=FONT_SMALL, text_color=("gray35", "gray60")
                     ).pack(side="left", padx=10)

        self.mod_keys_box = ctk.CTkTextbox(
            scard, height=110, font=FONT, wrap="none")
        self.mod_keys_box.pack(fill="x", padx=12, pady=(0, 12))
        self.mod_keys_box.insert("1.0", "Generated keys appear here.")
        self.mod_keys_box.configure(state="disabled")

    def _mod_store_save(self):
        pw = self._admin_pw("save the store link")
        if pw is None:
            return
        users.set_checkout_url(self.mod_store_entry.get(), pw)
        self.mod_store_btn.configure(text="Saved ✓")
        self.after(1500, lambda: self.mod_store_btn.configure(
            text="Save link"))

    def _mod_genkeys(self):
        pw = self._admin_pw("generate purchase keys")
        if pw is None:
            return
        try:
            count = int(self.mod_gen_entry.get() or 1)
        except ValueError:
            count = 1
        r = users.gen_keys(count, pw)
        self.mod_keys_box.configure(state="normal")
        self.mod_keys_box.delete("1.0", "end")
        if r.get("error"):
            self.mod_keys_box.insert("1.0", f"Error: {r['error']}")
        else:
            self.mod_keys_box.insert("1.0", "\n".join(r["keys"]))
        self.mod_keys_box.configure(state="disabled")

    def _mod_redeem(self):
        r = users.redeem_key(self.mod_key_entry.get())
        if r.get("ok"):
            self._render_mod()
            return
        self.mod_key_status.configure(
            text=r.get("error", "failed"), text_color=("#e05252", "#e05252"))

    def _mod_bind(self):
        mc_name = self.mod_bind_entry.get()
        r = users.bind_mc(mc_name)
        if r.get("ok"):
            self._render_mod()
            return
        err = r.get("error", "failed")
        if err == "locked":
            hrs, mins = r["locked_for"] // 3600, (r["locked_for"] % 3600) // 60
            err = f"Still locked to '{r.get('bound_mc')}' - {hrs}h {mins}m left"
        self.mod_bind_status.configure(
            text=err, text_color=("#e05252", "#e05252"))

    def _build_settings(self, parent):
        page = ctk.CTkFrame(parent, fg_color="transparent")
        page.grid_columnconfigure(0, weight=1)
        page.grid_rowconfigure(1, weight=1)

        top = ctk.CTkFrame(page, fg_color="transparent")
        top.grid(row=0, column=0, sticky="ew", padx=8, pady=(20, 8))
        _title_box(top, "Settings").pack(side="left")

        scroll = _scroll_frame(page, label_text="")
        scroll.grid(row=1, column=0, sticky="nsew", padx=8, pady=(4, 16))

        self.settings_vars = {}
        LABEL_W = 150

        def hint(card, text):
            ctk.CTkLabel(card, text=text, font=FONT_SMALL, justify="left",
                         anchor="w", wraplength=560,
                         text_color=("gray35", "gray60")
                         ).pack(fill="x", padx=12, pady=(0, 10))

        def field(card, label, key, note=None, width=300):
            r = ctk.CTkFrame(card, fg_color="transparent")
            r.pack(fill="x", padx=12, pady=(6, 0))
            ctk.CTkLabel(r, text=label, font=FONT, width=LABEL_W,
                         anchor="w").pack(side="left")
            var = tk.StringVar(value=str(self.settings.get(key, "")))
            ctk.CTkEntry(r, textvariable=var, width=width,
                         font=FONT).pack(side="left", padx=8)
            self.settings_vars[key] = var
            if note:
                hint(card, note)
            return r

        # ---- Game ----------------------------------------------------
        game = self._form_card("Game", parent=scroll)
        r = ctk.CTkFrame(game, fg_color="transparent")
        r.pack(fill="x", padx=12, pady=(6, 0))
        ctk.CTkLabel(r, text="Game folder", font=FONT, width=LABEL_W,
                     anchor="w").pack(side="left")
        var = tk.StringVar(value=str(self.settings.get("game_dir", "")))
        ctk.CTkEntry(r, textvariable=var, width=300,
                     font=FONT).pack(side="left", padx=8)
        self.settings_vars["game_dir"] = var
        ctk.CTkButton(r, text="Browse...", width=90, font=FONT,
                      command=self._browse_game_dir).pack(side="left")
        hint(game, "Where Minecraft, mods and your instances live.")
        field(game, "Java path (optional)", "java_path", width=300,
              note="Leave empty to auto-detect. Used for mod installers "
                   "and as a fallback Java.")

        # ---- Performance ----------------------------------------------
        perf = self._form_card("Performance", parent=scroll)
        field(perf, "Memory (MB)", "default_ram_mb", width=110,
              note="How much memory the game gets. "
                   "Per-instance override lives in Instances.")
        r = ctk.CTkFrame(perf, fg_color="transparent")
        r.pack(fill="x", padx=12, pady=(6, 0))
        ctk.CTkLabel(r, text="Performance preset", font=FONT,
                     width=LABEL_W, anchor="w").pack(side="left")
        self.jvm_preset_menu = ctk.CTkOptionMenu(
            r, values=list(JVM_PRESETS), width=260,
            font=FONT, command=self._apply_jvm_preset)
        current_args = str(self.settings.get("jvm_arguments", ""))
        match = next((name for name, args in JVM_PRESETS.items()
                      if args == current_args), None)
        self.jvm_preset_menu.set(match or "Standard (recommended)")
        self.jvm_preset_menu.pack(side="left", padx=8)
        self._java_major = None  # detected in background below
        hint(perf, "Tunes how Java runs the game - applies instantly. "
                   "Standard is fine for most people; only change this "
                   "if the game stutters.")

        # ---- Servers ---------------------------------------------------
        srv = self._form_card("Servers", parent=scroll)
        field(srv, "Auto-join server", "auto_join_server", width=300,
              note="e.g. hypixel.net - the game joins this server on "
                   "launch. Leave empty to pick a server in-game.")

        # ---- Game alerts ------------------------------------------------
        alerts = self._form_card("Game alerts", parent=scroll)
        field(alerts, "Alert triggers", "alert_patterns", width=300,
              note="Words separated by | - pops a Windows notification "
                   'when the game log matches (e.g. '
                   '"whispered to you|auction sold|restarting").')

        # ---- Appearance -------------------------------------------------
        look = self._form_card("Appearance", parent=scroll)
        r = ctk.CTkFrame(look, fg_color="transparent")
        r.pack(fill="x", padx=12, pady=(6, 10))
        ctk.CTkLabel(r, text="Theme", font=FONT, width=LABEL_W,
                     anchor="w").pack(side="left")
        self.theme_menu = ctk.CTkOptionMenu(
            r, values=[t.title() for t in THEMES], width=160,
            font=FONT, command=self._on_theme_pick)
        self.theme_menu.set(THEME_NAME.title())
        self.theme_menu.pack(side="left", padx=8)
        self.restart_btn = ctk.CTkButton(
            r, text="Restart now", width=110, font=FONT_SMALL,
            command=self._restart_launcher)
        hint(look, "Black or White - saves instantly, applies on next "
                   "launch.")

        # ---- Updates ----------------------------------------------------
        upd = self._form_card("Updates", parent=scroll)
        upd_row = ctk.CTkFrame(upd, fg_color="transparent")
        upd_row.pack(fill="x", padx=12, pady=(6, 0))
        self.update_btn = ctk.CTkButton(
            upd_row, text="Check for updates", width=150, font=FONT,
            command=self._check_update)
        self.update_btn.pack(side="left")
        self.upd_ver_lbl = ctk.CTkLabel(
            upd_row, text=f"v{config.APP_VERSION} installed",
            font=FONT_SMALL, text_color=("gray35", "gray60"))
        self.upd_ver_lbl.pack(side="left", padx=10)
        self.upd_dot = tk.Canvas(upd_row, width=15, height=15, bd=0,
                                 highlightthickness=0,
                                 bg=PALETTE["card"])
        self.upd_dot.create_oval(1.5, 1.5, 13.5, 13.5, fill="#e05252",
                               outline=PALETTE["badge_ring"], width=2)
        self.update_restart_btn = ctk.CTkButton(
            upd_row, text="Restart now", width=110, font=FONT_SMALL,
            command=self._restart_launcher)
        self.update_status = ctk.CTkLabel(
            upd, text="", font=FONT_SMALL, text_color=("gray35", "gray60"))
        self.update_status.pack(anchor="w", padx=12, pady=(0, 10))

        # ---- About ------------------------------------------------------
        about = self._form_card("About", parent=scroll)
        loc_row = ctk.CTkFrame(about, fg_color="transparent")
        loc_row.pack(fill="x", padx=12, pady=(6, 10))
        ctk.CTkLabel(loc_row, text="Install location", font=FONT,
                     width=LABEL_W, anchor="w").pack(side="left")
        ctk.CTkLabel(loc_row, text=str(config.INSTALL_DIR),
                     font=FONT_SMALL,
                     text_color=("gray35", "gray60")).pack(side="left")
        ctk.CTkButton(loc_row, text="Open folder", width=100,
                      font=FONT_SMALL,
                      command=lambda: os.startfile(config.INSTALL_DIR)
                      ).pack(side="left", padx=10)

        # ---- Save (bottom right) ----------------------------------------
        bottom = ctk.CTkFrame(scroll, fg_color="transparent")
        bottom.pack(fill="x", pady=8)
        self.save_status = ctk.CTkLabel(
            bottom, text="", font=FONT_SMALL,
            text_color=("#1a7f37", "#25a34a"))
        self.save_status.pack(side="right", padx=8)
        self.save_btn = ctk.CTkButton(
            bottom, text="Save settings", font=FONT_BOLD, height=36,
            width=140, command=self._save_settings)
        self.save_btn.pack(side="right", padx=8)

        def detect_java():
            self._java_major = core.java_major_version(
                self.settings.get("java_path") or core.find_java())

        threading.Thread(target=detect_java, daemon=True).start()

        return page

    # --------------------------------------------------------- Home logic

    def _load_versions(self):
        self._set_status("Loading version list...")
        try:
            versions = core.get_minecraft_versions()
        except Exception as e:
            self._set_status(f"Failed to load versions: {e}")
            return
        self.mc_versions = [
            {"id": v["id"], "type": v.get("type", "release")}
            for v in versions
            if v.get("type") != "pending"
        ]
        latest_release = next((v["id"] for v in versions if v.get("type") == "release"), None)
        self.ui_call(self._on_versions_loaded, latest_release)

    def _on_versions_loaded(self, latest_release):
        self._render_version_list()
        if latest_release and latest_release in self.version_buttons:
            self._select_version(latest_release)
        elif self.version_buttons:
            self._select_version(next(iter(self.version_buttons)))
        self._set_status("")

    def _filtered_versions(self):
        loader = self._current_loader()
        supported = self.supported_versions.get(loader)
        text = self.version_filter.get().strip().lower()
        show_legacy = self.show_legacy.get()
        show_snapshots = self.show_snapshots.get()
        result = []
        for v in self.mc_versions:
            if v["type"] in LEGACY_TYPES and not show_legacy:
                continue
            if v["type"] == "snapshot" and not show_snapshots:
                continue
            if supported and v["id"] not in supported:
                continue
            if text and text not in v["id"].lower():
                continue
            result.append(v)
        return result

    def _on_filter_typing(self, event):
        if self._filter_job is not None:
            self.after_cancel(self._filter_job)
        self._filter_job = self.after(FILTER_DEBOUNCE_MS, self._render_version_list)

    def _render_version_list(self):
        for child in self.version_scroll.winfo_children():
            child.destroy()
        self.version_buttons = {}
        matches = self._filtered_versions()
        for v in matches[:MAX_RENDERED_VERSIONS]:
            vid = v["id"]
            badge = {"release": "", "snapshot": " [snapshot]"}.get(v["type"], f" [{v['type']}]")
            btn = ctk.CTkButton(
                self.version_scroll, text=vid + badge, anchor="w",
                fg_color="transparent", text_color=("gray15", "gray85"),
                hover_color=PALETTE["nav"], font=FONT,
                command=lambda vid=vid: self._select_version(vid),
            )
            btn.pack(fill="x", pady=1)
            self.version_buttons[vid] = btn
        if len(matches) > MAX_RENDERED_VERSIONS:
            ctk.CTkLabel(
                self.version_scroll,
                text=f"...and {len(matches) - MAX_RENDERED_VERSIONS} more - refine the filter",
                font=FONT_SMALL, text_color=PALETTE["muted_hover"],
            ).pack(pady=6)
        if self.selected_mc_version and self.selected_mc_version in self.version_buttons:
            self._highlight_version(self.selected_mc_version)

    def _select_version(self, vid):
        self.selected_mc_version = vid
        self._highlight_version(vid)
        if self._current_loader() != "vanilla":
            self._load_loader_versions()

    def _highlight_version(self, vid):
        prev = getattr(self, "_highlighted_version", None)
        if prev and prev != vid and prev in self.version_buttons:
            self.version_buttons[prev].configure(
                fg_color="transparent", text_color=("gray15", "gray85"))
        if vid in self.version_buttons:
            self.version_buttons[vid].configure(
                fg_color=("#2f6fed", "#1f5fd0"), text_color="white")
            self._highlighted_version = vid

    def _current_loader(self):
        label = self.loader_seg.get()
        for key, name in core.LOADER_NAMES.items():
            if name == label:
                return key
        return "vanilla"

    def _on_loader_changed(self, label):
        self.loader_id = self._current_loader()
        is_vanilla = self.loader_id == "vanilla"
        self.loader_ver_row.grid_remove() if is_vanilla else self.loader_ver_row.grid()

        if not is_vanilla and self.loader_id not in self.supported_versions \
                and self.loader_id not in self._supported_pending:
            self._supported_pending.add(self.loader_id)

            def work(loader_id=self.loader_id):
                try:
                    versions = set(core.loader_mc_versions(loader_id, stable_only=False))
                except Exception:
                    versions = set()
                self.ui_call(self._supported_loaded, loader_id, versions)

            threading.Thread(target=work, daemon=True).start()

        if not is_vanilla:
            self._load_loader_versions()
        self._render_version_list()

    def _supported_loaded(self, loader_id, versions):
        self._supported_pending.discard(loader_id)
        if versions:
            self.supported_versions[loader_id] = versions
            self._render_version_list()

    def _load_loader_versions(self):
        loader_id = self.loader_id
        mc_version = self.selected_mc_version
        if not mc_version:
            return

        def work():
            versions = core.loader_versions(loader_id, mc_version, stable_only=True)
            if not versions:
                versions = core.loader_versions(loader_id, mc_version, stable_only=False)
            self.ui_call(self._set_loader_versions, versions)

        self.loader_ver_menu.configure(values=["Loading..."])
        threading.Thread(target=work, daemon=True).start()

    def _set_loader_versions(self, versions):
        self.loader_versions = versions
        if versions:
            self.loader_ver_menu.configure(values=versions)
            self.loader_ver_menu.set(versions[0])
        else:
            self.loader_ver_menu.configure(values=["(none)"])
            self.loader_ver_menu.set("(none)")

    # -------------------------------------------------------- Accounts page

    def _refresh_account_menu(self):
        names = [a.get("name", "Unknown") for a in self.accounts]
        values = names or ["No accounts"]
        for menu in (self.account_menu, self.instances_account_menu):
            menu.configure(values=values, state="normal" if names else "disabled")
        self._account_var.set(self._account_var.get() if self._account_var.get() in names else (names[0] if names else ""))

    def _selected_account(self):
        name = self._account_var.get()
        for a in self.accounts:
            if a.get("name") == name:
                return a
        return None

    def _render_accounts(self):
        for child in self.accounts_scroll.winfo_children():
            child.destroy()
        if not self.accounts:
            ctk.CTkLabel(self.accounts_scroll, text="No accounts yet - click '+ Add Microsoft Account'",
                         font=FONT, text_color=("gray35", "gray60")).pack(pady=20)
            return
        for account in self.accounts:
            row = ctk.CTkFrame(self.accounts_scroll, fg_color=PALETTE["card"])
            row.pack(fill="x", pady=4, padx=4)
            ctk.CTkLabel(row, text=account.get("name", "Unknown"), font=FONT_BOLD).pack(
                side="left", padx=12, pady=10)
            ctk.CTkButton(row, text="Remove", width=80, font=FONT_SMALL, fg_color=("#a33", "#a33"),
                          hover_color=("#c44", "#c44"),
                          command=lambda aid=account.get("id"): self._remove_account(aid)
                          ).pack(side="right", padx=12, pady=8)

    def _remove_account(self, account_id):
        auth.remove_account(account_id)
        self.accounts = config.load_accounts()
        self._render_accounts()
        self._refresh_account_menu()

    def _on_add_account(self):
        self.accounts_status.configure(text="Waiting for sign-in in your browser...")
        threading.Thread(target=self._add_account_worker, daemon=True).start()

    def _add_account_worker(self):
        try:
            profile = auth.microsoft_login()
        except Exception as e:
            self.ui_call(self.accounts_status.configure, text=f"Login failed: {e}")
            return
        auth.upsert_account(profile)
        self.ui_call(self._account_added, profile)

    def _account_added(self, profile):
        self.accounts = config.load_accounts()
        self.accounts_status.configure(text=f"Added {profile.get('name', 'account')}")
        self._render_accounts()
        self._refresh_account_menu()
        self._show_page("home")

    # ------------------------------------------------------------- Launch

    def _set_status(self, text):
        self.ui_call(self.status_label.configure, text=text)

    def _set_progress(self, value, maximum):
        def apply():
            frac = value / maximum if maximum else 0
            if frac > 0 and not self._progress_visible:
                self.progress_bar.pack(fill="x", pady=(0, 10))
                self._progress_visible = True
            elif frac <= 0 and self._progress_visible:
                self.progress_bar.pack_forget()
                self._progress_visible = False
            self.progress_bar.set(frac)
        self.ui_call(apply)

    def _on_play(self):
        if self._launching:
            return
        if "__quick__" in self._running:
            self._set_status("A game is already running from Play")
            return
        account = self._selected_account()
        if not account:
            messagebox.showinfo("No account", "Add a Microsoft account first (Accounts tab).")
            self._show_page("accounts")
            return
        mc_version = self.selected_mc_version
        if not mc_version:
            messagebox.showwarning("No version", "Select a Minecraft version first.")
            return

        loader_id = self.loader_id
        loader_version = None
        if loader_id != "vanilla":
            loader_version = self.loader_ver_menu.get()
            if not loader_version or loader_version in ("...", "(none)", "Loading..."):
                messagebox.showwarning("Loader version", "Loader versions are still loading - try again in a moment.")
                return

        try:
            version_id = core.installed_version_id(loader_id, mc_version, loader_version or "")
        except Exception as e:
            messagebox.showerror("Error", f"Could not resolve version: {e}")
            return

        self._launching = True
        self.play_button.configure(state="disabled", text="Working...")
        self._set_status("Preparing launch...")
        threading.Thread(
            target=self._launch_worker,
            args=(account, loader_id, mc_version, loader_version, version_id,
                  "", None, False),
            daemon=True,
        ).start()

    def _launch_worker(self, account, loader_id, mc_version, loader_version,
                       version_id, ram_mb, instance_id, separate_dir, inst=None):
        try:
            profile = auth.get_launch_profile(account)
            self.ui_call(self._account_profile_updated, profile)

            java = self.settings.get("java_path") or core.find_java()
            callback = {
                "setStatus": lambda s: self.ui_call(self._set_status, s),
                "setProgress": lambda p: self.ui_call(self._set_progress, p, getattr(self, "_cb_max", 0)),
                "setMax": lambda m: setattr(self, "_cb_max", m),
            }

            # built-in client mod needs Fabric Loader underneath - vanilla
            # instances silently ride a Fabric runtime for the same MC version
            cmod_jar = None
            want_cmod = users.is_licensed() or \
                (inst and inst.get("client_mod"))
            if want_cmod and loader_id in ("vanilla", "fabric", "quilt"):
                cmod_jar = core.client_mod_jar()
                if cmod_jar and loader_id == "vanilla":
                    lv = core.loader_versions("fabric", mc_version,
                                              stable_only=True) or \
                        core.loader_versions("fabric", mc_version,
                                             stable_only=False)
                    if lv:
                        loader_id, loader_version = "fabric", lv[0]
                        version_id = core.installed_version_id(
                            "fabric", mc_version, lv[0])
                    else:
                        cmod_jar = None  # no fabric for this version

            if not core.is_installed(version_id, self.settings["game_dir"]):
                self.ui_call(self._set_status, f"Installing {version_id}...")
                version_id = core.install_version(
                    loader_id, mc_version, loader_version, self.settings["game_dir"], java, callback
                )

            if instance_id:
                insts = config.load_instances()
                for i in insts:
                    if i.get("id") == instance_id:
                        i["last_played"] = int(time.time())
                        i["account_name"] = account.get("name", "")
                config.save_instances(insts)

            self.ui_call(self._set_status, "Launching Minecraft...")
            log_path = str(config.CONFIG_DIR / (
                f"game_output_{instance_id}.log" if instance_id
                else "game_output.log"))
            run_dir = None
            if separate_dir and instance_id:
                run_dir = str(config.CONFIG_DIR / "instances" / instance_id)
                if (inst or {}).get("shared_assets"):
                    for name in core.SHARED_FOLDER_NAMES:
                        core.ensure_shared_link(run_dir, name,
                                                str(config.SHARED_DIR))
            proc = core.launch_minecraft(
                version_id, profile, self.settings, ram_mb,
                log_path=log_path, game_dir_override=run_dir,
                server=(inst or {}).get("server", ""),
                port=(inst or {}).get("server_port", ""),
                client_mod_jar=cmod_jar)
            self.ui_call(self._set_status, f"{version_id} is running")
            self._running[instance_id or "__quick__"] = proc
            self.ui_call(self._render_instances)
            threading.Thread(target=self._watch_game,
                             args=(proc, inst, time.time(), log_path),
                             daemon=True).start()
            self.ui_call(self._launch_done, version_id)
        except Exception as e:
            self.ui_call(self._launch_failed, e)

    def _watch_game(self, proc, inst=None, started=None, log_path=None):
        last_tick = started or time.time()
        started = started or time.time()
        current_host, current_port = None, None
        inst_id = inst["id"] if inst else None
        log_file = Path(log_path) if log_path \
            else config.CONFIG_DIR / "game_output.log"
        log_size, log_pos = 0, 0
        last_log_change = time.time()

        # alert patterns -> compiled regexes (plain words work as substring match)
        alert_res = []
        for pat in re.split(r"[|\n]", self.settings.get("alert_patterns", "") or ""):
            pat = pat.strip()
            if pat:
                try:
                    alert_res.append(re.compile(pat, re.IGNORECASE))
                except re.error:
                    pass

        jstat = core.find_jstat()
        peak_kb, floor_kb = 0, 0.0
        ram_alloc_mb = 0
        if inst:
            try:
                ram_alloc_mb = int(inst.get("ram_mb") or
                                   self.settings.get("default_ram_mb") or 0)
            except ValueError:
                pass
            current_host = (inst.get("server") or
                            self.settings.get("auto_join_server") or "").strip() or None
            current_port = inst.get("server_port") or \
                self.settings.get("auto_join_port") or "25565"

        def tick(final=False):
            nonlocal last_tick, current_host, current_port
            nonlocal log_size, log_pos, last_log_change, peak_kb, floor_kb
            now = time.time()
            elapsed = int(now - last_tick)
            if elapsed <= 0:
                return
            try:
                log_text = log_file.read_text(encoding="utf-8", errors="replace")
                size = log_file.stat().st_size
                if size != log_size:
                    log_size = size
                    last_log_change = now
                    if inst_id in self._hung:
                        self._hung.discard(inst_id)
                        self.ui_call(self._render_instances)
                    if alert_res:
                        new_text = log_text[log_pos:] if log_pos else log_text
                        log_pos = len(log_text)
                        for line in new_text.splitlines():
                            if any(rx.search(line) for rx in alert_res):
                                core.toast_notify("MCLauncher", line.strip()[:140])
                                if self.settings.get("alert_sound", True):
                                    try:
                                        import winsound
                                        winsound.Beep(880, 180)
                                    except Exception:
                                        pass
                                break
                elif inst_id and now - started > 120 and \
                        now - last_log_change > 90 and inst_id not in self._hung:
                    self._hung.add(inst_id)
                    self.ui_call(self._render_instances)
                host, port = core.server_from_log(log_text)
                if host:
                    current_host, current_port = host, port
            except OSError:
                pass
            if jstat and inst_id and proc.poll() is None:
                sample = core.jstat_heap(proc.pid)
                if sample:
                    peak_kb = max(peak_kb, sample["used_kb"])
                    floor_kb = min(sample["used_kb"], floor_kb) if floor_kb \
                        else sample["used_kb"]
                    self._live_heap[inst_id] = sample
            if inst:
                try:
                    insts = config.load_instances()
                    target = next((i for i in insts
                                   if i.get("id") == inst["id"]), None)
                    if target:
                        target["playtime_secs"] = int(
                            target.get("playtime_secs", 0)) + elapsed
                        config.save_instances(insts)
                except OSError:
                    pass
            if inst and current_host:
                try:
                    stats = config.load_server_stats()
                    key = f"{current_host}:{current_port}"
                    entry = stats.setdefault(key, {"secs": 0, "last_played": 0})
                    entry["secs"] = int(entry.get("secs", 0)) + elapsed
                    entry["last_played"] = int(now)
                    entry["last_instance_id"] = inst["id"]
                    config.save_server_stats(stats)
                    self.ui_call(self._render_servers)
                except OSError:
                    pass
            last_tick = now
            if final and inst:
                self.ui_call(lambda: (
                    setattr(self, "instances", config.load_instances()),
                    self._render_instances()))

        while proc.poll() is None:
            time.sleep(30)
            if proc.poll() is None:
                tick()
        tick(final=True)

        # post-session RAM advice
        if inst_id and peak_kb and ram_alloc_mb:
            peak_mb = peak_kb / 1024
            if peak_mb < ram_alloc_mb * 0.45 and ram_alloc_mb >= 6144:
                self._session_advice[inst_id] = (
                    f"The game peaked at ~{peak_mb / 1024:.1f}GB but you gave it "
                    f"{ram_alloc_mb}MB. Extra RAM can cause stutters - try "
                    f"~{int((peak_mb * 1.6) // 512 * 512)}MB in Edit.")
            elif peak_mb > ram_alloc_mb * 0.9:
                self._session_advice[inst_id] = (
                    f"The game used ~{peak_mb / 1024:.1f}GB of your {ram_alloc_mb}MB "
                    f"allocation - consider raising it in Edit.")
        if inst_id:
            self._hung.discard(inst_id)
            self._live_heap.pop(inst_id, None)
        self._running.pop(inst["id"] if inst else "__quick__", None)
        self.ui_call(self._game_exited, proc.wait(), inst, log_file)

    def _game_exited(self, code, inst=None, log_path=None):
        stopped = False
        if inst:
            stopped = inst["id"] in self._stopping
            self._stopping.discard(inst["id"])
            self._running.pop(inst["id"], None)
            self._render_instances()
        if code == 0 or stopped:
            self._set_status("Minecraft closed" if code == 0 else "Stopped")
            advice = self._session_advice.pop(inst["id"], "") if inst else ""
            if advice:
                messagebox.showinfo("RAM suggestion", advice)
            return

        log_path = Path(log_path) if log_path \
            else config.CONFIG_DIR / "game_output.log"
        tail = ""
        try:
            lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
            tail = "\n".join(lines[-30:])
        except OSError:
            pass
        diag = core.diagnose_crash(tail)
        self._set_status(f"Minecraft exited with code {code}")
        self._show_crash_dialog(code, inst, diag, tail, log_path)

    def _show_crash_dialog(self, code, inst, diag, tail, log_path):
        dlg = ctk.CTkToplevel(self)
        dlg.title("Minecraft crashed")
        dlg.geometry("560x440")
        dlg.transient(self)
        dlg.grid_columnconfigure(0, weight=1)
        dlg.grid_rowconfigure(2, weight=1)

        ctk.CTkLabel(dlg, text=f"The game exited with code {code}",
                     font=FONT_BOLD).grid(row=0, column=0, sticky="w", padx=16, pady=(14, 4))

        findings = diag.get("findings") or []
        if findings:
            text = "\n".join(f"• {f}" for f in findings)
        else:
            text = "Couldn't pinpoint the cause from the log."
        ctk.CTkLabel(dlg, text=text, font=FONT, anchor="w",
                     justify="left", wraplength=520,
                     text_color=("#ffb04a", "#ffb04a") if findings else ("gray35", "gray60")
                     ).grid(row=1, column=0, sticky="w", padx=16, pady=(0, 6))

        log_box = ctk.CTkTextbox(dlg, font=FONT_SMALL, wrap="none")
        log_box.grid(row=2, column=0, sticky="nsew", padx=16, pady=6)
        log_box.insert("1.0", tail or "(no output)")
        log_box.configure(state="disabled")

        btn_row = ctk.CTkFrame(dlg, fg_color="transparent")
        btn_row.grid(row=3, column=0, sticky="e", padx=16, pady=12)
        ctk.CTkButton(btn_row, text="Close", width=90,
                      fg_color=PALETTE["muted"],
                      command=dlg.destroy).pack(side="right", padx=6)
        if inst and len(core.list_mods(str(self._instance_mods_dir(inst)))) >= 2:
            ctk.CTkButton(
                btn_row, text="Find broken mod (bisect)", width=170,
                fg_color=PALETTE["muted"], hover_color=PALETTE["muted_hover"],
                command=lambda: (dlg.destroy(), self._open_bisect_wizard(inst))
            ).pack(side="right", padx=6)
        ctk.CTkButton(btn_row, text="Full log", width=90,
                      fg_color=PALETTE["muted"],
                      command=lambda: os.startfile(str(log_path))).pack(side="right", padx=6)

        fix = diag.get("fix") or {}
        if inst and fix.get("type") == "mc_version":
            new_mc = fix["value"]

            def apply_version_fix():
                insts = config.load_instances()
                target = next((i for i in insts if i.get("id") == inst["id"]), None)
                if not target:
                    dlg.destroy()
                    return
                target["mc_version"] = new_mc
                if target.get("loader_id", "vanilla") != "vanilla":
                    target["loader_version"] = ""  # resolved below
                config.save_instances(insts)
                self.instances = insts
                self._render_instances()
                dlg.destroy()
                self._set_status(f"Instance switched to MC {new_mc} - check loader version in Edit")

            ctk.CTkButton(btn_row, text=f"Switch instance to {new_mc}",
                          fg_color=("#1a7f37", "#1a7f37"), hover_color=("#25a34a", "#25a34a"),
                          command=apply_version_fix).pack(side="right", padx=6)
        elif inst and fix.get("type") == "missing_mod":
            mod_id = fix["value"]
            ctk.CTkButton(btn_row, text=f"Find '{mod_id}' on Modrinth",
                          fg_color=("#1a7f37", "#1a7f37"), hover_color=("#25a34a", "#25a34a"),
                          command=lambda: (dlg.destroy(),
                                           self._open_mod_browser(inst, search_preset=mod_id))
                          ).pack(side="right", padx=6)
        elif inst and fix.get("type") == "ram":
            ctk.CTkButton(btn_row, text="Edit instance RAM",
                          fg_color=("#1a7f37", "#1a7f37"), hover_color=("#25a34a", "#25a34a"),
                          command=lambda: (dlg.destroy(), self._open_instance_editor(inst))
                          ).pack(side="right", padx=6)

    # --------------------------------------------------- Bisect wizard

    def _open_bisect_wizard(self, inst):
        mods_dir = str(self._instance_mods_dir(inst))
        enabled = [m["path"] for m in core.list_mods(mods_dir) if m["enabled"]]
        self._bisect = {"inst": inst, "mods_dir": mods_dir,
                        "suspects": enabled, "disabled_now": set()}

        dlg = ctk.CTkToplevel(self)
        dlg.title("Find the broken mod")
        dlg.geometry("540x260")
        dlg.transient(self)
        dlg.grid_columnconfigure(0, weight=1)
        self._bisect["dialog"] = dlg
        dlg.protocol("WM_DELETE_WINDOW", self._bisect_cancel)

        self._bisect_text = ctk.CTkLabel(dlg, text="", font=FONT, anchor="w",
                                         justify="left", wraplength=500)
        self._bisect_text.grid(row=0, column=0, sticky="w", padx=18, pady=(18, 8))
        btn_row = ctk.CTkFrame(dlg, fg_color="transparent")
        btn_row.grid(row=1, column=0, sticky="e", padx=18, pady=14)
        self._bisect_row = btn_row
        self._bisect_step()

    def _bisect_step(self):
        b = self._bisect
        suspects = b["suspects"]
        for child in b["row"].winfo_children():
            child.destroy()

        if len(suspects) <= 1:
            self._bisect_finish()
            return

        # enable all suspects first (back to a known state), then disable half
        for p in b["disabled_now"]:
            core.set_mod_enabled(p, True)
        b["disabled_now"] = set()
        half = suspects[len(suspects) // 2:]
        for p in half:
            core.set_mod_enabled(p, False)
            b["disabled_now"].add(
                p if p.endswith(".disabled") else p + ".disabled")
        b["enabled_half"] = suspects[:len(suspects) // 2]
        b["disabled_half"] = half

        self._bisect_text.configure(
            text=f"{len(suspects)} suspect mods left. I disabled {len(half)} of "
                 f"them.\n\nLaunch '{b['inst'].get('name', 'the instance')}' "
                 "(Play button), see if it crashes, then come back here.")
        ctk.CTkButton(b["row"], text="It crashed", width=110,
                      fg_color=("#a33", "#a33"), hover_color=("#c44", "#c44"),
                      command=lambda: self._bisect_answer(True)
                      ).pack(side="right", padx=6)
        ctk.CTkButton(b["row"], text="It ran fine", width=110,
                      fg_color=("#1a7f37", "#1a7f37"),
                      hover_color=("#25a34a", "#25a34a"),
                      command=lambda: self._bisect_answer(False)
                      ).pack(side="right", padx=6)
        ctk.CTkButton(b["row"], text="Cancel & restore", width=130,
                      fg_color=PALETTE["muted"],
                      command=self._bisect_cancel).pack(side="right", padx=6)

    def _bisect_answer(self, crashed):
        b = self._bisect
        # crashed -> culprit is in the still-enabled half; clean -> disabled half
        b["suspects"] = b["enabled_half"] if crashed else b["disabled_half"]
        self._bisect_step()

    def _bisect_restore(self):
        b = self._bisect
        for p in b.get("disabled_now", set()):
            core.set_mod_enabled(p, True)
        b["disabled_now"] = set()

    def _bisect_finish(self):
        b = self._bisect
        culprit = b["suspects"][0] if b["suspects"] else None
        self._bisect_restore()
        for child in b["row"].winfo_children():
            child.destroy()
        if culprit:
            name = os.path.basename(culprit).replace(".disabled", "")
            self._bisect_text.configure(
                text=f"Found it: '{name}' is almost certainly the mod that "
                     "crashes.\n\nIt's re-enabled right now - open Mods to "
                     "disable or delete it.")
            ctk.CTkButton(
                b["row"], text="Open Mods", width=110,
                command=lambda: (b["dialog"].destroy(),
                                 self._open_mod_browser(b["inst"]))
            ).pack(side="right", padx=6)
        else:
            self._bisect_text.configure(
                text="Couldn't isolate a single mod - the crash may be caused "
                     "by a combination, or not by a mod at all.")
        ctk.CTkButton(b["row"], text="Close", width=90,
                      fg_color=PALETTE["muted"],
                      command=b["dialog"].destroy).pack(side="right", padx=6)

    def _bisect_cancel(self):
        self._bisect_restore()
        self._bisect["dialog"].destroy()

    def _account_profile_updated(self, profile):
        for a in self.accounts:
            if a.get("id") == profile.get("id"):
                a["name"] = profile.get("name", a.get("name"))
        config.save_accounts(self.accounts)
        self._refresh_account_menu()

    def _launch_done(self, version_id):
        self._launching = False
        self.play_button.configure(state="normal", text="PLAY")
        self._set_progress(0, 0)
        self._set_status(f"Launched {version_id}")
        self.instances = config.load_instances()
        self._render_instances()

    def _launch_failed(self, error):
        self._launching = False
        self.play_button.configure(state="normal", text="PLAY")
        self._set_progress(0, 0)
        self._set_status("Launch failed")
        messagebox.showerror("Launch failed", str(error))

    # ------------------------------------------------------------- Settings

    def _browse_game_dir(self):
        directory = filedialog.askdirectory(initialdir=self.settings.get("game_dir", ""))
        if directory:
            self.settings_vars["game_dir"].set(directory)

    def _apply_jvm_preset(self, choice):
        args = JVM_PRESETS[choice]
        if choice == ZGC_PRESET:
            major = self._java_major
            if major is not None and major < 21:
                messagebox.showwarning(
                    "Java too old",
                    "ZGC needs Java 21 or newer. "
                    "Your detected Java is older - pick another preset "
                    "or update Java.")
                cur = str(self.settings.get("jvm_arguments", ""))
                prev = next((n for n, a in JVM_PRESETS.items()
                             if a == cur), "Standard (recommended)")
                self.jvm_preset_menu.set(prev)
                return
            # -XX:+ZGenerational only exists on Java 21-22; it's default
            # on 23+ and was removed entirely in 24/25 (startup error if
            # passed)
            if major is not None and major < 23:
                args += " -XX:+ZGenerational"
        self.settings["jvm_arguments"] = args
        self.settings["jvm_preset"] = choice
        config.save_settings(self.settings)
        self.save_status.configure(text="Preset applied")
        self.after(1800, lambda: self.save_status.configure(text=""))

    def _save_settings(self):
        for key, var in self.settings_vars.items():
            self.settings[key] = var.get()
        config.save_settings(self.settings)
        self.save_status.configure(text="Saved")
        self.save_btn.configure(text="Saved ✓")
        self.after(1800, lambda: (self.save_status.configure(text=""),
                                  self.save_btn.configure(text="Save settings")))

    def _on_theme_pick(self, choice):
        name = choice.lower()
        if name == THEME_NAME:
            self.restart_btn.pack_forget()
            return
        self.settings["theme"] = name
        config.save_settings(self.settings)
        self.restart_btn.pack(side="left", padx=(10, 0))

    def _restart_launcher(self):
        self._save_window_state()
        root = Path(__file__).resolve().parent.parent
        subprocess.Popen([sys.executable, "main.py"], cwd=str(root))
        self.destroy()

    def _check_update(self):
        self.update_btn.configure(state="disabled", text="Checking...")
        self.update_restart_btn.pack_forget()
        self.update_status.configure(text="")

        def work():
            root = Path(__file__).resolve().parent.parent
            flags = {}
            if hasattr(subprocess, "CREATE_NO_WINDOW"):
                flags["creationflags"] = subprocess.CREATE_NO_WINDOW
            try:
                p = subprocess.run(
                    [sys.executable, str(root / "tools" / "update.py")],
                    capture_output=True, text=True, timeout=180, **flags)
                lines = (p.stdout or "").strip().splitlines()
                msg = lines[-1] if lines else (p.stderr or "failed").strip()
                updated = "Updated to" in (p.stdout or "")
            except Exception as e:
                msg, updated = f"Update failed: {e}", False
            self.ui_call(self._update_done, msg, updated)
        threading.Thread(target=work, daemon=True).start()

    def _update_done(self, msg, updated):
        self.update_btn.configure(state="normal", text="Check for updates")
        self.update_status.configure(text=msg)
        if updated:
            self.update_restart_btn.pack(side="left", padx=10)

    # --------------------------------------------- update notifications

    def _update_check(self):
        """Ask GitHub for the latest version; banner if newer. Rechecks
        every 30 min while running."""
        self.after(1800000, self._update_check)

        def work():
            remote = config.remote_version()
            if config.version_newer(remote) \
                    and remote != self._update_dismissed:
                self.ui_call(self._show_update_banner, remote)
        threading.Thread(target=work, daemon=True).start()

    def _show_update_banner(self, remote):
        self._pending_update = remote
        if getattr(self, "update_banner", None) is None:
            bar = ctk.CTkFrame(self._content, border_width=1,
                               border_color=PALETTE["accent"],
                               corner_radius=4)
            self.upd_lbl = ctk.CTkLabel(bar, font=FONT,
                                        text_color=PALETTE["accent"])
            self.upd_lbl.pack(side="left", padx=12, pady=6)
            ctk.CTkButton(bar, text="Later", width=60, font=FONT_SMALL,
                          fg_color=PALETTE["muted"],
                          hover_color=PALETTE["muted_hover"],
                          command=self._dismiss_update
                          ).pack(side="right", padx=(4, 6), pady=4)
            self.upd_btn = ctk.CTkButton(
                bar, text="Update & restart", width=130, font=FONT_SMALL,
                command=self._apply_update)
            self.upd_btn.pack(side="right", padx=4, pady=4)
            self.update_banner = bar
        self.upd_lbl.configure(text=f"Update available - v{remote}")
        self.update_banner.grid(row=0, column=0, sticky="ew",
                                padx=8, pady=(8, 0))
        self._set_nav_badge("settings", True)
        if getattr(self, "upd_dot", None) is not None \
                and not self.upd_dot.winfo_ismapped():
            self.upd_dot.pack(side="left")

    def _dismiss_update(self):
        self._update_dismissed = self._pending_update
        self.update_banner.grid_remove()

    def _apply_update(self):
        self.upd_btn.configure(state="disabled", text="Updating...")

        def work():
            root = Path(__file__).resolve().parent.parent
            flags = {}
            if hasattr(subprocess, "CREATE_NO_WINDOW"):
                flags["creationflags"] = subprocess.CREATE_NO_WINDOW
            cmd = [sys.executable, str(root / "tools" / "update.py")]
            if getattr(self, "_pending_update", None):
                cmd += ["--expect", str(self._pending_update)]
            try:
                p = subprocess.run(
                    cmd, capture_output=True, text=True, timeout=180,
                    **flags)
                lines = (p.stdout or "").strip().splitlines()
                ok = "Updated to" in (p.stdout or "")
                msg = lines[-1] if lines else "Update failed"
            except Exception:
                ok, msg = False, "Update failed"
            self.ui_call(self._update_applied, ok, msg)
        threading.Thread(target=work, daemon=True).start()

    def _update_applied(self, ok, msg=""):
        if ok:
            self._restart_launcher()
            return
        self.upd_btn.configure(state="normal", text="Update & restart")
        self.upd_lbl.configure(text=msg or "Update failed")

    # ------------------------------------------------- nav badges + inbox

    def _sync_badge_bg(self, name):
        """Match the dot's bg to the button's *actually painted* color.
        Two delayed passes: one for hover/state repaints, one to catch
        CTk's ~100ms post-click re-highlight."""
        btn = self.nav_buttons.get(name)
        if btn is None:
            return
        def apply():
            dot = self._nav_dots.get(name)
            if dot is None:
                return
            try:
                col = btn._canvas.itemcget("inner_parts", "fill")
            except Exception:
                col = ""
            dot.configure(bg=col or PALETTE["sidebar"])
        btn.after(20, apply)
        btn.after(160, apply)

    def _set_nav_badge(self, name, on):
        """Discord-style red dot on a nav button."""
        btn = self.nav_buttons.get(name)
        if not btn:
            return
        dot = self._nav_dots.get(name)
        if on and dot is None:
            dot = tk.Canvas(btn, width=15, height=15,
                            highlightthickness=0, bd=0)
            dot.create_oval(1.5, 1.5, 13.5, 13.5, fill="#e05252",
                            outline=PALETTE["badge_ring"], width=2)
            dot.place(relx=1.0, x=-16, rely=0.5, anchor="e")
            dot.bind("<Button-1>",
                     lambda e, n=name: self._show_page(n))
            self._nav_dots[name] = dot
            self._sync_badge_bg(name)
        elif not on and dot is not None:
            dot.place_forget()
            self._nav_dots.pop(name, None)

    def _refresh_friends_badge(self):
        self._set_nav_badge("friends",
                            bool(self._unread) or self._new_requests)

    def _mark_read(self, friend, mid):
        if mid > self._last_read.get(friend, 0):
            self._last_read[friend] = mid
            self.settings["read_msgs"] = self._last_read
            config.save_settings(self.settings)
        if friend in self._unread:
            self._unread.discard(friend)
            self._refresh_friends_badge()

    def _msg_poll(self):
        """Global inbox watch: new friend requests + unread messages,
        independent of which page is open. Every 15s."""
        self.after(8000, self._msg_poll)
        user = users.current_user()
        if not user or not user.get("token"):
            if self._unread or self._new_requests:
                self._unread.clear()
                self._new_requests = False
                self._refresh_friends_badge()
            return
        token, myname = user["token"], user["name"]
        last_read = dict(self._last_read)

        def work():
            from . import chat
            try:
                data = chat.friends(token)
            except Exception:
                return
            latest = data.get("latest") or ""
            if latest and config.version_newer(latest) \
                    and latest != self._update_dismissed:
                self.ui_call(self._show_update_banner, latest)
            incoming = data.get("incoming") or []
            unread, new_read = [], {}
            for f in data.get("friends") or []:
                try:
                    r = chat.fetch(token, f, last_read.get(f, 0))
                except Exception:
                    continue
                msgs = r.get("messages") or []
                if not msgs:
                    continue
                mx = max(m["id"] for m in msgs)
                if f not in last_read:
                    new_read[f] = mx  # first sight = baseline, not unread
                elif any(m["sender"].lower() != myname.lower()
                         for m in msgs):
                    unread.append(f)
                    if f in self._chat_open:
                        new_read[f] = mx
            self.ui_call(self._msg_state, incoming, unread, new_read)
        threading.Thread(target=work, daemon=True).start()

    def _msg_state(self, incoming, unread, new_read):
        self._new_requests = bool(incoming)
        changed = False
        for f, mid in new_read.items():
            if mid > self._last_read.get(f, 0):
                self._last_read[f] = mid
                changed = True
        if changed:
            self.settings["read_msgs"] = self._last_read
            config.save_settings(self.settings)
        before = len(self._unread)
        self._unread.difference_update(self._chat_open)
        shrank = len(self._unread) != before
        for f in unread:
            if f not in self._chat_open and f not in self._unread:
                self._unread.add(f)
        self._refresh_friends_badge()
        if (shrank or self._unread - set(unread)) \
                and getattr(self, "_active_page", "") == "friends":
            self._render_friends()

    def _on_configure(self, event):
        if event.widget is not self:
            return
        if self._geom_after:
            self.after_cancel(self._geom_after)
        self._geom_after = self.after(600, self._save_window_state)

    def _save_window_state(self):
        self._geom_after = None
        try:
            zoomed = self.state() == "zoomed"
            self.settings["window_state"] = "zoomed" if zoomed else "normal"
            if not zoomed:
                geo = self.geometry()
                if re.match(r"^\d+x\d+[-+]-?\d+[-+]-?\d+$", geo):
                    self.settings["window_geometry"] = geo
            config.save_settings(self.settings)
        except Exception:
            pass

    def _on_close(self):
        if self._geom_after:
            self.after_cancel(self._geom_after)
        self._save_window_state()
        self.destroy()
