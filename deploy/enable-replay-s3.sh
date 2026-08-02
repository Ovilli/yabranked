#!/bin/sh
# Point replay packet storage back at MinIO, and carry the local recordings over.
#
# Run this ON the host. It has three parts and they are separable, because only
# the last one needs sudo:
#
#   bucket    ensure the bucket exists                        (docker + mc)
#   migrate   copy <replay dir>/<match>/<n>.yabr into S3      (docker + mc)
#   env       add YABRANKED_REPLAY_S3_* to the backend env    (sudo)
#
#   ./enable-replay-s3.sh bucket
#   ./enable-replay-s3.sh migrate [--dry-run]
#   sudo -E ./enable-replay-s3.sh env       # then restarts the backend
#   ./enable-replay-s3.sh verify
#
# The two layouts line up exactly, which is the only reason a migration is a
# copy rather than a re-record: FileReplayBlobStore writes one file per stream
# at `<match>/<index>.yabr`, and S3ReplayBlobStore addresses each appended chunk
# by the offset it starts at, zero-padded to 20 digits, under
# `replays/<match>/<index>/`. A whole file is the chunk that starts at 0.
#
# Credentials are read from ~/.minio-creds (MINIO_ADMIN_USER / MINIO_ADMIN_PASS)
# and are never passed on a command line — mc gets them through an --env-file
# that is created 0600 and removed on exit, and the backend env file is written
# from stdin.
set -eu

CREDS="${MINIO_CREDS:-$HOME/.minio-creds}"
ENDPOINT="${MINIO_ENDPOINT:-http://localhost:9000}"
BUCKET="${YABRANKED_REPLAY_S3_BUCKET:-yabranked-replays}"
REGION="${YABRANKED_REPLAY_S3_REGION:-auto}"
REPLAY_DIR="${YABRANKED_REPLAY_DIR:-/var/lib/yabranked/replays}"
BACKEND_ENV="${YABRANKED_BACKEND_ENV:-/etc/yabranked/backend.env}"
UNIT="${YABRANKED_UNIT:-yabranked-backend}"
MC_IMAGE="${MC_IMAGE:-quay.io/minio/mc:latest}"
KEY_PREFIX="replays"

ENVFILE=""
cleanup() { [ -n "$ENVFILE" ] && rm -f "$ENVFILE"; }
trap cleanup EXIT INT TERM

load_creds() {
    # sudo -E keeps HOME, but be explicit: this file belongs to the login user.
    if [ ! -r "$CREDS" ] && [ -n "${SUDO_USER:-}" ]; then
        CREDS="$(getent passwd "$SUDO_USER" | cut -d: -f6)/.minio-creds"
    fi
    [ -r "$CREDS" ] || { echo "cannot read $CREDS — set \$MINIO_CREDS" >&2; exit 1; }
    # shellcheck disable=SC1090
    . "$CREDS"
    [ -n "${MINIO_ADMIN_USER:-}" ] && [ -n "${MINIO_ADMIN_PASS:-}" ] \
        || { echo "$CREDS has no MINIO_ADMIN_USER / MINIO_ADMIN_PASS" >&2; exit 1; }
}

# Credentials reach the container through an --env-file and are turned into an
# alias inside it. The obvious `MC_HOST_yab=http://user:pass@host` does not work
# here: mc does not percent-decode that URL, so a password with punctuation in
# it — this one has four such characters — fails as a signature mismatch, which
# reads exactly like a wrong password.
write_envfile() {
    ENVFILE=$(mktemp)
    chmod 600 "$ENVFILE"
    {
        printf 'MC_ENDPOINT=%s\n' "$ENDPOINT"
        printf 'MC_USER=%s\n' "$MINIO_ADMIN_USER"
        printf 'MC_PASS=%s\n' "$MINIO_ADMIN_PASS"
    } > "$ENVFILE"
}

mc() {
    docker run --rm --network host --env-file "$ENVFILE" \
        -v "$REPLAY_DIR":/data:ro --entrypoint sh "$MC_IMAGE" -c \
        'mc alias set yab "$MC_ENDPOINT" "$MC_USER" "$MC_PASS" >/dev/null || exit 1
         exec mc "$@"' sh "$@"
}

