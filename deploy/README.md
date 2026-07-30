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

## 4. Up

```sh
docker compose up -d --build
curl https://<your hostname>/health     # {"status":"up"}
```

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
