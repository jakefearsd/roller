#!/usr/bin/env bash
# Shared identity and reaping helpers for the browser IT harness. Sourced by
# start-app.sh, stop-app.sh, sweep-stale.sh and supervise-run.sh; not executable
# on its own.
#
# WHY IDENTITY RATHER THAN A PIDFILE
#
# Everything the harness starts carries two system properties on its command
# line:
#
#   -Droller.it.run=<run id>          which run it belongs to
#   -Droller.it.owner=<pid>@<start>   which Maven build owns it
#
# so any process can be found again without a file surviving, and "is this
# stale?" has an exact answer -- the owning build is either still running or it
# is not. A pidfile answers neither question once it has been truncated by the
# next run, which is how four app JVMs came to be running on a machine whose
# it-work directory held no app.pid at all.
#
# The owner token pins the *start time* as well as the pid, because pids are
# recycled: a token whose pid is alive but started at a different moment names
# a build that has exited and had its pid reused, not a live one.
#
# Process discovery is `ps` plus shell matching, never `pgrep -f`: a pgrep
# pattern matches the command line of the process doing the grepping (and of
# any `while pgrep ...; do` loop waiting on it), which is the self-match trap
# CLAUDE.md records. `ps` output cannot contain this script's own pattern.

IT_RUN_PROP="-Droller.it.run="
IT_OWNER_PROP="-Droller.it.owner="

# The supervisor marks itself with a DIFFERENT property, and the split is not
# cosmetic. A forked shell subprocess (command substitution, either side of a
# pipeline) shows its parent's command line in ps, so a supervisor marked with
# -Droller.it.run= finds its own short-lived subshells while enumerating the
# run's processes and kills them. Observed, not theoretical. Keeping the two
# roles on two properties means the kill list ("what belongs to this run?")
# and the liveness question ("is this run still claimed?") never overlap.
IT_SUPERVISOR_PROP="-Droller.it.supervisor="

# Container names are <prefix>-<run id>; the sweep matches on the prefix.
IT_CONTAINER_PREFIX="roller-it-postgres"

# ---------------------------------------------------------------- ownership

# it_owner_stamp <pid> -> "<pid>@<start time, punctuation stripped>"
#
# Never fails: an unresolvable start time becomes "unknown", which
# it_owner_alive then treats as "cannot disprove", so a missing stamp can only
# ever spare a process, never kill one.
it_owner_stamp() {
    local pid="${1:-0}" started=""
    if [ -n "$pid" ]; then
        started="$(ps -o lstart= -p "$pid" 2>/dev/null | tr -d ' :' || true)"
    fi
    printf '%s@%s' "$pid" "${started:-unknown}"
}

# it_owner_alive <token> -> 0 if the build that owns the token is still running.
it_owner_alive() {
    local token="${1:-}" pid stamp now
    pid="${token%%@*}"
    stamp="${token#*@}"
    case "$pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    ps -p "$pid" >/dev/null 2>&1 || return 1
    now="$(ps -o lstart= -p "$pid" 2>/dev/null | tr -d ' :' || true)"
    # Alive, but one side has no start time to compare: refuse to guess and
    # call it live. Reaping on a guess is the one failure this must not have.
    if [ -z "$now" ] || [ "$stamp" = "unknown" ]; then
        return 0
    fi
    [ "$now" = "$stamp" ]
}

# ---------------------------------------------------------------- discovery

# Emits "<pid> <run id> <owner token>" for every live process carrying the
# given marker property, owned by this user. Restricted to this user
# deliberately: another account's processes are not ours to kill, and matching
# them would only produce failed kills.
it_processes_with_prop() {
    local prop="$1"
    local pid args runid owner
    ps -ww -U "$(id -u)" -o pid=,args= 2>/dev/null | while read -r pid args; do
        case "$args" in
            *"$prop"*) ;;
            *) continue ;;
        esac
        runid="${args##*"$prop"}"
        runid="${runid%% *}"
        case "$args" in
            *"$IT_OWNER_PROP"*)
                owner="${args##*"$IT_OWNER_PROP"}"
                owner="${owner%% *}"
                ;;
            *) owner="0@unknown" ;;
        esac
        [ -n "$runid" ] || continue
        printf '%s %s %s\n' "$pid" "$runid" "$owner"
    done
}

