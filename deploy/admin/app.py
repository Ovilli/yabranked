#!/usr/bin/env python3
"""YAB Ranked admin console — logs and live match control, on the LAN.

A separate process from the backend on purpose. The backend is published to the
internet through cloudflared (yabranked.ovilli.de), so an admin surface added to
it would be an admin surface on the internet; this one binds to the host's own
addresses and is reached over the LAN or Tailscale, where the login is a second
lock rather than the only one.

Standard library only — the host has no pip environment and this has to keep
working after an OS upgrade.

    python3 app.py set-password          # writes ~/.yabranked-admin/config.json
    python3 app.py serve --port 8091     # foreground
    python3 app.py serve --bind 127.0.0.1

What it can reach, and what that is worth knowing about:

  * `journalctl -u yabranked-backend` — readable unprivileged, no sudo anywhere
    in this file.
  * the match logs the backend copies out of each container before removing it.
  * the docker socket, for listing match containers, following their logs, and
    tearing one down. Docker socket access is root-equivalent on this host, so
    the login below is what stands in front of it: every mutating route is POST,
    carries a CSRF token, and is written to an audit log.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

CONFIG_DIR = Path(os.environ.get("YABRANKED_ADMIN_HOME", Path.home() / ".yabranked-admin"))
CONFIG_FILE = CONFIG_DIR / "config.json"
AUDIT_FILE = CONFIG_DIR / "audit.log"

UNIT = os.environ.get("YABRANKED_UNIT", "yabranked-backend")
MATCH_LOG_DIR = Path(os.environ.get("YABRANKED_MATCH_LOG_DIR", "/var/lib/yabranked/match-logs"))
REPLAY_DIR = Path(os.environ.get("YABRANKED_REPLAY_DIR", "/var/lib/yabranked/replays"))
CONTAINER_PREFIX = "yabranked-match-"

SESSION_COOKIE = "yabadmin"
SESSION_TTL_SECONDS = 12 * 3600
# A wrong password is a typo the first few times and an attack after that.
MAX_FAILURES = 5
LOCKOUT_SECONDS = 300
KDF_ITERATIONS = 240_000

UUID_RE = re.compile(r"^[0-9a-fA-F-]{8,36}$")


# --------------------------------------------------------------------------
# config and credentials


def load_config() -> dict:
    if not CONFIG_FILE.exists():
        sys.exit(
            f"no config at {CONFIG_FILE} — run `python3 {sys.argv[0]} set-password` first"
        )
    with CONFIG_FILE.open() as fh:
        return json.load(fh)


def save_config(cfg: dict) -> None:
    CONFIG_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    tmp = CONFIG_FILE.with_suffix(".tmp")
    with tmp.open("w") as fh:
        json.dump(cfg, fh, indent=2)
    tmp.chmod(0o600)
    tmp.replace(CONFIG_FILE)


def hash_password(password: str, salt: bytes, iterations: int = KDF_ITERATIONS) -> str:
    return hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations).hex()


def cmd_set_password(_args) -> None:
    password = getpass.getpass("new admin password: ")
    if len(password) < 10:
        sys.exit("too short — use at least 10 characters")
    if password != getpass.getpass("repeat: "):
        sys.exit("passwords did not match")
    salt = secrets.token_bytes(16)
    cfg = {
        "salt": salt.hex(),
        "iterations": KDF_ITERATIONS,
        "password_hash": hash_password(password, salt),
        # Rotating the secret on a password change logs every session out, which
        # is the point of changing it.
        "session_secret": secrets.token_hex(32),
    }
    save_config(cfg)
    print(f"written to {CONFIG_FILE} (0600)")


def audit(request_ip: str, action: str, detail: str) -> None:
    CONFIG_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    line = f"{datetime.now(timezone.utc).isoformat()}\t{request_ip}\t{action}\t{detail}\n"
    with AUDIT_FILE.open("a") as fh:
        fh.write(line)


# --------------------------------------------------------------------------
# sessions
#
# A signed cookie rather than server-side state: this process gets restarted by
# hand often enough that in-memory sessions would mean logging in after every
# restart, and there is nothing in a session here worth storing.


def issue_session(secret: str) -> str:
    expires = int(time.time()) + SESSION_TTL_SECONDS
    nonce = secrets.token_hex(8)
    body = f"{expires}.{nonce}"
    sig = hmac.new(secret.encode(), body.encode(), hashlib.sha256).hexdigest()
    return f"{body}.{sig}"


def check_session(token: str | None, secret: str) -> bool:
    if not token:
        return False
    parts = token.split(".")
    if len(parts) != 3:
        return False
    expires, nonce, sig = parts
    body = f"{expires}.{nonce}"
    expected = hmac.new(secret.encode(), body.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(sig, expected):
        return False
    try:
        return int(expires) > time.time()
    except ValueError:
        return False


def csrf_token(session: str, secret: str) -> str:
    return hmac.new(secret.encode(), f"csrf:{session}".encode(), hashlib.sha256).hexdigest()


class Throttle:
    """Per-IP login failure counter. Crude on purpose — it only has to make a
    password guess cost minutes, and this listens on the LAN, not the world."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._state: dict[str, tuple[int, float]] = {}

    def locked_for(self, ip: str) -> int:
        with self._lock:
            failures, last = self._state.get(ip, (0, 0.0))
            if failures < MAX_FAILURES:
                return 0
            remaining = int(LOCKOUT_SECONDS - (time.time() - last))
            if remaining <= 0:
                del self._state[ip]
                return 0
            return remaining

    def fail(self, ip: str) -> None:
        with self._lock:
            failures, _ = self._state.get(ip, (0, 0.0))
            self._state[ip] = (failures + 1, time.time())

    def succeed(self, ip: str) -> None:
        with self._lock:
            self._state.pop(ip, None)


