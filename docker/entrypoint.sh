#!/bin/sh
set -eu

# Accept the EULA on behalf of the operator (required for any MC server).
echo "eula=true" > eula.txt

# server.properties is generated fresh each boot from the environment the
# orchestrator injects; the world seed pins world generation for the match.
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
EOF

exec java ${JVM_ARGS} -jar fabric-server.jar nogui
