"""Visual themes: generated CTk color themes + structural palette.

20 themes, picked via (in order): MCL_THEME env var (previews), the
'theme' key in settings.json, else 'onyx'. Each theme derives a
customtkinter JSON theme from the installed 'blue' base and overrides
accent/surface/corner-radius values.
"""

import json
import os
import sys
from pathlib import Path

if getattr(sys, "frozen", False):
    _BASE = Path(sys.executable).resolve().parent
else:
    _BASE = Path(__file__).resolve().parent.parent
_THEME_OUT = _BASE / "build" / "themes"


def _rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def _mix(a, b, t):
    ar, ag, ab = _rgb(a)
    br, bg, bb = _rgb(b)
    return "#{:02x}{:02x}{:02x}".format(
        round(ar + (br - ar) * t),
        round(ag + (bg - ag) * t),
        round(ab + (bb - ab) * t))


def _deep_set(d, key, dark, light=None):
    """Set a color entry; tuple entries keep their light-mode value."""
    if key in d:
        if isinstance(d[key], list) and len(d[key]) == 2:
            d[key] = [light if light is not None else d[key][0], dark]
        else:
            d[key] = dark


def _mk(accent, hover, tint, radius, text="#f2f4f8"):
    """Build a theme def by blending the neutral surface ramp toward a hue."""
    app = _mix("#14161a", tint, 0.22)
    frame = _mix("#1d2026", tint, 0.22)
    top = _mix("#22252c", tint, 0.25)
    border = _mix("#30363f", tint, 0.20)
    entry = _mix("#23272e", tint, 0.18)
    scroll = _mix("#2c313a", tint, 0.25)
    return (accent, hover, text, app, frame, top, border, entry,
            scroll, radius,
            {"sidebar": _mix("#101215", tint, 0.30), "card": frame,
             "muted": scroll,
             "muted_hover": _mix("#363c46", tint, 0.30), "nav": scroll,
             "accent": _mix(accent, "#ffffff", 0.15)})


def _build_theme(name, accent, hover, button_text, app_bg, frame_bg,
                 frame_top, border, entry_bg, scroll, radius):
    base = Path(__import__("customtkinter").__file__).parent
    src = base / "assets" / "themes" / "blue.json"
    theme = json.loads(src.read_text())
    t = theme["CTk"]
    _deep_set(t, "fg_color", app_bg)
    _deep_set(theme["CTkToplevel"], "fg_color", app_bg)
    f = theme["CTkFrame"]
    f["corner_radius"] = radius
    _deep_set(f, "fg_color", frame_bg)
    _deep_set(f, "top_fg_color", frame_top)
    _deep_set(f, "border_color", border)
    b = theme["CTkButton"]
    b["corner_radius"] = radius
    _deep_set(b, "fg_color", accent)
    _deep_set(b, "hover_color", hover)
    _deep_set(b, "text_color", button_text)
    e = theme["CTkEntry"]
    e["corner_radius"] = radius
    _deep_set(e, "fg_color", entry_bg)
    _deep_set(e, "border_color", border)
    for w in ("CTkCheckBox", "CTkRadioButton"):
        c = theme[w]
        if "corner_radius" in c and w == "CTkCheckBox":
            c["corner_radius"] = radius
        _deep_set(c, "fg_color", accent)
        _deep_set(c, "hover_color", hover)
    _deep_set(theme["CTkSwitch"], "progress_color", accent)
    _deep_set(theme["CTkProgressBar"], "progress_color", accent)
    _deep_set(theme["CTkProgressBar"], "fg_color", scroll)
    s = theme["CTkSlider"]
    _deep_set(s, "progress_color", accent)
    _deep_set(s, "button_color", accent)
    _deep_set(s, "button_hover_color", hover)
    o = theme["CTkOptionMenu"]
    o["corner_radius"] = radius
    _deep_set(o, "fg_color", accent)
    _deep_set(o, "button_color", accent)
    _deep_set(o, "button_hover_color", hover)
    _deep_set(o, "text_color", button_text)
    cb = theme["CTkComboBox"]
    cb["corner_radius"] = radius
    _deep_set(cb, "fg_color", entry_bg)
    _deep_set(cb, "border_color", border)
    _deep_set(cb, "button_color", scroll)
    _deep_set(cb, "button_hover_color", border)
    sc = theme["CTkScrollbar"]
    _deep_set(sc, "button_color", scroll)
    _deep_set(sc, "button_hover_color", border)
    sb = theme["CTkSegmentedButton"]
    sb["corner_radius"] = radius
    _deep_set(sb, "fg_color", frame_bg)
    _deep_set(sb, "unselected_color", frame_bg)
    _deep_set(sb, "selected_color", accent)
    _deep_set(sb, "selected_hover_color", hover)
    _deep_set(sb, "unselected_hover_color", scroll)
    tb = theme["CTkTextbox"]
    tb["corner_radius"] = radius
    _deep_set(tb, "fg_color", entry_bg)
    _deep_set(tb, "border_color", border)
    _deep_set(theme["CTkScrollableFrame"], "label_fg_color", frame_bg)
    dm = theme["DropdownMenu"]
    _deep_set(dm, "fg_color", frame_bg)
    _deep_set(dm, "hover_color", scroll)
    _deep_set(dm, "text_color", "#e8eaf0")

    _THEME_OUT.mkdir(parents=True, exist_ok=True)
    out = _THEME_OUT / f"{name}.json"
    out.write_text(json.dumps(theme, indent=2))
    return str(out)