# --------------------------------------------------------------------------
# the host, as this app is allowed to see it
#
# Every command is a list — nothing here is ever handed to a shell, and every
# id that reaches one of them is validated first.


def run(cmd: list[str], timeout: int = 20) -> str:
    try:
        done = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        return f"({cmd[0]} not found)"
    except subprocess.TimeoutExpired:
        return f"({' '.join(cmd)} timed out)"
    return (done.stdout or "") + (done.stderr or "")


def journal(lines: int = 300, pattern: str | None = None) -> str:
    out = run(["journalctl", "-u", UNIT, "-n", str(lines), "--no-pager", "-o", "short-iso"], timeout=30)
    if pattern:
        try:
            rx = re.compile(pattern, re.IGNORECASE)
        except re.error as exc:
            return f"(bad pattern: {exc})"
        out = "\n".join(line for line in out.splitlines() if rx.search(line))
    return out


def match_logs() -> list[dict]:
    if not MATCH_LOG_DIR.is_dir():
        return []
    entries = []
    for path in MATCH_LOG_DIR.glob("*.log"):
        stat = path.stat()
        entries.append(
            {
                "id": path.stem,
                "size": stat.st_size,
                "modified": datetime.fromtimestamp(stat.st_mtime).strftime("%Y-%m-%d %H:%M"),
                "mtime": stat.st_mtime,
            }
        )
    return sorted(entries, key=lambda e: e["mtime"], reverse=True)


def match_log_text(match_id: str, pattern: str | None = None) -> str:
    if not UUID_RE.match(match_id):
        return "(invalid match id)"
    path = MATCH_LOG_DIR / f"{match_id}.log"
    # resolve() then compare, so a crafted id can never walk out of the directory
    if not path.resolve().parent == MATCH_LOG_DIR.resolve() or not path.exists():
        return "(no such match log)"
    text = path.read_text(errors="replace")
    if pattern:
        try:
            rx = re.compile(pattern, re.IGNORECASE)
        except re.error as exc:
            return f"(bad pattern: {exc})"
        text = "\n".join(line for line in text.splitlines() if rx.search(line))
    return text


