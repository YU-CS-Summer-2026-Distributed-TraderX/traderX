#!/usr/bin/env python3
"""Run local source/generated suites serially; retain every attempt and report coverage gaps."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def junit(folder):
    rows = []
    for path in sorted(folder.rglob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        rows.append({'suite': suite.get('name'), 'tier': path.parent.name,
                     **{k: int(suite.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}})
    return rows



def output_test_counts(text):
    """Read an attributable runner summary, including skip-only refusal inputs."""
    found = re.findall(r'Ran (\d+) tests?', text)
    if found:
        skipped = re.findall(r'skipped=(\d+)', text)
        return int(found[-1]), int(skipped[-1]) if skipped else 0
    found = re.findall(r'(?:#|ℹ) tests (\d+)', text)
    if found:
        skipped = re.findall(r'(?:#|ℹ) skipped (\d+)', text)
        return int(found[-1]), int(skipped[-1]) if skipped else 0
    text = re.sub(r'\x1b\[[0-9;]*m', '', text)
    found = re.findall(r'TOTAL:\s*(?:(\d+)\s+FAILED,\s*)?(\d+)\s+SUCCESS', text)
    if found:
        failed, passed = found[-1]
        skips = re.findall(r'\(skipped (\d+)\)', text)
        skipped = int(skips[-1]) if skips else 0
        return int(failed or 0) + int(passed) + skipped, skipped
    # pytest reports executed and skipped cases separately in its terminal summary.
    lines = [line for line in text.splitlines() if re.search(r'\d+ (?:passed|failed|skipped).* in ', line)]
    if lines:
        counts = {kind: int(n) for n, kind in re.findall(r'(\d+) (passed|failed|skipped)', lines[-1])}
        return sum(counts.values()), counts.get('skipped', 0)
    return 0, 0


def source_identity():
    """Content identity of versioned and new source; ignore generated/cache outputs like Git."""
    tracked = subprocess.check_output(['git', 'ls-files', '--cached', '-z'], cwd=ROOT).decode().split('\0')
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    outputs = {'.test-results', 'dist', 'build', 'coverage', '.gradle', '.angular', 'node_modules', '__pycache__'}
    paths = tracked + [name for name in untracked if not any(part in outputs for part in Path(name).parts)]
    entries = []
    for name in sorted(set(paths) - {''}):
        path = ROOT / name
        if path.is_symlink():
            entries.append([name, 'symlink', os.readlink(path)])
        elif path.is_file():
            entries.append([name, bool(path.stat().st_mode & 0o111), hashlib.sha256(path.read_bytes()).hexdigest()])
        else:
            entries.append([name, 'absent'])
    digest = hashlib.sha256(json.dumps(entries, separators=(',', ':')).encode()).hexdigest()
    return {'sha256': digest, 'files': entries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True, help='New owned evidence directory; never overwrite an attempt')
    parser.add_argument('--python', type=Path, required=True, help='Interpreter with EOD and tick-store test dependencies')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--part', choices=['states', 'components', 'extras', 'legacy', 'website', 'all'], default='all')
    parser.add_argument('--containers', action='store_true', help='Run the three composed Testcontainers integration tiers')
    parser.add_argument('--state', action='append', help='Rerun only these YU state IDs; partial scope is recorded')
    parser.add_argument('--fetch-dependencies', action='store_true', help='Permit npm to fetch existing locked dependencies missing from the local cache')
    args = parser.parse_args()
    npm_install = ['npm', 'ci', *([] if args.fetch_dependencies else ['--offline']), '--no-audit', '--no-fund']
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    env = {**os.environ, 'JAVA_HOME': str(args.java_home), 'PYTHONDONTWRITEBYTECODE': '1',
           'SOURCE_DATE_EPOCH': '1791691081', 'TRADERX_SKIP_LOCKFILE_REFRESH': '1',
           'npm_config_offline': str(not args.fetch_dependencies).lower(),
           'NPM_CONFIG_OFFLINE': str(not args.fetch_dependencies).lower(),
           'PUPPETEER_SKIP_DOWNLOAD': 'true'}
    env['PATH'] = str(args.python.parent) + ':' + str(args.java_home / 'bin') + ':' + env['PATH']
    # The matcher JWT fixture key never comes from an operator's exported credentials.
    env['AUTH_JWT_SECRET'] = 'ri31-synthetic-suite-secret'
    env['TRADERX_RUN_LIVE_CHECKS'] = '0'
    env.pop('TRADERX_SKIP_GENERATE', None)
    env.pop('TRADERX_GENERATED_ROOT', None)
    env.pop('JAVA_TOOL_OPTIONS', None)
    env.pop('AERON_REPLICATION_PHASE0', None)
    env.pop('RISK_EXTRACT_GCS_HMAC_KEY_ID', None)  # Separate cloud-write proof, not this local suite.
    if Path('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome').exists():
        env['CHROME_BIN'] = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
        env['PUPPETEER_EXECUTABLE_PATH'] = env['CHROME_BIN']
    results = []
    source = source_identity()
    (evidence / 'source-identity.json').write_text(json.dumps(source, indent=2))

    def save():
        temporary = evidence / 'results.json.tmp'
        temporary.write_text(json.dumps(results, indent=2))
        temporary.replace(evidence / 'results.json')

    def deferred(name, reason):
        results.append({'name': name, 'verdict': 'not executed', 'reason': reason})
        save()

    def run(name, command, cwd=ROOT, reports=None, require_tests=False, timeout=900):
        started = time.monotonic()
        path = evidence / (name + '.log')
        with path.open('w') as output:
            try:
                process = subprocess.run(command, cwd=cwd, env=env, stdout=output,
                                         stderr=subprocess.STDOUT, timeout=timeout)
                code = process.returncode
            except subprocess.TimeoutExpired:
                code = 124
            except OSError as error:
                output.write(f'runner prerequisite error: {error}\n')
                code = 125
        row = {'name': name, 'command': command, 'exit': code,
               'seconds': round(time.monotonic() - started, 2), 'verdict': 'pass' if code == 0 else 'fail'}
        text = path.read_text()
        if reports:
            source = cwd / reports
            dest = evidence / (name + '-xml')
            if source.exists():
                shutil.copytree(source, dest)
                row['suites'] = junit(dest)
            else:
                row['suites'] = []
            row['tests'] = sum(x['tests'] for x in row['suites'])
            row['skipped'] = sum(x['skipped'] for x in row['suites'])
            row['executed'] = row['tests'] - row['skipped']
            if row['executed'] <= 0 or any(x['failures'] or x['errors'] for x in row['suites']):
                row['verdict'] = 'fail'
        elif require_tests:
            row['tests'], row['skipped'] = output_test_counts(text)
            row['executed'] = row['tests'] - row['skipped']
            if row['executed'] <= 0:
                row['verdict'] = 'fail'
                row['coverageError'] = 'command returned no attributable nonzero test summary'
        row['coverageNotes'] = [line for line in text.splitlines()
                                if 'NO-SOURCE' in line or 'skipping in-process' in line or '[warn]' in line]
        results.append(row)
        current = source_identity()
        row['sourceSha256After'] = current['sha256']
        save()
        print(json.dumps({k: v for k, v in row.items() if k not in ('command', 'suites', 'coverageNotes')}), flush=True)
        return row['verdict'] == 'pass'

    (evidence / 'run.json').write_text(json.dumps({
        'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'root': str(ROOT), 'startUTC': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'part': args.part, 'containers': args.containers, 'fetchDependencies': args.fetch_dependencies, 'python': str(args.python),
        'selectedStates': args.state, 'sourceSha256AtStart': source['sha256'],
        'javaHome': str(args.java_home), 'profile': 'local synthetic; no live cluster, private tape or external-engine upgrade'}, indent=2))
    if args.part in ('states', 'all'):
        available = {p.name[len('test-state-'):-3]: p for p in (ROOT / 'scripts').glob('test-state-YU*.sh')}
        if args.state and any(s not in available for s in args.state):
            parser.error('unknown --state selection')
        selected = sorted(available[s] for s in args.state) if args.state else sorted(available.values())
        for path in selected:
            if path.name == 'test-state-YU01-lmax-sequencer.sh':
                deferred(path.stem, 'Requires a separately assigned state009 live Compose rig; retired single-BLP tier is not started.')
            else:
                run(path.stem, ['bash', str(path)])
        # Inventory every foundational live wrapper instead of silently omitting it.
        for path in sorted((ROOT / 'scripts').glob('test-state-0*.sh')):
            deferred(path.stem, 'Live HTTP/Compose/Kubernetes/browser tier; requires its own fixture/rig scope before execution.')

    if args.part in ('components', 'all'):
        if not run('final-YU18-generation', ['bash', 'pipeline/generate-state.sh', 'YU18-risk-integration']):
            return 1
        generated = ROOT / 'generated/code/target-generated'
        run('generated-ri31-guard-controls', [str(args.python), str(generated / 'scripts/tests/test-suite-profiles/test_guards.py')], require_tests=True)
        run('suite-report-guard-selftest', ['bash', 'scripts/ci/assert-suites-executed.sh', '--selftest'])
        run('ri31-guard-controls', [str(args.python), 'scripts/tests/test-suite-profiles/test_guards.py'], require_tests=True)
        for module in ['order-matcher', 'execution-algo-engine', 'trade-processor', 'position-service',
                       'account-service', 'aeron-replication-sidecar', 'trade-service']:
            folder = generated / module
            if run(module + '-unit', ['bash', './gradlew', '--no-daemon', '--console=plain', 'cleanTest', 'test'],
                   folder, reports='build/test-results'):
                run(module + '-coverage', ['bash', str(ROOT / 'scripts/ci/assert-suites-executed.sh'), str(folder)])
        for module in ['account-service-specfirst', 'trade-service-specfirst',
                       'position-service-specfirst', 'trade-processor-specfirst']:
            folder = ROOT / 'templates' / module
            if run('baseline-' + module, ['bash', './gradlew', '--no-daemon', '--console=plain', 'cleanTest', 'test'],
                   folder, reports='build/test-results'):
                run('baseline-' + module + '-coverage',
                    ['bash', str(ROOT / 'scripts/ci/assert-suites-executed.sh'), str(folder)])
        if args.containers:
            for module in ['trade-processor', 'account-service', 'position-service']:
                run(module + '-containers', ['bash', './gradlew', '--no-daemon', '--console=plain',
                                            'cleanIntegrationTest', 'integrationTest'], generated / module,
                    reports='build/test-results/integrationTest')
        else:
            deferred('composed-Testcontainers', 'Use --containers to execute the three ephemeral Docker integration tiers.')
        for module in ['reference-data', 'price-publisher']:
            folder = generated / module
            if run(module + '-npm-install', npm_install, folder):
                if module == 'reference-data':
                    run(module + '-unit', ['npm', 'test', '--', '--runInBand', '--json',
                                          '--outputFile=' + str(evidence / 'reference-jest.json')], folder)
                    report = evidence / 'reference-jest.json'
                    data = json.loads(report.read_text()) if report.exists() else {}
                    if not data.get('numPassedTests') or not data.get('success'):
                        results[-1]['verdict'] = 'fail'
                    results[-1]['tests'] = data.get('numTotalTests', 0)
                    results[-1]['skipped'] = data.get('numPendingTests', 0)
                    results[-1]['executed'] = data.get('numPassedTests', 0) + data.get('numFailedTests', 0)
                    save()
                else:
                    run(module + '-unit', ['npm', 'test'], folder, require_tests=True)
        baseline_ref = ROOT / 'templates/reference-data-specfirst'
        if run('baseline-reference-npm-install', npm_install, baseline_ref):
            run('baseline-reference-unit', ['npm', 'test'], baseline_ref, require_tests=True)
        run('tick-store-synthetic', [str(args.python), '-m', 'pytest', 'tests', '-q'],
            generated / 'tick-store', require_tests=True)
        run('eod-source', ['bash', 'scripts/test-state-YU18-risk-integration.sh'], require_tests=True)
        run('eod-generated', ['bash', 'scripts/test-state-YU18-risk-integration.sh',
                              str(generated / 'eod-risk-bundles')], require_tests=True)
        run('eod-checkout-bytes', [str(args.python), 'scripts/test-state-YU18-checkout.py'])
        run('composition-contracts', [str(args.python), '-m', 'unittest', 'discover',
                                      '-s', 'scripts/ci/tests', '-v'], require_tests=True)
        run('compsci-offline-controls', [str(args.python), '-m', 'unittest', 'discover',
                                        '-s', 'scripts/gcp-compsci', '-v'], require_tests=True)
        for folder in sorted((ROOT / 'scripts/tests').iterdir()):
            if folder.is_dir() and list(folder.glob('test_*.py')):
                run('script-' + folder.name, [str(args.python), '-m', 'unittest', 'discover',
                                              '-s', str(folder), '-v'], require_tests=True)
        node_files = sorted((ROOT / 'web-front-end-console').glob('*.test.mjs'))
        node_files += sorted((ROOT / 'web-front-end-console-combined-prototype').glob('test-*.mjs'))
        run('console-and-desk-node', ['node', '--test', *map(str, node_files)], require_tests=True)
        for name in ['web-front-end-console', 'web-front-end-console-combined-prototype']:
            folder = ROOT / name
            if run(name + '-npm-install', npm_install, folder):
                run(name + '-angular', ['npm', 'test', '--', '--watch=false', '--browsers=ChromeHeadless'], folder,
                    require_tests=True)
                run(name + '-build', ['npm', 'run', 'build', '--', '--configuration=production'], folder)
        projects = list((generated / 'people-service').rglob('*Tests*.csproj'))
        for project in projects:
            run('people-generated-tests', ['dotnet', 'test', str(project), '--logger',
                                            'trx;LogFileName=' + str(evidence / 'people-generated.trx')])
        if not projects:
            deferred('people-generated-test-coverage', 'Generated people-service has no test project; a build is not test coverage.')
            project = next((generated / 'people-service').rglob('*.csproj'))
            run('people-generated-build', ['dotnet', 'build', str(project), '-c', 'Release'])
        baseline_people = ROOT / 'templates/people-service-specfirst/PeopleService.Tests/PeopleService.Tests.csproj'
        run('people-baseline-unit', ['dotnet', 'test', str(baseline_people), '--logger',
                                     'trx;LogFileName=' + str(evidence / 'people-baseline.trx')])
        report = evidence / 'people-baseline.trx'
        if report.exists():
            counters = ET.parse(report).getroot().find('.//{*}Counters')
            results[-1]['tests'] = int(counters.get('total', '0')) if counters is not None else 0
        else:
            results[-1]['tests'] = 0
        if not results[-1]['tests']:
            results[-1]['verdict'] = 'fail'
            results[-1]['coverageError'] = 'no attributable .NET test results'
        save()
        for name in ['frontmatter', 'root-gates', 'readiness', 'spec-coverage']:
            command = {'frontmatter': 'tools/validate-frontmatter.sh',
                       'root-gates': 'pipeline/speckit/validate-root-spec-kit-gates.sh',
                       'readiness': 'pipeline/speckit/validate-speckit-readiness.sh',
                       'spec-coverage': 'pipeline/verify-spec-coverage.sh'}[name]
            run(name, ['bash', command])
    if args.part in ('components', 'website', 'all'):
        website = ROOT / 'website'
        if (website / 'package-lock.json').exists():
            if run('website-npm-install', npm_install, website):
                run('website-build', ['npm', 'run', 'build'], website)
    if args.part in ('extras', 'all'):
        run('extras-generated-ri31-guard-controls', [str(args.python), str(ROOT / 'generated/code/target-generated/scripts/tests/test-suite-profiles/test_guards.py')], require_tests=True)
        run('extras-ri31-guard-controls', [str(args.python), 'scripts/tests/test-suite-profiles/test_guards.py'], require_tests=True)
        run('state-component-packs', [str(args.python), 'scripts/check-state-components.py', 'specs/YU18-risk-integration'])
        matcher = ROOT / 'generated/code/target-generated/order-matcher'
        for task in ['riskNoGcTest', 'noGcTest']:
            run('matcher-' + task, ['bash', './gradlew', '--no-daemon', '--console=plain',
                                    'clean' + task[0].upper() + task[1:], task],
                matcher, reports='build/test-results/' + task)
        # These use exact source, disposable JVMs/SQL containers or owned synthetic files.
        for folder in sorted((ROOT / 'scripts/diagnostics').iterdir()):
            if folder.is_dir() and list(folder.glob('test_*.py')):
                run('diagnostic-' + folder.name, [str(args.python), '-m', 'unittest', 'discover',
                                                '-s', str(folder), '-v'], require_tests=True)
        for name in ['generated-runtime-harness', 'generated-ci-assets', 'local-jvm-jar-build',
                     'position-blotter-upsert-contract', 'web-angular-baseline-ux-contract',
                     'web-angular-pricing-ux-contract', 'portfolio-generator']:
            run('script-' + name, ['bash', 'scripts/test-' + name + '.sh'])
        if args.containers:
            run('startup-probe-sql-controls', ['bash', 'scripts/ri07/test-startup-probe-sql.sh'])
        else:
            deferred('startup-probe-sql-controls', 'Use --containers for the owned MariaDB fixture.')
        # Live scripts can submit orders or select a shared kubeconfig. Name each omitted entry.
        live_names = ['account-service-overlay', 'api-explorer-pubsub-inspector', 'database-overlay',
                      'messaging-008-pricing-awareness-market-data', 'messaging-009-order-management-matcher',
                      'messaging-YU01-lmax-sequencer', 'order-create-pubsub-smoke', 'people-service-overlay',
                      'position-service-overlay', 'realtime-account-stream-overlay', 'realtime-order-stream-overlay',
                      'reference-data-overlay', 'trade-feed-overlay', 'trade-processor-overlay',
                      'trade-service-overlay', 'web-angular-overlay']
        for name in live_names:
            deferred('test-' + name, 'Requires a separately assigned live fixture; may submit or mutate shared state.')
        for path in sorted((ROOT / 'scripts').glob('test*.ps1')):
            deferred(path.name, 'PowerShell-specific wrapper; not executed in this macOS Bash profile.')
        for name in ['scripts/ri07/test_order_types_live.py', 'scripts/yu11/test-aeron-ha-compose.sh',
                     'scripts/yu11/test-aeron-ha-kind.sh', 'scripts/yu11/test-aeron-loss-replay.sh',
                     'scripts/yu15/test_cluster_prometheus.py', 'scripts/test-console-image.py']:
            deferred(name, 'Requires an explicitly selected live rig or a matching built image; not source-unit coverage.')
    if args.part in ('extras', 'legacy', 'all'):
        for name, folder in [('legacy-generated-angular', ROOT / 'generated/code/target-generated/web-front-end/angular'),
                             ('legacy-baseline-angular', ROOT / 'templates/web-front-end/angular')]:
            if name == 'legacy-generated-angular':
                # State014 adds FDC3 to this manifest; the fast state walk skips lock resolution.
                # Refresh only the owned generated lock, preserving the existing graph where possible.
                shutil.copy2(folder / 'package-lock.json', evidence / (name + '-lock-before.json'))
                if not run(name + '-lock-sync', ['npm', 'install', '--package-lock-only', '--ignore-scripts',
                                                 *([] if args.fetch_dependencies else ['--offline']),
                                                 '--no-audit', '--no-fund'], folder):
                    continue
                shutil.copy2(folder / 'package-lock.json', evidence / (name + '-lock-after.json'))
            if run(name + '-npm-install', npm_install, folder):
                run(name + '-unit', ['npm', 'test', '--', '--watch=false', '--browsers=ChromeHeadless'],
                    folder, require_tests=True)
                run(name + '-build', ['npm', 'run', 'build'], folder)
    failed = [x['name'] for x in results if x['verdict'] == 'fail']
    omitted = [x['name'] for x in results if x['verdict'] == 'not executed']
    print(json.dumps({'failed': failed, 'notExecuted': omitted, 'evidence': str(evidence)}), flush=True)
    return bool(failed)


if __name__ == '__main__':
    sys.exit(main())
