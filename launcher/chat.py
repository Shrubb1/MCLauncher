"""Thin client for the relay server (server/relay.py)."""

import requests

from . import config


def base_url() -> str:
    url = str(config.load_settings().get("chat_server") or "").strip()
    return url.rstrip("/")


def enabled() -> bool:
    return bool(base_url())


def _post(path, payload, timeout=8):
    r = requests.post(base_url() + path, json=payload, timeout=timeout)
    return r.json()


def ping() -> bool:
    try:
        return bool(requests.get(base_url() + "/ping", timeout=4).json().get("ok"))
    except Exception:
        return False


def signup(name, password):
    return _post("/signup", {"name": name, "password": password})


def login(name, password):
    return _post("/login", {"name": name, "password": password})


def oauth_ms(access_token):
    return _post("/oauth/ms", {"access_token": access_token})


def oauth_register(pending, name):
    return _post("/oauth/register", {"pending": pending, "name": name})


def account_bind(token, mc_name):
    return _post("/account/bind", {"token": token, "mc_name": mc_name})


def account_password(token, password):
    return _post("/account/password", {"token": token, "password": password})


def me(token):
    return _post("/me", {"token": token})


def server_config():
    try:
        return requests.get(base_url() + "/config", timeout=4).json()
    except Exception:
        return {}


def redeem(token, key):
    return _post("/redeem", {"token": token, "key": key})


def friends(token):
    return _post("/friends", {"token": token})


def set_presence(token, state, server="", instance="", mc_version=""):
    return _post("/presence", {
        "token": token, "state": state, "server": server,
        "instance": instance, "mc_version": mc_version})


def friend_add(token, name):
    return _post("/friends/add", {"token": token, "friend": name})


def friend_remove(token, name):
    return _post("/friends/remove", {"token": token, "friend": name})


def send(token, to, text):
    return _post("/messages/send", {"token": token, "to": to, "text": text})


def fetch(token, other, since=0):
    return _post("/messages", {"token": token, "with": other, "since": since})


def admin_users(token):
    return _post("/admin/users", {"token": token})


def admin_set_license(token, user, licensed, password=""):
    return _post("/admin/license",
                 {"token": token, "user": user, "licensed": licensed,
                  "password": password})


def admin_genkeys(token, count, password=""):
    return _post("/admin/genkeys",
                 {"token": token, "count": count, "password": password})


def admin_config(token, checkout_url, password=""):
    return _post("/admin/config",
                 {"token": token, "checkout_url": checkout_url,
                  "password": password})
