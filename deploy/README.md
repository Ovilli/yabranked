# Running YAB Ranked on your own hardware

Everything on one host: the backend, and the match containers it starts. Written
for a Raspberry Pi 5, which is fast enough for both.

A match container has **180 seconds** from `docker run` to reporting ready, or the
backend voids the match. On a desktop that takes ~18s. A Pi is slower, and an SD
card slower again — if matches start voiding, that budget is the first thing to
look at.

## 1. Build the match image (once, and after every agent change)

The image is ARM here and x86 on your desktop, so it has to be built **on the
Pi**. The jars inside it are platform-independent Java, so build those on the
desktop — where Gradle, loom and a Minecraft download already work — and copy
them over:

```sh
# on the desktop
./gradlew :agent:build && docker/fetch-mods.sh
rsync -a docker/ pi@raspberrypi.local:~/yabranked/docker/

# on the Pi
cd ~/yabranked && docker build -t yabranked-match docker/
```

Building the agent on the Pi instead would work, but pulls Minecraft and a JDK 25
toolchain for no benefit.

## 2. Configure

```sh
cd deploy
cp .env.example .env
$EDITOR .env
```

The two that matter: `YABRANKED_DATABASE_URL` — Neon's free tier needs no card,
and without it every player, rating and match is lost on restart — and
`PUBLIC_HOSTNAME` / `YABRANKED_PUBLIC_HOST`, which must be a DDNS name rather
than an IP, because most consumer ISPs move you onto a new address daily.

## 3. Open the ports

Forward to the Pi's LAN address on the router:

| Port          | For                                              |
|---------------|--------------------------------------------------|
| `80`          | Let's Encrypt's HTTP challenge — needed for TLS  |
| `443`         | The backend API                                  |
| `25600-25649` | Match servers. Clients connect straight to these |

Minecraft speaks raw TCP directly to `host:port`, so the match ports cannot be
put behind a proxy or an HTTP tunnel.

### If you cannot forward ports

Two tunnels, because no single one does both jobs. The API is HTTP and goes
through a Cloudflare Tunnel; Minecraft is raw TCP and cannot, so match servers
go through playit.gg. Putting the match ports through Cloudflare needs Spectrum,
which is enterprise.

**API — cloudflared.** `cloudflared tunnel create <name>` prints a tunnel id and
writes a credentials JSON; that JSON is the secret and stays off the repo.

```sh
sudo mkdir -p /etc/cloudflared-yabranked
sudo cp deploy/cloudflared/config.yml.example /etc/cloudflared-yabranked/config.yml
sudo $EDITOR /etc/cloudflared-yabranked/config.yml     # tunnel id + hostname
cloudflared tunnel route dns <name> <your hostname>
sudo cp deploy/systemd/cloudflared-yabranked.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now cloudflared-yabranked
```

**Match servers — playit.gg.** The agent comes from its own apt repo and brings
its own `playit.service`, so there is no unit here to copy — installing the
package is the whole of it. Its secret lives in `/etc/playit/playit.toml`
(`0600`, owned by `playit`) and is likewise not in the repo.

Create one **Minecraft Java** tunnel per concurrent match you want, each
forwarding to `127.0.0.1:2560N`, then list them in `YABRANKED_PORT_MAP`. That
tunnel type publishes a `_minecraft._tcp` SRV record, which is why a bare
hostname with no port works. Two things to know:

- **The number of entries is the hard cap on concurrent matches.** Setting
  `YABRANKED_PORT_MAP` replaces the port range entirely, so only mapped ports
  are ever allocated.
- **Verify a tunnel before trusting it.** Bind `nc -l 127.0.0.1 25600` on the
  host and dial the public address from off-network. playit routes by edge IP
  and port, so a mistyped name can still reach *somebody's* tunnel — see the
  warning above `YABRANKED_PORT_MAP` in `.env.example`.

## 4. Up

```sh
docker compose up -d --build
curl https://<your hostname>/health     # {"status":"up"}
```

## Running the backend natively instead (systemd)

`systemd/` holds the unit this project's own deployment actually runs, because
on that host the JVM SIGSEGVs reading its own module image inside Docker while
the identical distribution starts in a quarter of a second on the host JVM. It
is an alternative to the backend service in `docker-compose.yml`, not an
addition — run one or the other. Match containers are still Docker either way.

```sh
./gradlew :backend:installDist                        # on the desktop
# copy backend/build/install/backend to /opt/yabranked/app on the host, then:
sudo cp -r deploy/systemd/yabranked-backend.service* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now yabranked-backend
```

`User=` and the `/opt/yabranked/app` path are baked into the unit; change both
if yours differ. `EnvironmentFile=/etc/yabranked/backend.env` takes the same
keys as `.env.example` — the duplicate-key warning at the top of that file
applies to `EnvironmentFile` too, and cost a real afternoon here.

The drop-in exists because the JVM answers SIGTERM with 143, which systemd
otherwise reports as a failed unit after every ordinary restart. It is a
separate file rather than a line in the unit so that replacing the unit does
not silently drop it.

## Notes

- **The backend mounts the Docker socket**, which is root-equivalent access to
  this machine. That is what an orchestrator is; it is also why this host should
  not be sharing duties with anything you care about.
- **`YABRANKED_HOST_NETWORK=false`** is set deliberately. The default is host
  networking, which would put a public Minecraft server on your LAN's network
  stack rather than behind a bridge with one published port.
- **Replays wear out SD cards.** A match writes tens of megabytes. Point
  `REPLAY_HOST_DIR` at a USB SSD, shorten retention, or accept the card is
  consumable.
- Publishing the mod: set `YABRANKED_MIN_CLIENT_VERSION` so old jars get a clean
  426 rather than confusing failures, and make sure the client's baked
  `DEFAULT_BACKEND_URL` matches this host.
