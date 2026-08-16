#!/usr/bin/env bash
# Starts the executable Roller WAR for the browser IT suite and blocks until
# it answers, or fails with the app log tail for diagnosis -- killing what it
# started on every failure path.
#
# Replaces cargo's Tomcat deploy + pingURL wait: same readiness contract
# (poll roller-ui/login.rol, 180s budget), against the actual Boot-executable
# WAR instead of an exploded WAR under an external Tomcat.
#
# THE TRAP IS THE POINT. This runs in pre-integration-test, so a non-zero exit
# here aborts the build *before* post-integration-test -- the phase where
# app-stop lives. Exiting without killing the JVM it had just started therefore
# leaked it permanently, and the readiness-timeout path did exactly that. Every
# exit from this script now goes through cleanup().
#
# Usage: start-app.sh <war> <port> <custom-config-properties> <pidfile> <log> <run-id>
#
# Environment (all optional):
#   IT_APP_JAVA               java command to launch with (default: java)
#   IT_READY_TIMEOUT_SECONDS  readiness budget (default: 180)
#   IT_OWNER_PID              build process that owns this app (default: $PPID,
#                             which under exec-maven-plugin is the Maven JVM)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=it-harness-lib.sh
. "$SCRIPT_DIR/it-harness-lib.sh"

WAR="$1"; PORT="$2"; PROPS="$3"; PIDFILE="$4"; LOG="$5"; RUN_ID="$6"

JAVA="${IT_APP_JAVA:-java}"
READY_TIMEOUT="${IT_READY_TIMEOUT_SECONDS:-180}"
OWNER="$(it_owner_stamp "${IT_OWNER_PID:-$PPID}")"

APP_PID=""

cleanup() {
    local status=$?
    if [ "$status" -ne 0 ] && [ -n "$APP_PID" ]; then
        echo "start-app.sh: exiting $status -- killing the app it started (pid $APP_PID, run $RUN_ID)" >&2
        it_kill_pids 10 "$APP_PID" || echo "start-app.sh: pid $APP_PID survived SIGKILL" >&2
        rm -f "$PIDFILE"
    fi
    exit "$status"
}
trap cleanup EXIT
# Ctrl-C and `mvn` being killed both arrive as signals: turn them into an exit,
# so the EXIT trap above does the killing rather than the shell dying silently.
trap 'exit 130' INT
trap 'exit 143' TERM

if [ ! -f "$WAR" ]; then
    echo "start-app.sh: WAR not found at $WAR -- the reactor must build the" \
         "app module (repackaging it into an executable WAR) before" \
         "it-selenium's pre-integration-test phase runs. Run the full" \
         "reactor ('mvn verify -Pit' from the repo root), not 'mvn verify'" \
         "inside it-selenium alone." >&2
    exit 1
fi

if [ ! -f "$PROPS" ]; then
    echo "start-app.sh: properties file not found at $PROPS -- it is a filtered" \
         "test resource; run the 'verify' lifecycle (which runs" \
         "process-test-resources first), not this script standalone." >&2
    exit 1
fi

mkdir -p "$(dirname "$PIDFILE")" "$(dirname "$LOG")"

# management.server.port=0 (random free port): the ITs never poll actuator
# (readiness below is roller-ui/login.rol on the main port), and application
# .properties' default of 8090 would otherwise collide across concurrent IT
# runs the same way a fixed app port would -- 0 sidesteps that with no need
# to reserve/thread a dedicated port property through this module.
#
# roller.it.run / roller.it.owner are inert as far as Roller is concerned: they
# exist so this process can be found again by anything that has to clean it up.
"$JAVA" -Djava.awt.headless=true -Droller.custom.config="$PROPS" \
     "-Droller.it.run=$RUN_ID" "-Droller.it.owner=$OWNER" \
     -jar "$WAR" --server.port="$PORT" --server.servlet.context-path=/roller \
     --management.server.port=0 \
     > "$LOG" 2>&1 &
APP_PID=$!
echo "$APP_PID" > "$PIDFILE"

# A stable name for the newest run's log, since the logs themselves are now
# per-run (a shared app.log was truncated by the next run, destroying the
# diagnostics of the run that had just leaked).
ln -sfn "$(basename "$LOG")" "$(dirname "$LOG")/app-latest.log" 2>/dev/null || true

echo "start-app.sh: run $RUN_ID, app pid $APP_PID, owner $OWNER, log $LOG"

DEADLINE=$(( SECONDS + READY_TIMEOUT ))
while [ "$SECONDS" -lt "$DEADLINE" ]; do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        echo "start-app.sh: the app exited before answering on ${PORT}; log tail:" >&2
        tail -50 "$LOG" >&2
        APP_PID=""
        exit 1
    fi
    if curl -sf "http://127.0.0.1:${PORT}/roller/roller-ui/login.rol" > /dev/null; then
        exit 0
    fi
    sleep 2
done

echo "start-app.sh: Roller did not answer on ${PORT} within ${READY_TIMEOUT}s; log tail:" >&2
tail -50 "$LOG" >&2
exit 1
