"""Microsoft account authentication for Minecraft.

Uses the OAuth2 authorization-code flow with PKCE against a temporary
localhost redirect, then runs the Xbox Live -> XSTS -> Minecraft Services
token chain. Accounts are stored in the launcher config directory.
"""

import base64
import hashlib
import json
import secrets
import threading
import urllib.parse
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

import minecraft_launcher_lib.microsoft_account as msa

from .config import load_accounts, save_accounts

# Public client ID registered for Minecraft launchers (same one Prism
# Launcher ships with). A launcher needs an Azure app that has been
# granted the Minecraft API permission, which requires a manual approval
# process - registering a personal app is impractical for local use.
CLIENT_ID = "c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb"
SCOPE = "XboxLive.signin offline_access"
TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
LOGIN_TIMEOUT = 300


class AuthError(Exception):
    pass


class LoginTimeout(AuthError):
    pass


class InvalidRefreshToken(AuthError):
    pass


class NoMinecraftOwnership(AuthError):
    pass


class _LoginHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)
        if "code" in params and "state" in params:
            self.server.auth_code = params["code"][0]
            self.server.auth_state = params["state"][0]
            self.server.auth_error = params.get("error", [None])[0]
            self.server.auth_event.set()
            body = (
                "<html><body style='font-family:sans-serif;text-align:center;"
                "padding-top:4em'><h2>Login successful</h2>"
                "<p>You can close this tab and return to the launcher.</p>"
                "</body></html>"
            )
            self.send_response(200)
        else:
            body = "<html><body>Invalid request</body></html>"
            self.send_response(404)
        self.send_header("Content-Type", "text/html")
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))

    def log_message(self, *args):
        pass


def _msa_token_to_profile(access_token: str) -> dict:
    xbl = msa.authenticate_with_xbl(access_token)
    if "Token" not in xbl:
        raise AuthError("Xbox Live authentication failed: " + json.dumps(xbl)[:300])

    xsts = msa.authenticate_with_xsts(xbl["Token"])
    if "Token" not in xsts:
        raise AuthError("XSTS authentication failed: " + json.dumps(xsts)[:300])

    userhash = xbl["DisplayClaims"]["xui"][0]["uhs"]
    mc = msa.authenticate_with_minecraft(userhash, xsts["Token"])
    if "access_token" not in mc:
        raise AuthError(
            "Minecraft authentication failed - the sign-in app may lack "
            "Minecraft API permission: " + json.dumps(mc)[:300]
        )

    profile = msa.get_profile(mc["access_token"])
    if "id" not in profile:
        raise NoMinecraftOwnership(
            "This Microsoft account does not own Minecraft: Java Edition"
        )

    profile["access_token"] = mc["access_token"]
    return profile


def microsoft_login(timeout: int = LOGIN_TIMEOUT) -> dict:
    """Run the interactive Microsoft login flow.

    Opens the user's browser, waits for the localhost redirect, and returns
    a profile dict with id, name, access_token and refresh_token.
    Blocks until login completes or times out.
    """
    server = ThreadingHTTPServer(("127.0.0.1", 0), _LoginHandler)
    port = server.server_address[1]
    redirect_uri = f"http://localhost:{port}/"

    server.auth_code = None
    server.auth_state = None
    server.auth_error = None
    server.auth_event = threading.Event()

    login_url, state, code_verifier = msa.get_secure_login_data(CLIENT_ID, redirect_uri)
    # Always show the account picker - without this Microsoft silently
    # reuses the current browser session, so "add account" can never
    # sign in as a different user.
    login_url += "&prompt=select_account"

    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.2}, daemon=True)
    thread.start()
    webbrowser.open(login_url)

    try:
        if not server.auth_event.wait(timeout):
            raise LoginTimeout("Timed out waiting for Microsoft login")
    finally:
        server.shutdown()
        server.server_close()

    if server.auth_error:
        raise AuthError(f"Microsoft login failed: {server.auth_error}")
    if server.auth_state != state:
        raise AuthError("Login state mismatch - please try again")
    if not server.auth_code:
        raise AuthError("No authorization code received")

    try:
        token_request = msa.get_authorization_token(
            CLIENT_ID, None, redirect_uri, server.auth_code, code_verifier
        )
    except Exception as e:
        raise AuthError(f"Token exchange failed: {e}")

    if "access_token" not in token_request:
        raise AuthError(
            "Token exchange failed: " + json.dumps(token_request)[:300]
        )

    try:
        profile = _msa_token_to_profile(token_request["access_token"])
    except (AuthError, NoMinecraftOwnership):
        raise
    except Exception as e:
        raise AuthError(f"Login failed: {e}")

    profile["refresh_token"] = token_request.get("refresh_token", "")
    return profile


