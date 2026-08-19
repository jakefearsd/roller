#!/usr/bin/env bash
# Prints current static-analysis violation counts and sites.
# Usage: bin/quality-report.sh [rule-name]
#   no args    -- per-rule count tables for PMD and SpotBugs, plus CPD blocks
#   rule-name  -- every file:line for that one rule
#
# Regenerates the reports first, so the numbers are never stale.
# Sets its own heap (-Xmx4g) rather than relying on the caller's MAVEN_OPTS:
# the PMD+SpotBugs report goals were OOM-killed twice under default heap
# during the SLF4J migration's batch 7.
set -euo pipefail
cd "$(dirname "$0")/.."

RULE="${1:-}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx4g}"

# Scoped to THIS checkout, derived rather than hardcoded: the literal
# "source/roller" only matches the original author's directory layout, so a
# clone at ~/dev/roller reported CLEAR while a build was running and launched a
# second mvn into the same app/target/ -- the clobbering this guard exists to
# prevent. The bracket on [s] keeps the pattern from matching this pgrep itself.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if pgrep -f "[s]urefirebooter.*${REPO_ROOT}" >/dev/null; then
    echo "A build is already running in this tree; wait for it." >&2; exit 2
fi

mvn -ntp -q -pl app compile pmd:pmd pmd:cpd spotbugs:spotbugs

python3 - "$RULE" <<'PY'
import sys, xml.etree.ElementTree as ET
from collections import Counter, defaultdict
rule = sys.argv[1] if len(sys.argv) > 1 else ""

def strip(tag): return tag.split('}')[-1]

pmd = defaultdict(list)
try:
    for f in ET.parse('app/target/pmd.xml').getroot().iter():
        if strip(f.tag) != 'file': continue
        path = f.get('name', '').split('/java/')[-1]
        for v in f.iter():
            if strip(v.tag) == 'violation':
                pmd[v.get('rule')].append(f"{path}:{v.get('beginline')}")
except FileNotFoundError:
    pass

sb = defaultdict(list)
try:
    for b in ET.parse('app/target/spotbugsXml.xml').getroot().findall('BugInstance'):
        c = b.find('Class'); s = b.find('SourceLine')
        where = f"{c.get('classname').split('.')[-1] if c is not None else '?'}:{s.get('start') if s is not None else '?'}"
        sb[b.get('type')].append(where)
except FileNotFoundError:
    pass

if rule:
    for site in pmd.get(rule, []) + sb.get(rule, []):
        print(f"  {site}")
    print(f"{rule}: {len(pmd.get(rule, [])) + len(sb.get(rule, []))}")
    sys.exit(0)

print(f"=== PMD: {sum(len(v) for v in pmd.values())} ===")
for r, v in sorted(pmd.items(), key=lambda kv: -len(kv[1])):
    print(f"  {len(v):4}  {r}")
print(f"=== SpotBugs: {sum(len(v) for v in sb.values())} ===")
for r, v in sorted(sb.items(), key=lambda kv: -len(kv[1])):
    print(f"  {len(v):4}  {r}")

try:
    root = ET.parse('app/target/cpd.xml').getroot()
    ds = [d for d in root.iter() if strip(d.tag) == 'duplication']
    print(f"=== CPD @200: {len(ds)} ===")
    for d in ds:
        files = sorted({f.get('path').split('/java/')[-1] for f in d.iter() if strip(f.tag) == 'file'})
        print(f"  {d.get('lines')}L/{d.get('tokens')}t: " + " <-> ".join(files))
except FileNotFoundError:
    pass
PY
