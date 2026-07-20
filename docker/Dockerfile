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

COPY mods/ /server/mods/
COPY entrypoint.sh /server/entrypoint.sh
RUN chmod +x /server/entrypoint.sh

# match servers are ephemeral: world lives and dies with the container
ENV SERVER_PORT=25565 \
    ONLINE_MODE=true \
    JVM_ARGS="-Xms1G -Xmx2G"

EXPOSE 25565
ENTRYPOINT ["/server/entrypoint.sh"]
