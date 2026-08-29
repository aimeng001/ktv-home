#!/bin/sh
set -eu

# Bind mounts are created by Docker as root on a fresh deployment.  Adjust only
# the application-owned writable directories before dropping privileges; the
# external source mount is deliberately never touched.
for directory in /data /music; do
    if [ -d "$directory" ] && [ ! -L "$directory" ]; then
        chown ktv:ktv "$directory" 2>/dev/null || true
    fi
done

exec su-exec ktv:ktv java -jar /app/app.jar
