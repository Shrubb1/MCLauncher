"""One-time admin account creator - run locally, never inside the app.

    python tools/create_admin.py <username> <password>

Creates an account in %APPDATA%\\MCLauncher\\users.json with
admin=True and licensed=True. Nothing in the shipped launcher can
create or elevate admins - that only happens through this file's
direct write, so an admin exists only on machines where it was made.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from launcher import users  # noqa: E402


def main():
    if len(sys.argv) != 3:
        print("usage: create_admin.py <username> <password>")
        return 1
    name, password = sys.argv[1], sys.argv[2]
    err = users.create_user(name, password, licensed=True, admin=True)
    if err:
        print(f"error: {err}")
        return 1
    print(f"admin '{name}' created in {users.USERS_FILE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