def live_containers() -> list[dict]:
    out = run(
        [
            "docker", "ps",
            "--filter", f"name={CONTAINER_PREFIX}",
            "--format", "{{.Names}}\t{{.Status}}\t{{.Ports}}",
        ]
    )
    rows = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2 and parts[0].startswith(CONTAINER_PREFIX):
            rows.append(
                {
                    "name": parts[0],
                    "match": parts[0][len(CONTAINER_PREFIX):],
                    "status": parts[1],
                    "ports": parts[2] if len(parts) > 2 else "",
                }
            )
    return rows


def container_logs(name: str, tail: int = 300) -> str:
    if not valid_container(name):
        return "(invalid container name)"
    return run(["docker", "logs", "--tail", str(tail), name], timeout=30)


def valid_container(name: str) -> bool:
    return name.startswith(CONTAINER_PREFIX) and UUID_RE.match(name[len(CONTAINER_PREFIX):]) is not None


def host_status() -> dict:
    usage = shutil.disk_usage("/")
    replays = run(["du", "-sh", str(REPLAY_DIR)]).split("\t")[0].strip() if REPLAY_DIR.exists() else "—"
    return {
        "backend": run(["systemctl", "is-active", UNIT]).strip(),
        "image": run(["docker", "images", "yabranked-match", "--format", "{{.ID}}  {{.CreatedAt}}"]).strip(),
        "containers": run(["docker", "ps", "--format", "{{.Names}}  {{.Status}}"]).strip(),
        "disk": f"{usage.used // 2**30}G used of {usage.total // 2**30}G — {usage.free // 2**30}G free "
                f"({100 * usage.used // usage.total}%)",
        "replays": replays,
        "matchLogs": len(match_logs()),
    }


# --------------------------------------------------------------------------
# the page