# Processes a run OWNS and that must be killed to clean it up (the app JVM).
it_marked_processes() {
    it_processes_with_prop "$IT_RUN_PROP"
}

# Supervisors, which merely CLAIM a run. Never killed to clean a run up; they
# exit on their own once the build they watch is gone.
it_supervisor_processes() {
    it_processes_with_prop "$IT_SUPERVISOR_PROP"
}

# it_pids_for_run <run id> [pid to skip...] -> pids belonging to that run.
it_pids_for_run() {
    local want="$1"
    shift
    local pid runid owner skip skipped
    it_marked_processes | while read -r pid runid owner; do
        [ "$runid" = "$want" ] || continue
        skipped=0
        for skip in "$@"; do
            [ "$pid" = "$skip" ] && skipped=1
        done
        [ "$skipped" -eq 1 ] || printf '%s\n' "$pid"
    done
}

# it_run_is_live <run id> -> 0 if anything still holds that run: its app, or
# the supervisor that claims it. The supervisor half matters most between
# pg-start and app-start, the window in which a run owns a container but has
# not started an app yet; without it a concurrent run's fresh container would
# look like an orphan.
it_run_is_live() {
    [ -n "$(it_pids_for_run "$1")" ] && return 0
    local pid runid owner
    while read -r pid runid owner; do
        [ "$runid" = "$1" ] && return 0
    done < <(it_supervisor_processes)
    return 1
}

# it_descendants <pid> -> every process below it, depth first. Used to attribute
# chromedrivers to a run while their JVM is alive; once that JVM dies they
# reparent to init and become unattributable, which is exactly why the
# supervisor records them in advance rather than looking for them afterwards.
it_descendants() {
    local pid="${1:-}" child
    [ -n "$pid" ] || return 0
    for child in $(pgrep -P "$pid" 2>/dev/null || true); do
        printf '%s\n' "$child"
        it_descendants "$child"
    done
}

# ------------------------------------------------------------------ reaping

# it_kill_pids <grace seconds> <pid...> -- TERM, wait, KILL. Returns non-zero
# if anything survived both, so callers can report a failure to clean up rather
# than assuming success.
it_kill_pids() {
    local grace="${1:-10}"
    shift
    [ "$#" -gt 0 ] || return 0

    local pid i alive
    for pid in "$@"; do
        kill -TERM "$pid" 2>/dev/null || true
    done

    for i in $(seq 1 "$grace"); do
        alive=""
        for pid in "$@"; do
            ps -p "$pid" >/dev/null 2>&1 && alive="$alive $pid"
        done
        [ -n "$alive" ] || return 0
        sleep 1
    done

    for pid in $alive; do
        kill -KILL "$pid" 2>/dev/null || true
    done
    sleep 1

    for pid in "$@"; do
        if ps -p "$pid" >/dev/null 2>&1; then
            return 1
        fi
    done
    return 0
}

# it_describe_pid <pid> -- one line of evidence for the report: what it is and
# how long it has been running. Printed before killing, never after.
it_describe_pid() {
    local pid="$1"
    ps -ww -o pid=,etime=,rss=,args= -p "$pid" 2>/dev/null | cut -c1-160 || true
}

# it_remove_container <name> -- idempotent, and always with -v: the IT postgres
# container is anonymous-volume-backed (postgres:16 declares VOLUME
# /var/lib/postgresql/data), so removing it without -v orphans a volume per run.
it_remove_container() {
    local name="${1:-}"
    [ -n "$name" ] || return 0
    command -v docker >/dev/null 2>&1 || return 0
    if [ -n "$(docker ps -aq --filter "name=^${name}$" 2>/dev/null)" ]; then
        docker rm -f -v "$name" >/dev/null 2>&1 && printf 'removed container %s (with its volume)\n' "$name"
    fi
}
