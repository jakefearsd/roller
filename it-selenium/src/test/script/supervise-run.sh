#!/usr/bin/env bash
# Ties one IT run's processes and container to the lifetime of the Maven build
# that owns them, rather than to the build reaching post-integration-test.
#
# WHY THIS EXISTS. Failsafe's split-goal design already protects cleanup from
# *test* failures: integration-test records them, verify fails the build later,
# post-integration-test runs in between. What it cannot protect against is an
# *infrastructure* failure earlier in pre-integration-test -- pg-wait-ready
# timing out, migrate.sh failing, the seed failing, app-start timing out -- or a
# Ctrl-C. Any of those aborts the build before post-integration-test exists,
# taking every cleanup step with it. This supervisor starts before all of them
# and outlives the build, so the run is cleaned up by something whose own
# survival does not depend on the build's.
#
# It is a detached poller, not a systemd scope. `systemd-run --user --scope`
# would give a killable-by-name unit, but it needs a user D-Bus session that
# is not reliably present on CI runners or in containers, it does not exist on
# macOS, and a scope does not actually die with its invoking process -- so it
# would add a platform dependency and still need this polling loop underneath.
# setsid plus an ignored SIGINT is portable to anything with a POSIX shell.
#
# Usage: supervise-run.sh <run-id> <container-name> <work-dir>
# Environment:
#   IT_OWNER_STAMP  owner token to watch (default: computed from $PPID, which
#                   under maven-antrun-plugin is the Maven JVM)
#   IT_POLL_SECONDS how often to check on the build (default: 3)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=it-harness-lib.sh
. "$SCRIPT_DIR/it-harness-lib.sh"

RUN_ID="$1"
CONTAINER="${2:-}"
WORK_DIR="${3:-.}"

OWNER="${IT_OWNER_STAMP:-$(it_owner_stamp "$PPID")}"
POLL="${IT_POLL_SECONDS:-3}"
LOG="$WORK_DIR/supervisor-$RUN_ID.log"
CHROMEDRIVERS="$WORK_DIR/supervisor-$RUN_ID.chromedrivers"

# First invocation: detach and return immediately, so the build is not blocked
# by its own supervisor. The child re-enters this script with the marker
# properties on its command line, which is what makes it findable (and
# reapable) by sweep-stale.sh should it ever be stranded itself.
if [ "${IT_SUPERVISOR_CHILD:-}" != "1" ]; then
    mkdir -p "$WORK_DIR"
    export IT_SUPERVISOR_CHILD=1
    export IT_OWNER_STAMP="$OWNER"
    DETACH=""
    command -v setsid >/dev/null 2>&1 && DETACH="setsid"
    # Marked as a supervisor, deliberately NOT as a member of the run: see
    # it-harness-lib.sh on why the two roles carry different properties.
    # shellcheck disable=SC2086
    $DETACH "$0" "$@" "-Droller.it.supervisor=$RUN_ID" "-Droller.it.owner=$OWNER" \
        >>"$LOG" 2>&1 </dev/null &
    echo "supervise-run.sh: watching build ${OWNER%%@*} for run $RUN_ID (log: $LOG)"
    exit 0
fi

# Ctrl-C reaches the whole foreground process group. Being in a new session
# already prevents that; ignoring the signals covers the case where setsid is
# unavailable. Dying with the build is the one thing a supervisor must not do.
trap '' INT HUP

OWNER_PID="${OWNER%%@*}"
echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] supervising run $RUN_ID, build pid $OWNER_PID, container ${CONTAINER:-none}"

# Chromedrivers are attributable to this run only while the failsafe JVM that
# spawned them is alive; afterwards they are orphans indistinguishable from any
# other project's. So record them as they appear.
record_chromedrivers() {
    local pid comm
    for pid in $(it_descendants "$OWNER_PID"); do
        comm="$(ps -o comm= -p "$pid" 2>/dev/null || true)"
        case "$comm" in
            chromedriver*)
                if ! grep -qx "$pid" "$CHROMEDRIVERS" 2>/dev/null; then
                    echo "$pid" >> "$CHROMEDRIVERS"
                fi
                ;;
        esac
    done
}

while it_owner_alive "$OWNER"; do
    record_chromedrivers
    sleep "$POLL"
done

echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] build $OWNER_PID has exited; cleaning up run $RUN_ID"

# Everything below is idempotent: on a run that ended properly, app-stop and
# pg-stop have already done all of it and there is nothing left to find.
for pid in $(it_pids_for_run "$RUN_ID" "$$"); do
    echo "  killing pid $pid"
    echo "    $(it_describe_pid "$pid")"
    it_kill_pids 15 "$pid" || echo "  pid $pid survived SIGKILL"
done

if [ -f "$CHROMEDRIVERS" ]; then
    while read -r pid; do
        case "$pid" in
            ''|*[!0-9]*) continue ;;
        esac
        case "$(ps -o comm= -p "$pid" 2>/dev/null || true)" in
            chromedriver*) ;;
            *) continue ;;
        esac
        echo "  killing chromedriver $pid"
        it_kill_pids 10 "$pid" || echo "  chromedriver $pid survived SIGKILL"
    done < "$CHROMEDRIVERS"
    rm -f "$CHROMEDRIVERS"
fi

removed="$(it_remove_container "$CONTAINER")"
[ -n "$removed" ] && echo "  $removed"

echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] run $RUN_ID cleaned up"