PAGE = """<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>yabranked admin</title>
<style>
 :root { color-scheme: dark; --bg:#12141a; --panel:#1a1d26; --line:#2a2f3d; --fg:#d7dae3; --dim:#8b93a7; --accent:#6ea8fe; --bad:#ff6b6b; --good:#5fd97f; }
 * { box-sizing: border-box; }
 body { margin:0; background:var(--bg); color:var(--fg); font:14px/1.5 system-ui,sans-serif; }
 header { display:flex; gap:16px; align-items:center; padding:10px 16px; border-bottom:1px solid var(--line); background:var(--panel); position:sticky; top:0; z-index:2; flex-wrap:wrap; }
 header b { color:#fff; }
 nav button { background:none; border:1px solid transparent; color:var(--dim); padding:6px 12px; border-radius:6px; cursor:pointer; font:inherit; }
 nav button.on { color:#fff; border-color:var(--line); background:#232735; }
 main { padding:16px; }
 .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(240px,1fr)); gap:12px; }
 .card { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:12px; }
 .card h3 { margin:0 0 6px; font-size:12px; text-transform:uppercase; letter-spacing:.08em; color:var(--dim); font-weight:600; }
 .card pre { margin:0; white-space:pre-wrap; word-break:break-word; }
 pre.log { background:#0d0f14; border:1px solid var(--line); border-radius:8px; padding:12px; overflow:auto; max-height:70vh;
           font:12px/1.45 ui-monospace,Menlo,Consolas,monospace; white-space:pre; }
 .row { display:flex; gap:8px; align-items:center; margin-bottom:10px; flex-wrap:wrap; }
 input[type=text], input[type=password], select { background:#0d0f14; border:1px solid var(--line); color:var(--fg); border-radius:6px; padding:7px 9px; font:inherit; }
 input[type=text] { min-width:220px; }
 button.act { background:#232735; border:1px solid var(--line); color:var(--fg); border-radius:6px; padding:7px 12px; cursor:pointer; font:inherit; }
 button.act:hover { border-color:var(--accent); }
 button.danger { border-color:#5a2b2b; color:#ffb4b4; }
 button.danger:hover { border-color:var(--bad); }
 table { width:100%; border-collapse:collapse; }
 td, th { text-align:left; padding:7px 8px; border-bottom:1px solid var(--line); font-size:13px; }
 th { color:var(--dim); font-weight:600; font-size:11px; text-transform:uppercase; letter-spacing:.06em; }
 a { color:var(--accent); text-decoration:none; cursor:pointer; }
 .mono { font-family:ui-monospace,Menlo,Consolas,monospace; }
 .dim { color:var(--dim); }
 .good { color:var(--good); } .bad { color:var(--bad); }
 .hide { display:none; }
</style>
<header>
  <b>yabranked admin</b>
  <nav>
    <button data-tab="overview" class="on">Overview</button>
    <button data-tab="backend">Backend log</button>
    <button data-tab="matches">Match logs</button>
    <button data-tab="live">Live</button>
  </nav>
  <span style="flex:1"></span>
  <span class="dim mono" id="clock"></span>
  <form method="post" action="/logout"><input type="hidden" name="csrf" value="__CSRF__">
    <button class="act">Log out</button></form>
</header>
<main>
  <section id="tab-overview">
    <div class="grid" id="status"></div>
  </section>

  <section id="tab-backend" class="hide">
    <div class="row">
      <input type="text" id="bq" placeholder="filter (regex, e.g. void|orchestrat)">
      <select id="blines"><option>200</option><option selected>500</option><option>2000</option><option>5000</option></select>
      <button class="act" id="bgo">Load</button>
      <button class="act" id="bfollow">Follow</button>
      <span class="dim" id="bstate"></span>
    </div>
    <pre class="log" id="blog">…</pre>
  </section>

  <section id="tab-matches" class="hide">
    <div class="row"><button class="act" id="mrefresh">Refresh</button>
      <input type="text" id="mq" placeholder="filter inside the open log (regex)">
      <span class="dim" id="mopen"></span></div>
    <table id="mtable"><thead><tr><th>match</th><th>when</th><th>size</th></tr></thead><tbody></tbody></table>
    <pre class="log hide" id="mlog"></pre>
  </section>

  <section id="tab-live" class="hide">
    <div class="row"><button class="act" id="lrefresh">Refresh</button><span class="dim" id="lstate"></span></div>
    <table id="ltable"><thead><tr><th>match</th><th>status</th><th>ports</th><th></th></tr></thead><tbody></tbody></table>
    <pre class="log hide" id="llog"></pre>
  </section>
</main>
<script>
const CSRF = "__CSRF__";
const $ = s => document.querySelector(s);
const esc = s => s.replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));

document.querySelectorAll('nav button').forEach(b => b.onclick = () => {
  document.querySelectorAll('nav button').forEach(x => x.classList.toggle('on', x === b));
  ['overview','backend','matches','live'].forEach(t =>
    $('#tab-' + t).classList.toggle('hide', t !== b.dataset.tab));
  if (b.dataset.tab === 'overview') loadStatus();
  if (b.dataset.tab === 'matches') loadMatches();
  if (b.dataset.tab === 'live') loadLive();
});

setInterval(() => $('#clock').textContent = new Date().toLocaleTimeString(), 1000);

async function loadStatus() {
  const s = await (await fetch('/api/status')).json();
  $('#status').innerHTML = `
    <div class="card"><h3>backend</h3><pre class="${s.backend === 'active' ? 'good' : 'bad'}">${esc(s.backend)}</pre></div>
    <div class="card"><h3>disk /</h3><pre>${esc(s.disk)}</pre></div>
    <div class="card"><h3>replays on disk</h3><pre>${esc(s.replays)}</pre></div>
    <div class="card"><h3>match image</h3><pre class="mono">${esc(s.image)}</pre></div>
    <div class="card"><h3>containers</h3><pre class="mono">${esc(s.containers || '(none)')}</pre></div>
    <div class="card"><h3>match logs kept</h3><pre>${s.matchLogs}</pre></div>`;
}

let follower = null;
function stopFollow() { if (follower) { follower.close(); follower = null; $('#bfollow').textContent = 'Follow'; $('#bstate').textContent = ''; } }
async function loadBackend() {
  stopFollow();
  const q = encodeURIComponent($('#bq').value), n = $('#blines').value;
  $('#blog').textContent = 'loading…';
  const r = await fetch(`/api/journal?lines=${n}&q=${q}`);
  $('#blog').textContent = (await r.json()).text || '(nothing)';
  $('#blog').scrollTop = $('#blog').scrollHeight;
}
$('#bgo').onclick = loadBackend;
$('#bq').onkeydown = e => { if (e.key === 'Enter') loadBackend(); };
$('#bfollow').onclick = () => {
  if (follower) return stopFollow();
  $('#blog').textContent = '';
  follower = new EventSource('/stream/journal?q=' + encodeURIComponent($('#bq').value));
  follower.onmessage = e => {
    $('#blog').textContent += e.data + '\\n';
    $('#blog').scrollTop = $('#blog').scrollHeight;
  };
  follower.onerror = () => { $('#bstate').textContent = 'stream ended'; stopFollow(); };
  $('#bfollow').textContent = 'Stop';
  $('#bstate').textContent = 'following…';
};

let openMatch = null;
async function loadMatches() {
  const rows = await (await fetch('/api/matches')).json();
  $('#mtable tbody').innerHTML = rows.map(r =>
    `<tr><td><a class="mono" data-id="${r.id}">${r.id}</a></td><td>${r.modified}</td><td>${(r.size/1024).toFixed(0)} KB</td></tr>`
  ).join('') || '<tr><td colspan="3" class="dim">no match logs yet</td></tr>';
  document.querySelectorAll('#mtable a').forEach(a => a.onclick = () => openMatchLog(a.dataset.id));
}
async function openMatchLog(id) {
  openMatch = id;
  $('#mopen').textContent = id;
  $('#mlog').classList.remove('hide');
  $('#mlog').textContent = 'loading…';
  const q = encodeURIComponent($('#mq').value);
  $('#mlog').textContent = (await (await fetch(`/api/match?id=${id}&q=${q}`)).json()).text || '(empty)';
}
$('#mrefresh').onclick = loadMatches;
$('#mq').onkeydown = e => { if (e.key === 'Enter' && openMatch) openMatchLog(openMatch); };

async function loadLive() {
  const rows = await (await fetch('/api/live')).json();
  $('#lstate').textContent = rows.length ? '' : 'no match containers running';
  $('#ltable tbody').innerHTML = rows.map(r =>
    `<tr><td class="mono">${r.match}</td><td>${esc(r.status)}</td><td class="dim mono">${esc(r.ports)}</td>
     <td><button class="act" data-log="${r.name}">Logs</button>
         <button class="act danger" data-kill="${r.name}">Tear down</button></td></tr>`
  ).join('') || '<tr><td colspan="4" class="dim">nothing running</td></tr>';
  document.querySelectorAll('[data-log]').forEach(b => b.onclick = async () => {
    $('#llog').classList.remove('hide');
    $('#llog').textContent = 'loading…';
    $('#llog').textContent = (await (await fetch('/api/container?name=' + b.dataset.log)).json()).text;
    $('#llog').scrollTop = $('#llog').scrollHeight;
  });
  document.querySelectorAll('[data-kill]').forEach(b => b.onclick = async () => {
    const name = b.dataset.kill;
    if (!confirm(`Tear down ${name}?\\n\\nThe match dies with it. The backend's sweep will void it — no rating moves, and both players are dropped.`)) return;
    const body = new URLSearchParams({ csrf: CSRF, name });
    const r = await fetch('/action/teardown', { method: 'POST', body });
    alert((await r.json()).message);
    loadLive();
  });
}
$('#lrefresh').onclick = loadLive;

loadStatus();
</script>
"""

