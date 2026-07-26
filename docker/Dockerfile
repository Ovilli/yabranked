# YAB Ranked ephemeral match server.
# Build context expects jars staged in docker/mods/ (see fetch-mods.sh):
#   - bingo-<version>+mc26.2.jar   (YAB, built from the YAB repo)
#   - yabranked-agent-<version>.jar
#   - fabric-api-<version>.jar
FROM eclipse-temurin:25-jre

ARG MINECRAFT_VERSION=26.2
ARG FABRIC_LOADER_VERSION=0.19.3
ARG FABRIC_INSTALLER_VERSION=1.1.0

WORKDIR /server

ADD https://meta.fabricmc.net/v2/versions/loader/${MINECRAFT_VERSION}/${FABRIC_LOADER_VERSION}/${FABRIC_INSTALLER_VERSION}/server/jar \
    /server/fabric-server.jar

# Pre-fetch the Minecraft server jar and every library into the image.
#
# fabric-server.jar is only a launcher: left to itself it resolves and
# downloads all of that on first *run*, i.e. once per match container, which
# added minutes to provisioning and regularly blew past the orchestrator's
# ready timeout (MatchService.PROVISION_TIMEOUT_SECONDS) — the match was voided
# before the server ever finished booting.
#
# Running it with eula=false is what makes this download-only: the launcher
# fetches everything, hands off to the server, and the server exits on the
# unaccepted EULA before generating a world. The non-zero exit is expected.
RUN echo "eula=false" > eula.txt && \
    (java -jar fabric-server.jar nogui || true) && \
    rm -f eula.txt server.properties && \
    test -d libraries || (echo "pre-fetch produced no libraries/ — launcher layout changed" && exit 1)

COPY mods/ /server/mods/
COPY entrypoint.sh /server/entrypoint.sh
RUN chmod +x /server/entrypoint.sh

# match servers are ephemeral: world lives and dies with the container
ENV SERVER_PORT=25565 \
    ONLINE_MODE=true \
    JVM_ARGS="-Xms1G -Xmx2G"

EXPOSE 25565
ENTRYPOINT ["/server/entrypoint.sh"]
