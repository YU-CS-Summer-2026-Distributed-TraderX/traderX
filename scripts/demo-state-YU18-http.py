#!/usr/bin/env python3
"""Exercise the local HTTP mock with a real worker process and persistent restart lookup."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def main():
    os.umask(0o077)
    root = Path(__file__).resolve().parents[1]
    component = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else (
        root / 'specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles')
    output = Path(tempfile.mkdtemp(prefix='traderx-http-demo-')).resolve()
    worker = None
    log = None

    def command(script, *args):
        result = subprocess.run([sys.executable, str(component / script), *map(str, args)],
                                capture_output=True, text=True, timeout=30, check=True)
        return json.loads(result.stdout)

    def start(number):
        nonlocal worker, log
        log = (output / f'worker-{number}.log').open('w')
        worker = subprocess.Popen([sys.executable, str(component / 'fake_worker.py'),
                                   '--state', str(output / 'worker')], stdout=log, stderr=log)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if worker.poll() is not None:
                raise RuntimeError(f'worker exited; inspect {output}')
            lines = (output / f'worker-{number}.log').read_text().splitlines()
            if lines:
                return json.loads(lines[0])['url']
            time.sleep(0.05)
        raise TimeoutError(f'worker startup timed out; inspect {output}')

    def stop():
        nonlocal worker, log
        if worker is not None:
            worker.terminate()
            try:
                worker.wait(timeout=5)
            except subprocess.TimeoutExpired:
                worker.kill()
                worker.wait(timeout=5)
            worker = None
        if log is not None:
            log.close()
            log = None

    def consume(state, endpoint):
        def ctl(*args):
            return command('coordinator.py', '--state', output / state, '--http-worker', endpoint, *args)
        first = ctl('discover', output / 'inbox')
        assert len(first['queued']) == 1
        completed = ctl('run')
        assert len(completed['jobs']) == 1
        job = completed['jobs'][0]
        assert job['status'] == 'MOCK_COMPLETE' and job['resultIntegrity'] == 'VERIFIED'
        assert job['selectedMockResult'] and not job['usableForRisk']
        assert len(ctl('discover', output / 'inbox')['duplicates']) == 1
        repeated = ctl('run')
        assert repeated['processed'] == [] and len(repeated['jobs'][0]['attempts']) == 1
        (output / f'{state}-status.json').write_text(json.dumps(repeated, indent=2)+'\n')
        result_dir = output / state / job['result_path']
        payload = json.loads((result_dir / 'results.json').read_text())
        assert payload['coverage'] == {'submitted': 3, 'priced': 0}
        return json.loads((result_dir / 'transport.json').read_text())

    try:
        command('bundle.py', 'build', '--positions', component / 'tests/fixtures/exchange/positions.csv',
                '--contracts', component / 'tests/fixtures/exchange/contracts.csv',
                '--epoch', 'synthetic-http-cli-demo', '--valuation-time', '2025-06-02T20:00:00Z',
                '--origin', 'synthetic', '--output', output / 'inbox' / 'cut')
        first = consume('coordinator', start(1))
        result = output / 'worker' / first['workloadKey'] / 'result' / 'results.json'
        original = (result.read_bytes(), result.stat().st_mtime_ns)
        stop()
        second = consume('new-consumer', start(2))
        assert second == first
        assert (result.read_bytes(), result.stat().st_mtime_ns) == original
        print(json.dumps({'ok': True, 'evidence': str(output), 'items': 3, 'priced': 0,
                          'workerRestartLookup': 'verified', 'workerAttemptId': first['workerAttemptId'],
                          'eachConsumerJobs': 1, 'eachConsumerAttempts': 1, 'usableForRisk': False}, indent=2))
    finally:
        stop()


if __name__ == '__main__':
    main()