LOGIN_PAGE = """<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>yabranked admin — login</title>
<style>
 :root { color-scheme: dark; }
 body { margin:0; height:100vh; display:grid; place-items:center; background:#12141a; color:#d7dae3;
        font:14px/1.5 system-ui,sans-serif; }
 form { background:#1a1d26; border:1px solid #2a2f3d; border-radius:10px; padding:22px; width:300px; }
 h1 { margin:0 0 14px; font-size:15px; }
 input { width:100%; background:#0d0f14; border:1px solid #2a2f3d; color:#d7dae3; border-radius:6px;
         padding:9px; font:inherit; margin-bottom:10px; }
 button { width:100%; background:#232735; border:1px solid #2a2f3d; color:#fff; border-radius:6px;
          padding:9px; font:inherit; cursor:pointer; }
 .err { color:#ff6b6b; margin-bottom:10px; font-size:13px; }
</style>
<form method="post" action="/login">
  <h1>yabranked admin</h1>
  __ERROR__
  <input type="password" name="password" placeholder="password" autofocus autocomplete="current-password">
  <button>Log in</button>
</form>
"""


# --------------------------------------------------------------------------
# http


class Handler(BaseHTTPRequestHandler):
    server_version = "yabadmin"
    sys_version = ""

    # -- plumbing ---------------------------------------------------------

    @property
    def cfg(self) -> dict:
        return self.server.cfg  # type: ignore[attr-defined]

    @property
    def throttle(self) -> Throttle:
        return self.server.throttle  # type: ignore[attr-defined]

    def log_message(self, fmt, *args):  # quieter than the default
        if self.server.verbose:  # type: ignore[attr-defined]
            super().log_message(fmt, *args)

    def cookie(self) -> str | None:
        raw = self.headers.get("Cookie", "")
        for part in raw.split(";"):
            name, _, value = part.strip().partition("=")
            if name == SESSION_COOKIE:
                return value
        return None

    def authed(self) -> bool:
        return check_session(self.cookie(), self.cfg["session_secret"])

    def send(self, code: int, body: bytes, ctype: str = "text/html; charset=utf-8", extra: dict | None = None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, payload, code: int = 200):
        self.send(code, json.dumps(payload).encode(), "application/json")

    def redirect(self, to: str, extra: dict | None = None):
        self.send_response(HTTPStatus.SEE_OTHER)
        self.send_header("Location", to)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()

    def body_params(self) -> dict[str, str]:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode() if length else ""
        return {k: v[0] for k, v in parse_qs(raw).items()}

    def client_ip(self) -> str:
        return self.client_address[0]

    # -- routes -----------------------------------------------------------

    def do_GET(self):  # noqa: N802
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)

        if path == "/login":
            return self.send(200, LOGIN_PAGE.replace("__ERROR__", "").encode())

        if not self.authed():
            if path.startswith("/api/") or path.startswith("/stream/"):
                return self.send_json({"error": "not authenticated"}, 401)
            return self.redirect("/login")

        token = csrf_token(self.cookie() or "", self.cfg["session_secret"])

        if path == "/":
            return self.send(200, PAGE.replace("__CSRF__", token).encode())
        if path == "/api/status":
            return self.send_json(host_status())
        if path == "/api/journal":
            lines = min(int(query.get("lines", ["500"])[0] or 500), 20000)
            return self.send_json({"text": journal(lines, (query.get("q") or [""])[0] or None)})
        if path == "/api/matches":
            return self.send_json(match_logs())
        if path == "/api/match":
            match_id = (query.get("id") or [""])[0]
            return self.send_json({"text": match_log_text(match_id, (query.get("q") or [""])[0] or None)})
        if path == "/api/live":
            return self.send_json(live_containers())
        if path == "/api/container":
            return self.send_json({"text": container_logs((query.get("name") or [""])[0])})
        if path == "/stream/journal":
            return self.stream_journal((query.get("q") or [""])[0] or None)

        return self.send(404, b"not found")

    def do_POST(self):  # noqa: N802
        path = urlparse(self.path).path
        params = self.body_params()

        if path == "/login":
            ip = self.client_ip()
            wait = self.throttle.locked_for(ip)
            if wait:
                body = LOGIN_PAGE.replace(
                    "__ERROR__", f'<div class="err">too many attempts — wait {wait}s</div>'
                )
                return self.send(429, body.encode())
            salt = bytes.fromhex(self.cfg["salt"])
            candidate = hash_password(params.get("password", ""), salt, self.cfg.get("iterations", KDF_ITERATIONS))
            if hmac.compare_digest(candidate, self.cfg["password_hash"]):
                self.throttle.succeed(ip)
                audit(ip, "login", "ok")
                session = issue_session(self.cfg["session_secret"])
                cookie = (f"{SESSION_COOKIE}={session}; HttpOnly; SameSite=Strict; Path=/; "
                          f"Max-Age={SESSION_TTL_SECONDS}")
                return self.redirect("/", {"Set-Cookie": cookie})
            self.throttle.fail(ip)
            audit(ip, "login", "failed")
            body = LOGIN_PAGE.replace("__ERROR__", '<div class="err">wrong password</div>')
            return self.send(401, body.encode())

        if not self.authed():
            return self.send_json({"error": "not authenticated"}, 401)

        expected = csrf_token(self.cookie() or "", self.cfg["session_secret"])
        if not hmac.compare_digest(params.get("csrf", ""), expected):
            return self.send_json({"error": "bad csrf token"}, 403)

        if path == "/logout":
            return self.redirect("/login", {"Set-Cookie": f"{SESSION_COOKIE}=; Path=/; Max-Age=0"})

        if path == "/action/teardown":
            name = params.get("name", "")
            if not valid_container(name):
                return self.send_json({"message": "invalid container name"}, 400)
            audit(self.client_ip(), "teardown", name)
            out = run(["docker", "rm", "-f", name], timeout=60).strip()
            return self.send_json({"message": f"tore down {name}\n{out}"})

        return self.send(404, b"not found")

    # -- server-sent events ------------------------------------------------

    def stream_journal(self, pattern: str | None):
        try:
            rx = re.compile(pattern, re.IGNORECASE) if pattern else None
        except re.error:
            return self.send_json({"error": "bad pattern"}, 400)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()

        proc = subprocess.Popen(
            ["journalctl", "-u", UNIT, "-n", "50", "-f", "-o", "short-iso"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
        )
        try:
            assert proc.stdout is not None
            for line in proc.stdout:
                line = line.rstrip("\n")
                if rx and not rx.search(line):
                    continue
                self.wfile.write(f"data: {line}\n\n".encode())
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            # The browser closing the tab is the ordinary way this ends, so the
            # child has to be killed here or every visit leaks a journalctl.
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()


def cmd_serve(args) -> None:
    cfg = load_config()
    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    server.cfg = cfg  # type: ignore[attr-defined]
    server.throttle = Throttle()  # type: ignore[attr-defined]
    server.verbose = args.verbose  # type: ignore[attr-defined]
    server.daemon_threads = True
    print(f"yabranked admin on http://{args.bind}:{args.port} (unit {UNIT}, logs {MATCH_LOG_DIR})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("set-password").set_defaults(func=cmd_set_password)

    serve = sub.add_parser("serve")
    # Default binds to every interface, which on this host means the LAN and
    # Tailscale — not the internet: nothing forwards a port to it and the
    # cloudflared tunnels point at the backend, not here.
    serve.add_argument("--bind", default="0.0.0.0")
    serve.add_argument("--port", type=int, default=8091)
    serve.add_argument("--verbose", action="store_true")
    serve.set_defaults(func=cmd_serve)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
