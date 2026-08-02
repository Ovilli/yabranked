# yabranked admin console

A single-file Python app (stdlib only) that puts the backend journal, the kept
match logs, and the live match containers behind one login, on the LAN.

It is deliberately **not** part of the backend. The backend is published to the
internet through cloudflared, so admin routes added there would be admin routes
on the internet. This binds to the host's own addresses instead — the LAN and
Tailscale — and nothing forwards a port to it.

## Install on the host

`$HOST` below is the deployment's `user@host` — the same one `deploy/logs.sh`
reads from `$YABRANKED_HOST` or `deploy/.host`. This repository is public, so
the address lives there rather than in the files.

```sh
scp deploy/admin/app.py "$HOST":~/yabranked-admin/app.py
ssh -t "$HOST" 'python3 ~/yabranked-admin/app.py set-password'
```

The password is stored as a PBKDF2-SHA256 hash (240k iterations, per-install
salt) in `~/.yabranked-admin/config.json`, mode 0600. Changing it rotates the
session secret, which logs every browser out.

## Run it

```sh
ssh "$HOST" 'YABRANKED_ADMIN_TOKEN=… setsid nohup python3 ~/yabranked-admin/app.py serve \
    >> ~/yabranked-admin/console.log 2>&1 &'
```

`YABRANKED_ADMIN_TOKEN` is the same shared secret the backend is started with,
and it is what the **Reports** tab needs: moderation state lives in the
database, so that one tab asks the backend over loopback
(`YABRANKED_BACKEND_URL`, default `http://127.0.0.1:8080`) rather than reading
the host. Everything else here works without it, and the tab says the token is
missing rather than failing blankly.

Persistence is a `@reboot` crontab line rather than a systemd unit, because
`sudo` on this host wants a password and a user unit would need lingering
enabled the same way.

Then open `http://<host>:8091` on the LAN (or the Tailscale address).

## What it can do

| Tab | Reads |
| --- | --- |
| Overview | `systemctl is-active`, disk, replay dir size, match image, containers |
| Backend log | `journalctl -u yabranked-backend`, with a regex filter and a live follow (SSE) |
| Match logs | the files the backend copies out of each container before `docker rm -f` |
| Live | running `yabranked-match-*` containers, their logs, and teardown |
| Reports | the moderation queue, over the backend's admin API |

The Reports tab defaults to **open** reports rather than all of them: a queue
that lists every accusation ever filed alongside the ones nobody has read is
how it comes to read as endless. `×N` next to an accused is how many reports
that account has ever collected — one report is noise, and the ninth is the
reason to look. Marking a report `Actioned` or `Dismissed` is also what
releases the retention hold the report put on the match recording; until this
existed, that hold could only ever be set, so every reported match's packet
capture was kept forever.

Teardown and resolving a report are the mutating actions. Both are POSTs, both
carry a CSRF token, and both are written to `~/.yabranked-admin/audit.log`
along with every login attempt. Neither bans anyone — `POST /v1/admin/bans/…`
is still a deliberate, separate call.

## The part to be honest about

The app talks to the docker socket, and docker socket access on this host is
root-equivalent. The login is what stands in front of that, so:

- keep it off the tunnel — no cloudflared ingress should ever point here;
- pick a real password, since five wrong attempts lock an IP out for five
  minutes and that is the only rate limit there is;
- it serves plain HTTP. On the LAN and over Tailscale that is a considered
  trade; over anything else it would not be.