def refresh_login(refresh_token: str) -> dict:
    """Exchange a stored refresh token for a fresh Minecraft profile."""
    try:
        response = requests.post(
            TOKEN_URL,
            data={
                "client_id": CLIENT_ID,
                "grant_type": "refresh_token",
                "refresh_token": refresh_token,
                "scope": SCOPE,
            },
            timeout=30,
        )
        data = response.json()
    except Exception as e:
        raise AuthError(f"Token refresh request failed: {e}")

    if "access_token" not in data:
        raise InvalidRefreshToken(
            "Session expired - please log in again "
            f"({data.get('error_description', data.get('error', 'unknown'))})"
        )

    try:
        profile = _msa_token_to_profile(data["access_token"])
    except NoMinecraftOwnership:
        raise
    except Exception as e:
        raise AuthError(f"Token refresh failed: {e}")

    profile["refresh_token"] = data.get("refresh_token", refresh_token)
    return profile


def get_launch_profile(account: dict) -> dict:
    """Get a fresh access token for launching, updating stored credentials."""
    profile = refresh_login(account["refresh_token"])

    accounts = load_accounts()
    for stored in accounts:
        if stored.get("id") == account.get("id"):
            stored["refresh_token"] = profile["refresh_token"]
            stored["name"] = profile.get("name", stored.get("name"))
            break
    save_accounts(accounts)

    return profile


def upsert_account(profile: dict) -> None:
    accounts = load_accounts()
    account = {
        "id": profile["id"],
        "name": profile.get("name", "Unknown"),
        "refresh_token": profile.get("refresh_token", ""),
    }
    accounts = [a for a in accounts if a.get("id") != account["id"]]
    accounts.append(account)
    save_accounts(accounts)


def remove_account(account_id: str) -> None:
    accounts = [a for a in load_accounts() if a.get("id") != account_id]
    save_accounts(accounts)


# ------------------------------------------------- OAuth profile sign-in
#
# Same Microsoft authorize+PKCE pipeline as the Minecraft login, but with
# plain OIDC scopes (openid profile email). Returns the raw token response
# - the relay verifies the access token via Microsoft userinfo and maps it
# to a launcher account. No Minecraft ownership needed.

OAUTH_SCOPE = "openid profile email offline_access"


def oauth_ms_signin(timeout: int = LOGIN_TIMEOUT) -> dict:
    """Interactive Microsoft OIDC sign-in. Returns the token response:
    {access_token, id_token, refresh_token, ...}."""
    server = ThreadingHTTPServer(("127.0.0.1", 0), _LoginHandler)
    port = server.server_address[1]
    redirect_uri = f"http://localhost:{port}/"

    server.auth_code = None
    server.auth_state = None
    server.auth_error = None
    server.auth_event = threading.Event()

    verifier = base64.urlsafe_b64encode(
        secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)

    params = {
        "client_id": CLIENT_ID,
        "response_type": "code",
        "redirect_uri": redirect_uri,
        "response_mode": "query",
        "scope": OAUTH_SCOPE,
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "prompt": "select_account",
    }
    login_url = ("https://login.microsoftonline.com/consumers/oauth2/v2.0/"
                 "authorize?" + urllib.parse.urlencode(params))

    thread = threading.Thread(target=server.serve_forever,
                              kwargs={"poll_interval": 0.2}, daemon=True)
    thread.start()
    webbrowser.open(login_url)

    try:
        if not server.auth_event.wait(timeout):
            raise LoginTimeout("Timed out waiting for Microsoft sign-in")
    finally:
        server.shutdown()
        server.server_close()

    if server.auth_error:
        raise AuthError(f"Microsoft sign-in failed: {server.auth_error}")
    if server.auth_state != state:
        raise AuthError("Sign-in state mismatch - please try again")
    if not server.auth_code:
        raise AuthError("No authorization code received")

    try:
        data = requests.post(TOKEN_URL, data={
            "client_id": CLIENT_ID,
            "grant_type": "authorization_code",
            "code": server.auth_code,
            "redirect_uri": redirect_uri,
            "code_verifier": verifier,
            "scope": OAUTH_SCOPE,
        }, timeout=30).json()
    except Exception as e:
        raise AuthError(f"Token exchange failed: {e}")

    if "access_token" not in data:
        raise AuthError("Token exchange failed: " + json.dumps(data)[:300])
    return data
