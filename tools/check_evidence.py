#!/usr/bin/env python3
"""Check evidence references, test inventory, outcomes, local links and hashes.

This is an unqualified convenience checker, not an estimator test or compliance
assessment. It performs no network requests and does not modify the repository.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'docs/evidence'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def path_for(relative):
    path = Path(relative)
    require(not path.is_absolute() and '..' not in path.parts, 'Unsafe path: ' + relative)
    resolved = (ROOT / path).resolve()
    require(resolved.is_relative_to(ROOT), 'Outside repository: ' + relative)
    require(resolved.is_file(), 'Missing file: ' + relative)
    return resolved


def index(rows, label):
    result = {row['id']: row for row in rows}
    require(len(result) == len(rows), 'Duplicate ' + label + ' IDs')
    return result


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def check(skip_manifest=False):
    register = json.loads((PACKAGE / 'traceability.json').read_text())
    requirements = index(register['requirements'], 'requirement')
    tests = index(register['tests'], 'test')
    manuals = index(register['manuals'], 'manual')
    evidence = set(register['evidence_ids'])
    baseline = register['source_baseline']
    git('cat-file', '-e', baseline + '^{commit}')
    for requirement in requirements.values():
        for field, entries in [('test_ids', tests), ('manual_ids', manuals)]:
            for target in requirement[field]:
                require(target in entries, 'Unknown reference: ' + target)
                require(requirement['id'] in entries[target]['requirement_ids'],
                        'Missing reverse link: ' + target + ' / ' + requirement['id'])
        require(set(requirement['evidence_ids']) <= evidence, 'Unknown evidence ID')
        for source in requirement['source_paths']:
            path_for(source)
    symbols = {}
    for file in sorted((ROOT / 'app/src/test').rglob('*.kt')):
        methods = re.findall(r'@Test\s+fun\s+(\w+)\s*\(', file.read_text())
        for method in methods:
            symbols[(str(file.relative_to(ROOT)), method)] = True
    catalog_symbols = {(t['source'], t['method']) for t in tests.values()}
    require(len(catalog_symbols) == len(tests), 'Duplicate test method')
    require(catalog_symbols == set(symbols), 'Test inventory differs from @Test methods')
    for entries, backref in [(tests, 'test_ids'), (manuals, 'manual_ids')]:
        for item in entries.values():
            require(item['requirement_ids'], 'Unmapped entry: ' + item['id'])
            for req in item['requirement_ids']:
                require(req in requirements and item['id'] in requirements[req][backref],
                        'Bad reverse requirement link: ' + item['id'])
    for manual in manuals.values():
        path_for(manual['record'])
    current = json.loads((PACKAGE / 'reports/current-verification.json').read_text())
    require(current['source_baseline'] == baseline, 'Verification baseline mismatch')
    outcomes = {}
    for suite in current['suites']:
        for test in suite['tests']:
            key = (suite['suite'], test['name'])
            require(key not in outcomes, 'Duplicate result: ' + str(key))
            outcomes[key] = test['outcome']
    require(len(outcomes) == len(tests), 'Report/test count mismatch')
    for test in tests.values():
        require(outcomes.get((test['suite'], test['method'])) == test['current_outcome'],
                'Wrong/missing outcome: ' + test['id'])
    totals = Counter(outcomes.values())
    require(current['test_totals'] == {'total': len(outcomes), **dict(totals)}, 'Wrong totals')
    require(sum(g['count'] for g in current['lint']['groups']) == current['lint']['total_issues'],
            'Wrong lint totals')
    # Validate local Markdown file links, including external-file anchors used here.
    docs = list(PACKAGE.rglob('*.md')) + [ROOT / 'README.md', ROOT / 'docs/estimator-v1.md',
                                           ROOT / 'docs/tablet-e8a.md']
    checked_links = 0
    for doc in docs:
        for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)', doc.read_text()):
            target = target.strip('<>')
            parts = urlsplit(target)
            if parts.scheme or parts.netloc:
                continue
            destination = (doc.parent / unquote(parts.path)).resolve() if parts.path else doc
            require(destination.is_relative_to(ROOT) and destination.is_file(),
                    'Broken local link in ' + str(doc.relative_to(ROOT)) + ': ' + target)
            if parts.fragment and destination.suffix == '.md':
                headings = re.findall(r'^#{1,6}\s+(.+)$', destination.read_text(), re.M)
                slugs = {re.sub(r'[^\w\- ]', '', h.lower()).replace(' ', '-') for h in headings}
                require(unquote(parts.fragment) in slugs, 'Broken anchor: ' + target)
            checked_links += 1
    if not skip_manifest:
        manifest = json.loads((PACKAGE / 'manifest.json').read_text())
        require(manifest['source_baseline'] == baseline, 'Manifest baseline mismatch')
        files = manifest['files']
        require(len(files) == len({f['path'] for f in files}), 'Duplicate manifest path')
        for entry in files:
            data = path_for(entry['path']).read_bytes()
            require(hashlib.sha256(data).hexdigest() == entry['sha256'],
                    'Hash mismatch: ' + entry['path'])
            require(len(data) == entry['bytes'], 'Length mismatch: ' + entry['path'])
        # Every packaged doc and utility must be represented; manifest excludes itself.
        expected = {str(f.relative_to(ROOT)) for f in PACKAGE.rglob('*') if f.is_file()
                    and f.name != 'manifest.json'}
        expected.update({'tools/check_evidence.py', 'tools/summarize_verification.py'})
        require(expected <= {f['path'] for f in files}, 'Unmanifested evidence file')
        # Runtime/test/build baseline must not change during a documentation-only audit.
        for source in manifest['unchanged_baseline_paths']:
            # Respect Git attributes (notably the CRLF checkout of gradlew.bat).
            path_for(source)
            require(git('hash-object', '--path=' + source, source).strip() ==
                    git('rev-parse', baseline + ':' + source).strip(),
                    'Source differs from preserved B09: ' + source)
    print(f'PASS: {len(requirements)} requirements, {len(tests)} test methods, '
          f'{len(manuals)} manual/planned campaigns, {checked_links} local links; '
          f'outcomes {dict(totals)}; manifest ' + ('skipped' if skip_manifest else 'verified'))
    print('This checks evidence consistency, not requirements satisfaction or certification.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-manifest', action='store_true', help='Authoring-only partial check')
    arguments = parser.parse_args()
    try:
        check(arguments.skip_manifest)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit('FAIL: ' + str(error))
