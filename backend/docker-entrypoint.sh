#!/bin/sh
set -eu

PUID="${PUID:-}"
PGID="${PGID:-}"

# If root execution is explicitly requested:
if [ "$PUID" = "0" ] || [ "$PUID" = "root" ]; then
    for directory in /data /music; do
        if [ -d "$directory" ] && [ ! -L "$directory" ]; then
            chown -R 0:0 "$directory" 2>/dev/null || true
        fi
    done
    exec java -jar /app/app.jar
fi

# Dynamically adjust UID/GID of the ktv user if PUID/PGID are supplied
if [ -n "$PGID" ] && [ "$PGID" != "$(id -g ktv 2>/dev/null || echo '')" ]; then
    existing_grp="$(awk -F: -v gid="$PGID" '$3 == gid {print $1; exit}' /etc/group 2>/dev/null || true)"
    if [ -z "$existing_grp" ]; then
        delgroup ktv 2>/dev/null || true
        addgroup -g "$PGID" -S ktv 2>/dev/null || true
    fi
fi

if [ -n "$PUID" ] && [ "$PUID" != "$(id -u ktv 2>/dev/null || echo '')" ]; then
    existing_usr="$(awk -F: -v uid="$PUID" '$3 == uid {print $1; exit}' /etc/passwd 2>/dev/null || true)"
    if [ -z "$existing_usr" ]; then
        deluser ktv 2>/dev/null || true
        grp_name="${existing_grp:-ktv}"
        adduser -u "$PUID" -D -S -G "$grp_name" ktv 2>/dev/null || true
    fi
fi

RUN_UID="$(id -u ktv 2>/dev/null || echo 100)"
RUN_GID="$(id -g ktv 2>/dev/null || echo 101)"

# Bind mounts are created by Docker as root on a fresh deployment. Adjust only
# the application-owned writable directories before dropping privileges; the
# external source mount is deliberately never touched.
for directory in /data /music; do
    if [ -d "$directory" ] && [ ! -L "$directory" ]; then
        chown "$RUN_UID:$RUN_GID" "$directory" 2>/dev/null || true
    fi
done

exec su-exec "$RUN_UID:$RUN_GID" java -jar /app/app.jar

