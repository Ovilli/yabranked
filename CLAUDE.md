# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```sh
./gradlew test                                  # all unit + API tests
./gradlew :backend:test                         # one module
./gradlew :backend:test --tests '*MatchServiceTest*'          # one class
./gradlew :backend:test --tests '*MatchServiceTest.settle*'   # one test
./gradlew :backend:run --args="--fake-auth"     # backend on :8080, no Mojang verify
./gradlew :backend:runMock                      # 2 fake players queue + match (needs a --fake-auth backend)
./gradlew :client:runClient                     # dev client, username AliceDev, runDir client/run
./gradlew :client:runClient2                    # second instance, BobDev, runDir client/run2
./gradlew :agent:build :backend:installDist     # artifacts for the Docker match-server flow
```

Match-server image (needs the YAB repo checked out at `../bingo`, override with `$YAB_REPO`):

```sh
(cd ../bingo && ./gradlew :api:publishToMavenLocal :mc26.2:build)
./gradlew :agent:build && docker/fetch-mods.sh && docker build -t yabranked-match docker/
```

`agent` depends on `me.jfenn.bingo:api` from **mavenLocal** — it will not compile until the YAB repo has published it.

### Toolchains

`proto` and `backend` target JVM 21; `agent` and `client` target JVM 25 (MC 26.2 requires it), so the **Gradle JVM itself must be Java 25**. `gradle.properties` pins `org.gradle.java.home` to an absolute path under `/home/ovilli/.gradle/jdks/…` — on any other machine that line has to be repointed or removed.

## Architecture

Four Gradle modules, one shared wire model:

- **`proto`** — `dev.yabranked.proto.Model.kt`, every DTO on every wire (HTTP + queue WebSocket + agent reports). Plain library, no Minecraft deps.
- **`backend`** — Ktor/Netty service: auth, matchmaking, rating, match records, orchestration.
- **`agent`** — server-side Fabric mod that runs *inside* an ephemeral match container and drives one YAB game.
- **`client`** — client-side Fabric mod: title-screen "Ranked" button and all ranked UI.

### Match lifecycle (the path worth knowing)

1. Client `POST /v1/auth/session` with a Mojang `joinServer` serverId → backend `hasJoined` check (`auth/MojangSessionVerifier.kt`) → bearer token in the in-memory `TokenRegistry` (`api/Api.kt`).
2. Client opens `WS /v1/queue?token=…` and sends `JoinQueue`. `QueueService` ticks `MatchmakingQueue` once a second under a `Mutex`; the queue widens each player's MMR band by `bandPerSecond` the longer they wait, and only pairs players whose `MatchFormat` matches exactly.
3. `MatchService.createMatch` writes a `PENDING` `MatchRecord` with a per-match `serverToken`, world seed and card seed, then fires `onMatchCreated`.
4. `MatchOrchestrator` (only when `YABRANKED_ORCHESTRATE=1`) `docker run`s one `yabranked-match` container per match, passing everything as `YABRANKED_*` env (`orchestrator/ContainerRuntime.kt`). Without it, `Main.kt` registers a stub listener that marks matches ready at `pending.invalid:25565` so the queue flow stays testable.
5. Inside the container, `AgentConfig.fromEnv` reads that env — **missing any required variable makes the agent inert**, which is what keeps the mod harmless on a normal server. The agent configures YAB, gates joins to the two matched UUIDs, starts the game, then `POST /v1/internal/matches/ready`.
6. The WebSocket handler polls the match record until it is `ACTIVE` with a `serverAddress`, then pushes `MatchFound`; the client auto-connects.
7. The agent reports the outcome to `POST /v1/internal/matches/result`, authenticated by the per-match `serverToken`. `MatchService.settle` applies Elo, updates stats, evaluates achievements, and fires `onMatchSettled` — which is also what tears the container down.

Both internal endpoints authenticate with the match's `serverToken` compared via `MessageDigest.isEqual`; the orchestrator is the only component that ever sees it.

### Storage

`store/Stores.kt` defines `PlayerStore` / `MatchStore` / `ReportStore` / `AchievementStore` plus in-memory implementations; `store/PostgresStores.kt` implements the same interfaces. `Main.kt` picks Postgres only when `YABRANKED_DATABASE_URL` is set, otherwise in-memory (state lost on restart).

Consequences to keep in mind:
- **Every test except `store/PostgresStoreTest.kt` runs against the in-memory stores**, so SQL paths are barely covered. `PostgresStoreTest` spins up `postgres:16-alpine` via `docker run` and `assumeTrue`-skips silently when Docker is absent.
- Schema changes go in `backend/src/main/resources/schema.sql`, which `Database.migrate()` executes **whole, as one statement, on every startup**. There is no migration versioning — new columns are appended as `ALTER TABLE … ADD COLUMN IF NOT EXISTS` at the bottom of the file, and both the `CREATE TABLE` block and the `ALTER` block must be updated together.
- Ratings are season-scoped (`season_stats` keyed `(uuid, season)`); `SeasonService` holds the current season number and persists it through `PostgresSettingsStore`. It only advances via `POST /v1/admin/seasons/advance`.

### Rating

`rating/RatingSystem.kt` is the interface; `EloRatingSystem` is the only implementation (initial 1000, K=80 during the 5 placement matches, K=32 after, floor 0). `rating/Tier.kt` turns a rating into the `"Gold II"` display string the client parses back apart. Note `placementMatches = 5` is declared independently in both `MatchService` and `EloRatingSystem`.

### Client ↔ proto coupling

`:proto`'s classes are **flattened into the client mod jar** by the custom `bundledProto` configuration in `client/build.gradle.kts` — Fabric JiJ only loads nested jars that are themselves mods, and proto is a plain library. Adding a proto type therefore needs no build change, but the client jar must be rebuilt for the client to see it.

Every module that (de)serializes must configure Json identically: `ignoreUnknownKeys = true` and `classDiscriminator = "type"`. That instance is re-declared per module (`api/Api.kt`, `client/BackendClient.kt`, `agent/…`) rather than shared — change one, change all.

Backward compatibility rests entirely on `ignoreUnknownKeys` plus default values on new fields, and on the `clientVersion` gate: setting `YABRANKED_MIN_CLIENT_VERSION` makes `/v1/auth/session` answer 426 to older clients. Adding a `MatchFormat` constant or a `QueueServerMessage` subtype is a **breaking change for existing clients** — unknown enum/subtype names fail to decode.

All `BackendClient` methods block. Call them from `YabRankedClient.workers` (a single-thread executor) and hop back with `minecraft.execute { … }`; never from the render thread.

## Environment variables

The backend reads everything ad hoc via `System.getenv` in `Main.kt` — that file is the authoritative list. Two inconsistencies that bite:

- Booleans are not uniform: `YABRANKED_FAKE_AUTH` / `_ORCHESTRATE` / `_SEED` are true only when `== "1"`, while `YABRANKED_ONLINE_MODE` / `_HOST_NETWORK` are true unless `== "false"` (so `YABRANKED_ONLINE_MODE=0` *enables* online mode).
- `--fake-auth` (or `YABRANKED_FAKE_AUTH=1`) both disables Mojang verification and enables `GET /v1/debug/matches/{id}/token`. Nothing stops it being combined with a real `YABRANKED_DATABASE_URL`.

`YABRANKED_SEED=1` loads the fixture ladder from `dev/Seeder.kt` for UI work; it refuses to run against Postgres.

Client backend URL comes from `-Dyabranked.url`, then `$YABRANKED_URL`, defaulting to `http://localhost:8080`.

See `README.md` for the full local 2-client match walkthrough.
