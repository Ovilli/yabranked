# YAB Ranked

Competitive ranked ladder for [Yet Another Bingo](https://gitlab.com/horrificdev/bingo),
modeled on MCSR Ranked. See `RANKED_ARCHITECTURE.md` in the YAB repo for the
Phase 0 architecture document.

## Modules

- `proto` — shared kotlinx.serialization DTOs used by the backend, the
  match-server agent mod, and the client mod.
- `backend` — Ktor service: Mojang session auth, matchmaking queue with
  MMR-band expansion, Elo rating engine (behind a `RatingSystem` interface),
  match records, leaderboard. Phase 1 runs on in-memory stores; the Postgres
  schema lives in `backend/src/main/resources/schema.sql`.

- `agent` — server-side Fabric mod (MC 26.2) for match servers. Inert without
  the `YABRANKED_*` environment; otherwise it configures the YAB game (lockout,
  first-win, time limit, card seed), gates joins to the two matched players,
  auto-assigns teams, starts the game, and reports the result to the backend.
  Handles no-shows (void) and mid-match disconnects (forfeit after 120 s).
- `docker/` — ephemeral match-server image: Fabric 26.2 + YAB + agent.
  `docker/fetch-mods.sh` stages the jars, then
  `docker build -t yabranked-match docker/`.
- Orchestration lives in `backend` (`dev.yabranked.backend.orchestrator`):
  enable with `YABRANKED_ORCHESTRATE=1`; one Docker container per match
  (host networking, ports 25600+), torn down when the match settles or is
  reaped after a ready timeout.

- `client` — client-side Fabric mod (MC 26.2). "Ranked" button on the title
  screen → ranked screen with login (real Mojang `joinServer` handshake),
  queue toggle with live state, auto-connect on match found, post-match MMR
  delta, and a leaderboard view. Backend URL defaults to `localhost:8080`;
  override with `-Dyabranked.url=…` or `YABRANKED_URL`. The backend enforces
  a minimum client version when `YABRANKED_MIN_CLIENT_VERSION` is set
  (HTTP 426 → "update required" in the UI).
  Dev launch: `./gradlew :client:runClient`.

## Development

```sh
./gradlew test                                 # unit + API tests
./gradlew :backend:run --args="--fake-auth"    # local backend on :8080, no Mojang verify
./gradlew :backend:runMock                     # mock client: 2 fake players queue + match
```

`--fake-auth` (or `YABRANKED_FAKE_AUTH=1`) accepts any username without
Mojang session verification — local development only.

## Local 2-client match test (Phase 2 acceptance)

1. Start Docker (`sudo systemctl start docker`).
2. Build the pieces:
   ```sh
   (cd ../bingo && ./gradlew :api:publishToMavenLocal :mc26.2:build)
   ./gradlew :agent:build :backend:installDist
   docker/fetch-mods.sh
   docker build -t yabranked-match docker/
   ```
3. Run the backend with orchestration, offline mode for local clients.
   On **Docker Desktop** (VM-based; `systemctl --user start docker-desktop`)
   also set `YABRANKED_HOST_NETWORK=false` — containers then publish ports and
   reach the backend via host.docker.internal:
   ```sh
   YABRANKED_FAKE_AUTH=1 YABRANKED_ORCHESTRATE=1 YABRANKED_ONLINE_MODE=false \
   YABRANKED_HOST_NETWORK=false \
     ./backend/build/install/backend/bin/backend
   ```
4. Two options to queue:
   - **Client mod (preferred):** run two game instances with the `client` mod
     (`./gradlew :client:runClient`), press "Ranked" on the title screen, log
     in, queue on both. On match found each client auto-connects.
   - **Mock:** `YABRANKED_MOCK_PLAYER_A/B=<usernames>` with `runMock`, then
     direct-connect both clients to the `match_found` address (fake auth uses
     the vanilla offline-UUID formula, so usernames must match exactly).
5. The agent assigns red/blue, starts lockout (first to 13 items), reports the
   winner, and the container is removed.

## API sketch (v1)

- `POST /v1/auth/session` `{username, serverId}` → `{token, profile}` (Mojang `hasJoined` verification)
- `WS /v1/queue?token=…` — `join_queue`/`leave_queue`; server pushes `queue_state`, `match_found`
- `GET /v1/players/{uuid}` → profile
- `GET /v1/leaderboard?limit=25`
- `POST /v1/internal/matches/result` — agent-only, `Authorization: Bearer <per-match server token>`
