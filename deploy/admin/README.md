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
ssh "$HOST" 'setsid nohup python3 ~/yabranked-admin/app.py serve \
    >> ~/yabranked-admin/console.log 2>&1 &'
```

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

Teardown is the only mutating action. It is a POST, it carries a CSRF token, it
asks for confirmation, and it is written to `~/.yabranked-admin/audit.log`
along with every login attempt.

## The part to be honest about

The app talks to the docker socket, and docker socket access on this host is
root-equivalent. The login is what stands in front of that, so:

- keep it off the tunnel — no cloudflared ingress should ever point here;
- pick a real password, since five wrong attempts lock an IP out for five
  minutes and that is the only rate limit there is;
- it serves plain HTTP. On the LAN and over Tailscale that is a considered
  trade; over anything else it would not be.
