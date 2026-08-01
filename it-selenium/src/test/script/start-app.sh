#!/usr/bin/env bash
# Starts the executable Roller WAR for the browser IT suite and blocks until
# it answers, or fails after 180s with the app log tail for diagnosis.
#
# Replaces cargo's Tomcat deploy + pingURL wait: same readiness contract
# (poll roller-ui/login.rol, 180s budget), against the actual Boot-executable
# WAR instead of an exploded WAR under an external Tomcat.
#
# Usage: start-app.sh <war> <port> <custom-config-properties> <pidfile> <log>
set -euo pipefail

WAR="$1"; PORT="$2"; PROPS="$3"; PIDFILE="$4"; LOG="$5"

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

java -Djava.awt.headless=true -Droller.custom.config="$PROPS" \
     -jar "$WAR" --server.port="$PORT" --server.servlet.context-path=/roller \
     > "$LOG" 2>&1 &
echo $! > "$PIDFILE"

for i in $(seq 1 90); do
    if curl -sf "http://127.0.0.1:${PORT}/roller/roller-ui/login.rol" > /dev/null; then
        exit 0
    fi
    sleep 2
done

echo "Roller did not answer on ${PORT} within 180s; log tail:" >&2
tail -50 "$LOG" >&2
exit 1
