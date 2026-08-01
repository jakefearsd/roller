#!/usr/bin/env bash
# Stops the Roller process started by start-app.sh: TERM, wait, then KILL if
# it hasn't exited. Missing/stale pidfile is not a build failure -- the app
# may never have started (e.g. start-app.sh itself failed first).
#
# Usage: stop-app.sh <pidfile>
set -euo pipefail

PIDFILE="$1"

if [ ! -f "$PIDFILE" ]; then
    echo "stop-app.sh: no pidfile at $PIDFILE, nothing to stop"
    exit 0
fi

PID="$(cat "$PIDFILE")"

if ! kill -0 "$PID" 2>/dev/null; then
    echo "stop-app.sh: pid $PID from $PIDFILE is not running, nothing to stop"
    rm -f "$PIDFILE"
    exit 0
fi

kill -TERM "$PID" 2>/dev/null || true

for i in $(seq 1 30); do
    if ! kill -0 "$PID" 2>/dev/null; then
        rm -f "$PIDFILE"
        exit 0
    fi
    sleep 1
done

echo "stop-app.sh: pid $PID did not exit within 30s of SIGTERM; sending SIGKILL" >&2
kill -KILL "$PID" 2>/dev/null || true
rm -f "$PIDFILE"
