"""Run one closed synthetic portfolio on task-local HTTP/SQLite, publish GCS custody."""
import argparse
import hashlib
import json
import os
import signal
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path

from batch_artifacts import GCS, MAX_FILE, MAX_TOTAL, require, safe_run, verify_payload, connected_record
from client import Driver
from corpus import digest, encoded


def stop(proc):
    if proc is None:
        return
    if proc.poll() is None:
        os.killpg(proc.pid, signal.SIGTERM)
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=5)
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def run_local(raw, root, manifest, runtime):
    require(hashlib.sha256(raw).hexdigest() == manifest['request_sha256'], 'only the immutable packaged request is admitted')
    require(not any((p / '.git').exists() for p in (root, *root.parents)), 'evidence must be outside Git')
    mapped = manifest.get('profile') == 'traderx-synthetic-usd-bill-v1'
    if mapped:
        from export_mapping import verify
        require(runtime['run_id'] == manifest['mapping']['expected']['run_id'], 'mapped runtime run ID mismatch')
        require(verify(Path(__file__).parent / 'mapped', manifest['mapping']['expected']) == manifest['mapping'],
                'packaged mapping mismatch')
    root.mkdir(parents=True, mode=0o700, exist_ok=False)
    if mapped:
        shutil.copytree(Path(__file__).parent / 'mapped', root / 'mapped')
    (root / 'input.json').write_bytes(raw)
    (root / 'build.json').write_bytes(encoded(manifest) + b'\n')
    (root / 'runtime.json').write_bytes(encoded(runtime) + b'\n')
    env = dict(os.environ, JAX_PLATFORMS='cpu', JAX_ENABLE_X64='true', PYTHONPATH='',
               JAX_RISK_JOB_QUEUE=str(root / 'jobs.sqlite'), JAX_RISK_WORKER='spawn',
               JAX_EOD_STORE_ROOT=str(root / 'eod-unused'), JAX_COMPILATION_CACHE_DIR=str(root / 'cache'))
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    origin = 'http://127.0.0.1:' + str(port)
    proc = None
    try:
        with (root / 'service.log').open('xb') as log:
            proc = subprocess.Popen([sys.executable, '-m', 'uvicorn', 'engine.api.app:app', '--host',
                                     '127.0.0.1', '--port', str(port)], cwd=root, env=env,
                                    stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            ready = time.monotonic() + 90
            while True:
                require(proc.poll() is None, 'API exited before ready')
                try:
                    with urllib.request.urlopen(origin + '/portfolio/capabilities', timeout=2) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                require(time.monotonic() < ready, 'API readiness timeout')
                time.sleep(0.25)
            result = Driver(origin, root / 'job', deadline=300, interval=0.5, max_polls=600).run(
                root / 'input.json', submission_id=runtime['run_id'])
            require(result['outcome'] == 'DONE', 'portfolio failed: ' + result['outcome'])
    finally:
        stop(proc)
    verify_unused_eod(root / 'eod-unused')
    accepted = verify_payload(root, manifest)
    if mapped:
        (root / 'connected-result.json').write_bytes(encoded(connected_record(root, manifest, accepted)) + b'\n')
    return accepted


def verify_unused_eod(eod_root):
    expected_eod_dirs = {'attempts', 'pointers', 'submissions', 'tmp'}
    eod_entries = list(eod_root.rglob('*'))
    require(eod_root.is_dir() and not eod_root.is_symlink() and
            {p.relative_to(eod_root).as_posix() for p in eod_entries} == expected_eod_dirs and
            all(p.is_dir() and not p.is_symlink() for p in eod_entries),
            'unexpected EOD output or missing initial store directories')


def publish(storage, root, prefix):
    records, total = [], 0
    paths = sorted(p for p in root.rglob('*') if p.is_file() and 'cache' not in p.relative_to(root).parts)
    require(len(paths) <= 1010, 'too many artifacts')
    for path in paths:
        require(not path.is_symlink() and path.stat().st_size <= MAX_FILE, 'unsafe/oversized artifact')
        if not path.stat().st_size:
            continue
        total += path.stat().st_size
        require(total <= MAX_TOTAL, 'artifact budget exceeded')
        name = path.relative_to(root).as_posix()
        records.append(dict(storage.put(prefix + name, path.read_bytes()), path=name))
    return records


def main():
    def terminated(signum, frame):
        raise RuntimeError('task terminated by signal ' + str(signum))
    signal.signal(signal.SIGTERM, terminated)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--local-input', type=Path)
    parser.add_argument('--evidence', type=Path, default=Path('/tmp/risk-cpu-proof'))
    args = parser.parse_args()
    from verify_installed import verify
    manifest = verify(Path(__file__).with_name('build.json'))
    run = safe_run(os.environ.get('PILOT_RUN_ID', 'risk-cpu-build-proof'))
    runtime = {'run_id': run, 'image': os.environ.get('PILOT_IMAGE', 'local-build-proof'),
               'batch_uid': os.environ.get('BATCH_JOB_UID', ''), 'synthetic': True,
               'financial_validation': False}
    if args.local_input:
        require(args.local_input.stat().st_size <= 1024 * 1024, 'oversized local input')
        accepted = run_local(args.local_input.read_bytes(), args.evidence.resolve(), manifest, runtime)
        print(json.dumps({'event': 'local_cpu_portfolio_verified', 'verification': accepted,
                          'engine_revision': manifest['engine_revision']}), flush=True)
        return 0
    require(bool(runtime['batch_uid']) and os.environ.get('BATCH_TASK_INDEX') == '0', 'one Batch task required')
    image = runtime['image']
    require('@sha256:' in image and len(image.rsplit('@sha256:', 1)[1]) == 64, 'digest-pinned image required')
    storage = GCS(os.environ['PILOT_BUCKET'])
    pin = {'bucket': storage.bucket, 'object': 'inputs/' + run + '/request.json',
           'generation': os.environ['PILOT_INPUT_GENERATION'], 'sha256': manifest['request_sha256']}
    raw = storage.get(pin['object'], pin['generation'])
    require(hashlib.sha256(raw).hexdigest() == pin['sha256'], 'pinned input checksum mismatch')
    attempt = 'runs/' + run + '/attempts/' + uuid.uuid4().hex + '/'
    # This fence survives task/VM loss. A duplicate execution refuses before pricing.
    storage.put('runs/' + run + '/started.json', encoded(dict(runtime, input=pin, attempt_prefix=attempt)))
    root = args.evidence.resolve()
    failure = None
    try:
        accepted = run_local(raw, root, manifest, runtime)
    except Exception as exc:
        failure = type(exc).__name__ + ': ' + str(exc)
        root.mkdir(parents=True, exist_ok=True)
        (root / 'failure.json').write_bytes(encoded({'status': 'FAILED', 'error': failure}) + b'\n')
    files = publish(storage, root, attempt)
    if failure:
        raise RuntimeError(failure)
    receipt = dict(version=1, status='VERIFIED', financial_validation=False, run_id=run, input=pin,
                   image=image, batch_uid=runtime['batch_uid'], attempt_prefix=attempt, files=files,
                   engine_revision=manifest['engine_revision'], numerical_revision=manifest['numerical_revision'],
                   build_sha256=digest(root / 'build.json'), verification=accepted)
    complete = storage.put('runs/' + run + '/completion.json', encoded(receipt) + b'\n')
    print(json.dumps({'event': 'cloud_cpu_portfolio_verified', 'receipt': complete,
                      'verification': accepted}), flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
