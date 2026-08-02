#!/bin/sh
# Read the production host's logs from a development machine.
#
# Everything here goes over ssh and needs no sudo: the backend runs as a
# systemd user-readable unit, and the match logs the backend copies out of each
# container before removing it are plain files owned by the deploy user.
#
# There are three separate places a match's story is written, and only the
# first two survive the container:
#
#   backend   the orchestrator's view — provisioning, ready, settle, teardown
#   match     what happened *inside* one container, kept by YABRANKED_MATCH_LOG_DIR
#   live      docker logs of a match container that is still running
#
# Usage:
#   deploy/logs.sh backend [-f] [-n LINES] [PATTERN]
#   deploy/logs.sh matches
#   deploy/logs.sh match <match-id-prefix> [-f] [PATTERN]
#   deploy/logs.sh live [-f]
#   deploy/logs.sh pull [DEST_DIR]
#   deploy/logs.sh status
#
# Host: $YABRANKED_HOST, or a user@host line in deploy/.host (untracked). This
# repository is public, so the deployment's address is not baked in here.
set -eu

HOST_FILE="$(dirname "$0")/.host"
HOST="${YABRANKED_HOST:-}"
if [ -z "$HOST" ] && [ -f "$HOST_FILE" ]; then
    HOST=$(head -1 "$HOST_FILE" | tr -d '[:space:]')
fi
if [ -z "$HOST" ]; then
    echo "no host: set YABRANKED_HOST=user@host, or put it in $HOST_FILE" >&2
    exit 1
fi
UNIT="${YABRANKED_UNIT:-yabranked-backend}"
MATCH_LOG_DIR="${YABRANKED_MATCH_LOG_DIR:-/var/lib/yabranked/match-logs}"
CONTAINER_PREFIX="yabranked-match-"

usage() {
    sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-1}"
}

# ssh with no pseudo-tty: this is piped output, and a tty would line-wrap it.
remote() {
    ssh -o BatchMode=yes -o ConnectTimeout=10 "$HOST" "$@"
}

cmd_backend() {
    follow=""
    lines=300
    pattern=""
    while [ $# -gt 0 ]; do
        case "$1" in
            -f|--follow) follow="-f"; shift ;;
            -n) lines="$2"; shift 2 ;;
            -*) echo "unknown option $1" >&2; exit 1 ;;
            *) pattern="$1"; shift ;;
        esac
    done
    if [ -n "$pattern" ]; then
        remote "journalctl -u '$UNIT' -n '$lines' --no-pager $follow | grep --line-buffered -E '$pattern'"
    else
        remote "journalctl -u '$UNIT' -n '$lines' --no-pager $follow"
    fi
}

cmd_matches() {
    remote "ls -lt --time-style=long-iso '$MATCH_LOG_DIR' 2>/dev/null | awk 'NR>1 {print \$6, \$7, \$5, \$8}'" \
        || { echo "no match logs on $HOST — is YABRANKED_MATCH_LOG_DIR set there?" >&2; exit 1; }
}

# Match ids are uuids and nobody types one; a prefix is resolved on the host,
# and an ambiguous one is an error rather than a guess at which match you meant.
cmd_match() {
    [ $# -ge 1 ] || usage
    prefix="$1"; shift
    follow=""
    pattern=""
    while [ $# -gt 0 ]; do
        case "$1" in
            -f|--follow) follow="1"; shift ;;
            -*) echo "unknown option $1" >&2; exit 1 ;;
            *) pattern="$1"; shift ;;
        esac
    done

    matched=$(remote "ls '$MATCH_LOG_DIR' 2>/dev/null | grep '^$prefix' || true")
    count=$(printf '%s\n' "$matched" | grep -c . || true)
    if [ "$count" -eq 0 ]; then
        echo "no match log starting with '$prefix' on $HOST" >&2
        exit 1
    elif [ "$count" -gt 1 ]; then
        echo "'$prefix' matches several logs:" >&2
        printf '%s\n' "$matched" >&2
        exit 1
    fi

    reader="cat"
    [ -n "$follow" ] && reader="tail -n 200 -f"
    if [ -n "$pattern" ]; then
        remote "$reader '$MATCH_LOG_DIR/$matched' | grep --line-buffered -E '$pattern'"
    else
        remote "$reader '$MATCH_LOG_DIR/$matched'"
    fi
}

# A match still being played has no file yet — the backend only copies the log
# out just before `docker rm -f`, because a log read after the remove is no log.
cmd_live() {
    follow=""
    [ "${1:-}" = "-f" ] && follow="-f"
    names=$(remote "docker ps --format '{{.Names}}' --filter name='$CONTAINER_PREFIX'")
    if [ -z "$names" ]; then
        echo "no match containers running on $HOST"
        exit 0
    fi
    count=$(printf '%s\n' "$names" | grep -c .)
    if [ "$count" -gt 1 ] && [ -n "$follow" ]; then
        echo "several matches running; naming one:" >&2
        printf '%s\n' "$names" >&2
        exit 1
    fi
    for name in $names; do
        echo "=== $name ==="
        remote "docker logs --tail 200 $follow '$name'"
    done
}

# Copy every match log down so it can be grepped across matches locally —
# which is the shape most questions about a bad match actually have.
cmd_pull() {
    dest="${1:-./match-logs}"
    mkdir -p "$dest"
    scp -q "$HOST:$MATCH_LOG_DIR/*.log" "$dest/" 2>/dev/null \
        || { echo "nothing to pull from $HOST:$MATCH_LOG_DIR" >&2; exit 1; }
    echo "pulled into $dest:"
    ls -lt "$dest"
}

cmd_status() {
    remote 'echo "=== backend ==="; systemctl is-active '"$UNIT"' || true
        echo "=== containers ==="; docker ps --format "{{.Names}}\t{{.Image}}\t{{.Status}}"
        echo "=== match image ==="; docker images yabranked-match --format "{{.ID}} {{.CreatedAt}}"
        echo "=== disk ==="; df -h / | tail -1
        echo "=== replays ==="; du -sh /var/lib/yabranked/replays 2>/dev/null || true'
}

[ $# -ge 1 ] || usage 0
sub="$1"; shift
case "$sub" in
    backend) cmd_backend "$@" ;;
    matches) cmd_matches "$@" ;;
    match)   cmd_match "$@" ;;
    live)    cmd_live "$@" ;;
    pull)    cmd_pull "$@" ;;
    status)  cmd_status "$@" ;;
    -h|--help|help) usage 0 ;;
    *) echo "unknown command '$sub'" >&2; usage ;;
esac
