#!/bin/sh
set -eu

# Accept the EULA on behalf of the operator (required for any MC server).
echo "eula=true" > eula.txt

# YAB lobby mode: players wait in a controlled lobby world instead of loose in
# the survival world before the game starts, and — importantly for ranked —
# GameService only spreads teams to separate spawnpoints when lobby mode is on.
mkdir -p config/yet-another-minecraft-bingo
cat > config/yet-another-minecraft-bingo/config.json <<'EOF'
{
  "server": {
    "isLobbyMode": true
  },
  "lobbyTutorialBook": false,
  "startWhenReadySeconds": null
}
EOF

# server.properties is generated fresh each boot from the environment the
# orchestrator injects; the world seed pins world generation for the match.
#
# max-tick-time=-1 disables the vanilla watchdog, and it is not optional here.
# YAB generates the teams' spawn areas up front and reads blocks from the main
# thread while doing it, so `getBlockState` blocks on chunk generation inside a
# single tick — by design. On a CPU-limited container that one tick can take
# minutes: an observed match spent 2m2s preloading 162 chunks, then blew past
# the watchdog's 60s default in `SpawnService.getTeamSpawnpoint` and was killed
# mid-setup. The server was not hung, only slow, and killing it lost the match
# for both players.
cat > server.properties <<EOF
server-port=${SERVER_PORT}
online-mode=${ONLINE_MODE}
level-seed=${YABRANKED_WORLD_SEED:-}
motd=YAB Ranked match server
max-players=10
white-list=false
enforce-secure-profile=false
spawn-protection=0
sync-chunk-writes=false
max-tick-time=-1
view-distance=${YABRANKED_VIEW_DISTANCE:-8}
simulation-distance=${YABRANKED_SIMULATION_DISTANCE:-6}
EOF

exec java ${JVM_ARGS} -jar fabric-server.jar nogui