# name -> theme def (accent, hover, text, app, frame, top, border, entry,
#                   scroll, radius, palette)  -- explicit or via _mk()
_DEFS = {
    "onyx": ("#3d7ff0", "#2f68d8", "#eef2ff", "#14161a", "#1d2026",
             "#22252c", "#30363f", "#23272e", "#2c313a", 4,
             {"sidebar": "#101215", "card": "#1d2026", "muted": "#2c313a",
              "muted_hover": "#363c46", "nav": "#2c313a",
              "accent": "#4d8dff"}),
    "indigo": ("#7c6cf5", "#6657e0", "#f0eeff", "#16141f", "#1e1b2e",
               "#232040", "#312b52", "#231f38", "#2b2742", 8,
               {"sidebar": "#100e19", "card": "#1e1b2e", "muted": "#2b2742",
                "muted_hover": "#37315a", "nav": "#2b2742",
                "accent": "#9a8dff"}),
    "emerald": ("#12b586", "#0d9570", "#eafff7", "#111916", "#17231f",
                "#1c2b26", "#294039", "#1c2b26", "#22322c", 6,
                {"sidebar": "#0d1311", "card": "#17231f", "muted": "#22322c",
                 "muted_hover": "#2b4239", "nav": "#22322c",
                 "accent": "#2fd6a3"}),
    "ember": ("#e0853d", "#c46f2f", "#fff4ea", "#1a1410", "#241b15",
              "#2b2018", "#40302a", "#2a211a", "#332720", 6,
              {"sidebar": "#130e0a", "card": "#241b15", "muted": "#332720",
               "muted_hover": "#423228", "nav": "#332720",
               "accent": "#f0a05a"}),
    "slate": _mk("#8b9bb4", "#6d7f9c", "#39424f", 6),
    "crimson": _mk("#e05252", "#bd3a3a", "#4d2828", 6),
    "rose": _mk("#ef6da0", "#d14f85", "#4a2b3d", 8),
    "ocean": _mk("#2cb5dd", "#2196bc", "#1d3d4a", 6),
    "forest": _mk("#55b25a", "#429a47", "#24402a", 6),
    "grape": _mk("#a06be8", "#8450c9", "#3a2a4a", 8),
    "gold": _mk("#e0b53d", "#bd9428", "#4a3d1e", 4),
    "mint": _mk("#42d6b5", "#2bb396", "#1e4038", 8),
    "arctic": _mk("#7cc7f0", "#5aa8d4", "#2a3d4d", 8),
    "midnight": _mk("#4a5fd0", "#3a4ab0", "#1e2444", 4),
    "lime": _mk("#a3d635", "#85b02a", "#33401e", 6),
    "coral": _mk("#f07a5a", "#d15f42", "#4a2e26", 8),
    "fuchsia": _mk("#e04ad0", "#bd35ae", "#44203f", 8),
    "mocha": _mk("#b08968", "#916d50", "#3a2e26", 6),
    "nord": _mk("#88c0d0", "#63a3b4", "#2a3a44", 6),
    "cyber": _mk("#3df06a", "#2ac253", "#1a3324", 4),
}

THEMES = list(_DEFS)


def _pick_theme():
    env = os.environ.get("MCL_THEME")
    if env in _DEFS:
        return env
    try:
        from .config import load_settings
        saved = load_settings().get("theme", "")
        if saved in _DEFS:
            return saved
    except Exception:
        pass
    return env if env in _DEFS else "onyx"


THEME_NAME = _pick_theme()
_d = _DEFS[THEME_NAME]
CTK_THEME_PATH = _build_theme(THEME_NAME, *_d[:10])
PALETTE = _d[10]
