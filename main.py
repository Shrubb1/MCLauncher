import os
import sys

if os.name == "nt":
    import ctypes
    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("MCLauncher.App")

from launcher.gui import LauncherApp


def main():
    app = LauncherApp()
    app.mainloop()


if __name__ == "__main__":
    sys.exit(main())
