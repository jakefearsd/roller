#!/usr/bin/env bash
# Pre-run sweep: reap what earlier IT runs leaked, and SAY SO.
#
# Runs first in pre-integration-test, before the postgres container starts, so
# whatever a Ctrl-C or an aborted build left behind is gone before this run
# adds to it. That bounds accumulation to one run's worth.
#
# Saying so is half the fix. build-helper reserves a *free* port every run, so
# a leaked app server holding a port never collided with anything and the next
# run started perfectly cleanly -- which is how four Roller JVMs and thirteen
# chromedrivers accumulated on a developer machine without one failing build.
# The leak was never the silent part; the silence was.
#
# Staleness is decided by the owning build, never by "an IT process exists": a
# second, concurrent `mvn verify -Pit` is legitimate (that is what the random
# port reservation is for) and its processes must survive this sweep. A process
# is stale only when the Maven build named by its owner token is gone.
#
# Usage: sweep-stale.sh <current-run-id> [work-dir]
# Environment:
#   IT_WORK_DIR      overrides <work-dir>
#   IT_SWEEP_STRICT  set to 1 to fail the build when anything was reaped,
#                    rather than warning loudly (default: warn)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=it-harness-lib.sh
. "$SCRIPT_DIR/it-harness-lib.sh"

CURRENT_RUN="${1:-}"
WORK_DIR="${IT_WORK_DIR:-${2:-}}"
STRICT="${IT_SWEEP_STRICT:-0}"

REAPED=0
FAILED=0
REPORT=""

note() {
    REPORT="${REPORT}$1
"
}

# ------------------------------------------------ processes from dead builds

STALE_RUNS=""
while read -r kind pid runid owner; do
    [ -n "${pid:-}" ] || continue
    [ "$runid" = "$CURRENT_RUN" ] && continue
    if it_owner_alive "$owner"; then
        continue
    fi
    note "  pid $pid  $kind of run $runid  owner ${owner%%@*} is gone"
    note "    $(it_describe_pid "$pid")"
    case " $STALE_RUNS " in
        *" $runid "*) ;;
        *) STALE_RUNS="$STALE_RUNS $runid" ;;
    esac
    if it_kill_pids 10 "$pid"; then
        REAPED=$(( REAPED + 1 ))
    else
        note "    FAILED to kill pid $pid"
        FAILED=1
    fi
    # A supervisor whose build is gone is mid-cleanup or wedged; either way the
    # rest of this sweep does the same work, and taking it out first is what
    # lets the container pass below see its run as unclaimed.
done < <(it_marked_processes | sed 's/^/app /'; it_supervisor_processes | sed 's/^/supervisor /')

# ---------------------------------------------------- their chromedrivers

# Chromedrivers cannot be identified after the fact -- once the failsafe JVM
# that spawned them dies they reparent to init and look like anyone else's.
# The supervisor records them by pid while their JVM is still alive; this reads
# that record back, and re-checks each pid really is still a chromedriver
# before killing it, in case the pid has been recycled since.
for runid in $STALE_RUNS; do
    record="${WORK_DIR:-.}/supervisor-${runid}.chromedrivers"
    [ -f "$record" ] || continue
    while read -r pid; do
        case "$pid" in
            ''|*[!0-9]*) continue ;;
        esac
        case "$(ps -o comm= -p "$pid" 2>/dev/null || true)" in
            chromedriver*) ;;
            *) continue ;;
        esac
        note "  pid $pid  chromedriver from run $runid"
        if it_kill_pids 10 "$pid"; then
            REAPED=$(( REAPED + 1 ))
        else
            note "    FAILED to kill chromedriver $pid"
            FAILED=1
        fi
    done < "$record"
    rm -f "$record"
done

# ------------------------------------------------------ their containers

# Named <prefix>-<run id>, so a container is stale exactly when no process of
# its run is still alive. The bare legacy name (no run id) predates per-run
# naming and can only be a corpse -- it is also the "409 name conflict on the
# next run" that CLAUDE.md used to tell operators to clear by hand.
if command -v docker >/dev/null 2>&1; then
    for name in $(docker ps -a --filter "name=^${IT_CONTAINER_PREFIX}" --format '{{.Names}}' 2>/dev/null || true); do
        [ "$name" = "${IT_CONTAINER_PREFIX}-${CURRENT_RUN}" ] && continue
        runid="${name#"${IT_CONTAINER_PREFIX}"}"
        runid="${runid#-}"
        if [ -n "$runid" ] && it_run_is_live "$runid"; then
            continue
        fi
        removed="$(it_remove_container "$name")"
        if [ -n "$removed" ]; then
            note "  $removed"
            REAPED=$(( REAPED + 1 ))
        fi
    done
fi

# ----------------------------------------------------------------- report

if [ "$REAPED" -eq 0 ] && [ "$FAILED" -eq 0 ]; then
    echo "sweep-stale.sh: nothing left over from earlier IT runs."
    exit 0
fi

echo "=================================================================="
echo " IT harness: reaped $REAPED leftover(s) from earlier runs"
echo "=================================================================="
printf '%s' "$REPORT"
echo "------------------------------------------------------------------"
echo " These were leaked by an earlier 'mvn verify -Pit' that did not"
echo " reach its cleanup phase (Ctrl-C, or a failure in"
echo " pre-integration-test). They are gone now; this run starts clean."
echo "=================================================================="

if [ "$FAILED" -ne 0 ]; then
    echo "sweep-stale.sh: could not kill everything it found (see above)" >&2
    exit 1
fi
if [ "$STRICT" = "1" ]; then
    echo "sweep-stale.sh: IT_SWEEP_STRICT=1 and leftovers were found" >&2
    exit 1
fi
exit 0