cmd_bucket() {
    load_creds
    write_envfile
    mc ls yab >/dev/null || { echo "cannot reach MinIO at $ENDPOINT — is the container up?" >&2; exit 1; }
    mc mb --ignore-existing "yab/$BUCKET"
    echo "bucket yab/$BUCKET ready"
}

cmd_migrate() {
    dry=""
    [ "${1:-}" = "--dry-run" ] && dry="1"
    load_creds
    write_envfile
    [ -d "$REPLAY_DIR" ] || { echo "no replay dir at $REPLAY_DIR — nothing to migrate"; return 0; }

    zeros="00000000000000000000"   # 20 digits: the offset a whole file starts at
    copied=0
    # Read the listing through the container: the replay dir is root-owned and
    # this script is not meant to need sudo for the parts before `env`.
    for rel in $(docker run --rm -v "$REPLAY_DIR":/data:ro --entrypoint sh "$MC_IMAGE" \
                    -c 'cd /data 2>/dev/null && ls */*.yabr 2>/dev/null' || true); do
        match=${rel%/*}
        file=${rel##*/}
        index=${file%.yabr}
        key="yab/$BUCKET/$KEY_PREFIX/$match/$index/$zeros"
        if [ -n "$dry" ]; then
            echo "would copy $rel -> $key"
        else
            mc cp -q "/data/$rel" "$key" >/dev/null
            echo "copied $rel"
        fi
        copied=$((copied + 1))
    done
    echo "$copied stream(s) $( [ -n "$dry" ] && echo "would be copied" || echo copied )"
    [ -n "$dry" ] || echo "local files left in place — delete them only after a replay plays back from S3"
}

cmd_env() {
    [ "$(id -u)" = "0" ] || { echo "run this one with sudo -E" >&2; exit 1; }
    load_creds
    [ -f "$BACKEND_ENV" ] || { echo "no $BACKEND_ENV" >&2; exit 1; }

    backup="$BACKEND_ENV.bak-$(date +%Y%m%d-%H%M%S)"
    cp -p "$BACKEND_ENV" "$backup"

    # Rewrite rather than append: appending a second YABRANKED_REPLAY_S3_BUCKET
    # would leave which one wins up to the parser.
    tmp=$(mktemp); chmod 600 "$tmp"
    grep -v '^YABRANKED_REPLAY_S3_' "$BACKEND_ENV" > "$tmp" || true
    {
        printf 'YABRANKED_REPLAY_S3_ENDPOINT=%s\n' "$ENDPOINT"
        printf 'YABRANKED_REPLAY_S3_BUCKET=%s\n' "$BUCKET"
        printf 'YABRANKED_REPLAY_S3_REGION=%s\n' "$REGION"
        printf 'YABRANKED_REPLAY_S3_ACCESS_KEY=%s\n' "$MINIO_ADMIN_USER"
        printf 'YABRANKED_REPLAY_S3_SECRET_KEY=%s\n' "$MINIO_ADMIN_PASS"
    } >> "$tmp"
    install -m 600 -o root -g root "$tmp" "$BACKEND_ENV"
    rm -f "$tmp"
    echo "wrote S3 settings to $BACKEND_ENV (backup: $backup)"

    systemctl restart "$UNIT"
    echo "restarted $UNIT"
    sleep 3
    cmd_verify
}

cmd_verify() {
    echo "--- what the backend says it chose ---"
    journalctl -u "$UNIT" -n 400 --no-pager \
        | grep -E "replay packet data|no YABRANKED_REPLAY" | tail -3 \
        || echo "(no replay storage line yet — it is logged once at startup)"
    echo "--- unit ---"
    systemctl is-active "$UNIT"
}

case "${1:-}" in
    bucket)  shift; cmd_bucket "$@" ;;
    migrate) shift; cmd_migrate "$@" ;;
    env)     shift; cmd_env "$@" ;;
    verify)  shift; cmd_verify "$@" ;;
    *) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
