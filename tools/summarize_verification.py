#!/usr/bin/env python3
"""Print a public-safe summary of an existing Gradle run; does not run tests.

Raw reports may contain hostnames and local paths. Only allowlisted fields are
exported. Use apply_patch or a reviewed editor to archive the JSON output.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--date', required=True, help='UTC date of completed run, YYYY-MM-DD')
    parser.add_argument('--baseline', required=True, help='Full source commit checked by the operator')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    subprocess.run(['git', 'cat-file', '-e', args.baseline + '^{commit}'], cwd=root, check=True)
    suites = []
    for file in sorted((root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
        result = ET.parse(file).getroot()
        # Filesystem conflict copies are not another executed suite.
        if file.name != 'TEST-' + result.attrib['name'] + '.xml':
            continue
        timestamp = result.attrib.get('timestamp', '')
        if not timestamp.startswith(args.date + 'T'):
            raise SystemExit('Stale canonical report: ' + file.name)
        tests = []
        for case in result.findall('testcase'):
            outcome = 'passed'
            for tag, label in [('skipped', 'skipped'), ('failure', 'failed'), ('error', 'error')]:
                if case.find(tag) is not None:
                    outcome = label
            tests.append({'name': case.attrib['name'], 'outcome': outcome})
        suites.append({'suite': result.attrib['name'], 'timestamp_utc': timestamp,
                       'duration_seconds': float(result.attrib.get('time', '0')),
                       'report_sha256': hashlib.sha256(file.read_bytes()).hexdigest(), 'tests': tests})
    if not suites:
        raise SystemExit('No test reports found')
    lint = root / 'app/build/reports/lint-results-debug.xml'
    issues = ET.parse(lint).getroot().findall('issue')
    grouped = Counter((item.attrib.get('id'), item.attrib.get('severity')) for item in issues)
    apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
    outcomes = Counter(t['outcome'] for suite in suites for t in suite['tests'])
    print(json.dumps({
        'schema_version': 1, 'evidence_id': 'E-015', 'run_date_utc': args.date,
        'source_baseline': args.baseline,
        'scope': 'Existing debug JVM unit tests, Android lint and debug APK build; no device execution.',
        'test_totals': {'total': sum(outcomes.values()), **dict(outcomes)},
        'suites': suites,
        'lint': {'report_sha256': hashlib.sha256(lint.read_bytes()).hexdigest(),
                 'total_issues': len(issues), 'by_severity': dict(Counter(x.attrib.get('severity') for x in issues)),
                 'groups': [{'id': key[0], 'severity': key[1], 'count': count} for key, count in sorted(grouped.items())]},
        'apk': {'path': 'app/build/outputs/apk/debug/app-debug.apk',
                'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'bytes': apk.stat().st_size,
                'included_in_public_package': False},
        'limitations': ['Report hashes identify local artifacts, not authenticated or reproducible builds.',
                       'Private drive replay is optional; inspect skipped outcomes.',
                       'Passing assertions are not an independent accuracy reference.']
    }, indent=2))


if __name__ == '__main__':
    main()
