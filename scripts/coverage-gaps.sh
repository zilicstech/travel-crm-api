#!/usr/bin/env bash
# Runs the given tests with JaCoCo and lists missed lines and branches per class.
# Usage: scripts/coverage-gaps.sh '<surefire -Dtest pattern>' [class-or-package prefix ...]
#   scripts/coverage-gaps.sh 'BookingServiceTest,LeadServiceTest' service.BookingService service.LeadService
# Missed line numbers come from target/site/jacoco/jacoco.xml. With no prefixes every class is listed.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
pattern="$1"; shift || true
./mvnw -q test -Dtest="$pattern" -Dsurefire.failIfNoSpecifiedTests=false
python3 - "$@" <<'EOF'
import sys, xml.etree.ElementTree as ET
prefixes = ['com/voyra/crm/' + p.replace('.', '/') for p in sys.argv[1:]]
root = ET.parse('target/site/jacoco/jacoco.xml').getroot()
for pkg in root.iter('package'):
    for src in pkg.iter('sourcefile'):
        path = pkg.get('name') + '/' + src.get('name').removesuffix('.java')
        if prefixes and not any(path.startswith(p) for p in prefixes):
            continue
        lines = [l for l in src.iter('line')]
        missed = [l.get('nr') for l in lines if int(l.get('mi')) > 0]
        partial = [f"{l.get('nr')}({l.get('mb')}b)" for l in lines if int(l.get('mb')) > 0]
        if missed or partial:
            print(f"{path.removeprefix('com/voyra/crm/')}: missed lines {','.join(missed) or '-'}; missed branches at {','.join(partial) or '-'}")
EOF
