#!/usr/bin/env bash
# Fails if lines changed since <base-ref> (default HEAD~1) are <90% covered.
# Usage: bin/check-diff-coverage.sh [base-ref]
# Needs: pip install diff_cover; and a fresh coverage report:
#   mvn -ntp -pl app test && mvn -ntp -pl app jacoco:report
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_REF="${1:-HEAD~1}"
REPORT="app/target/site/jacoco/jacoco.xml"

command -v diff-cover >/dev/null 2>&1 || {
    echo "diff-cover not found: pip install diff_cover" >&2; exit 2; }
[ -f "$REPORT" ] || {
    echo "no coverage report at $REPORT — run: mvn -ntp -pl app test && mvn -ntp -pl app jacoco:report" >&2; exit 2; }

exec diff-cover "$REPORT" \
    --src-roots app/src/main/java \
    --compare-branch="$BASE_REF" \
    --fail-under=90
