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
    """Set a color entry; our color fills both light and dark slots so the
    theme is identical regardless of OS appearance mode."""
    if key in d:
        if isinstance(d[key], list) and len(d[key]) == 2:
            d[key] = [light if light is not None else dark, dark]
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
    # text_color stays as the base ['gray10','gray90'] pair so popup
    # items stay readable in both the black and white themes

    _THEME_OUT.mkdir(parents=True, exist_ok=True)
    out = _THEME_OUT / f"{name}.json"
    out.write_text(json.dumps(theme, indent=2))
    return str(out)


# name -> theme def (accent, hover, text, app, frame, top, border, entry,
#                   scroll, radius, palette)  -- monochrome: black & white
_DEFS = {
    "black": ("#565e6b", "#454c57", "#f2f4f8", "#0d0e10", "#15171a",
              "#1a1d21", "#292d33", "#191c20", "#22262b", 4,
              {"sidebar": "#090a0b", "card": "#15171a", "muted": "#22262b",
               "muted_hover": "#30353d", "nav": "#22262b",
               "accent": "#6b7480", "badge_ring": "#f2f4f8"}),
    "white": ("#374151", "#2b3442", "#ffffff", "#f4f5f7", "#ffffff",
              "#eceef1", "#d4d8de", "#ffffff", "#dfe3e9", 4,
              {"sidebar": "#e9ebef", "card": "#ffffff", "muted": "#dfe3e9",
               "muted_hover": "#c8cdd5", "nav": "#dfe3e9",
               "accent": "#4b5563", "badge_ring": "#111827"}),
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
    return env if env in _DEFS else "black"


THEME_NAME = _pick_theme()
_d = _DEFS[THEME_NAME]
CTK_THEME_PATH = _build_theme(THEME_NAME, *_d[:10])
PALETTE = _d[10]
