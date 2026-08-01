#!/bin/sh
# Stages the mod jars the match-server image needs into docker/mods/.
# Run from the repo root after building the agent and YAB:
#   (in YAB repo)  ./gradlew :mc26.2:build
#   (here)         ./gradlew :agent:build && docker/fetch-mods.sh
set -eu

YAB_REPO="${YAB_REPO:-../bingo}"
FABRIC_API_VERSION="${FABRIC_API_VERSION:-0.156.0+26.2}"
MODS_DIR="$(dirname "$0")/mods"

mkdir -p "$MODS_DIR"
rm -f "$MODS_DIR"/*.jar

cp "$YAB_REPO"/mc26.2/build/libs/bingo-*+mc26.2.jar "$MODS_DIR"/ 2>/dev/null \
    || { echo "YAB jar not found — build it with ./gradlew :mc26.2:build in $YAB_REPO"; exit 1; }
# drop the sources jar if the glob picked it up
rm -f "$MODS_DIR"/*-sources.jar

cp "$(dirname "$0")"/../agent/build/libs/agent-*.jar "$MODS_DIR"/ 2>/dev/null \
    || { echo "agent jar not found — build it with ./gradlew :agent:build"; exit 1; }
rm -f "$MODS_DIR"/agent-*-sources.jar

FABRIC_API_JAR="$MODS_DIR/fabric-api-$FABRIC_API_VERSION.jar"
if [ ! -f "$FABRIC_API_JAR" ]; then
    echo "downloading fabric-api $FABRIC_API_VERSION"
    curl -fsSL -o "$FABRIC_API_JAR" \
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FABRIC_API_VERSION/fabric-api-$FABRIC_API_VERSION.jar"
fi

echo "staged mods:"
ls -la "$MODS_DIR"
