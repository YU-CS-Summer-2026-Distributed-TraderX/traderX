#!/usr/bin/env python3
"""Single-host EOD coordinator. Local mock, W0 and provisional synthetic pricing intake."""
import argparse
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import sqlite3
import uuid

import bundle

from worker_protocol import PROFILE, HTTP_PROFILE, W0_PROFILE, PRICING_PROFILE, Deferred


def now():
    return datetime.now(timezone.utc).isoformat()


def decode_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            bundle.require(key not in result, f'duplicate JSON key: {key}')
            result[key] = value
        return result
    def invalid(value):
        raise ValueError(f'non-finite JSON value: {value}')
    return json.loads(data, object_pairs_hook=pairs, parse_constant=invalid)


def load_json(path):
    return decode_json(path.read_bytes())


def private_directory(path):
    bundle.require(not path.is_symlink(), 'private directory must not be a symlink')
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    bundle.require(path.is_dir() and path.stat().st_uid == os.getuid()
                   and path.stat().st_mode & 0o077 == 0,
                   'state directory must be owned by you with mode 0700')


class MockAdapter:
    profile = PROFILE
    completion_status = 'MOCK_COMPLETE'
    result_files = {'results.json'}

    def execute(self, input_directory, result_directory):
        bundle.mock(input_directory, result_directory)

    def validate_result(self, input_directory, result_directory):
        manifest, parsed = bundle.validate(input_directory)
        root = Path(result_directory)
        bundle.require(not root.is_symlink() and root.is_dir(), 'invalid result directory')
        bundle.require({p.name for p in root.iterdir()} == self.result_files, 'invalid result files')
        path = root / 'results.json'
        bundle.require(path.is_file() and not path.is_symlink(), 'invalid result file')
        data = path.read_bytes()
        result = load_json(path)
        expected_top = {'schema', 'bundleId', 'clusterEpoch', 'valuationTime', 'engine',
                        'synthetic', 'usableForRisk', 'status', 'coverage', 'items'}
        bundle.require(isinstance(result, dict) and set(result) == expected_top, 'invalid result fields')
        for key in ('bundleId', 'clusterEpoch', 'valuationTime'):
            bundle.require(result[key] == manifest[key], f'result input mismatch: {key}')
        bundle.require(result['schema'] == 'traderx.mock-result.v1'
                       and result['engine'] == 'transport-mock'
                       and result['status'] == 'MOCK_COMPLETE'
                       and result['synthetic'] is True and result['usableForRisk'] is False,
                       'result is not a non-pricing mock')
        expected = {}
        for kind, (_, rows) in parsed.items():
            field = 'security' if kind == 'positions' else 'contractId'
            for row in rows:
                expected[(kind, row['accountId'], row[field])] = row['currency']
        bundle.require(isinstance(result['items'], list), 'items must be an array')
        seen = set()
        for item in result['items']:
            bundle.require(isinstance(item, dict), 'invalid result item')
            field = 'security' if item.get('source') == 'positions' else 'contractId'
            bundle.require(set(item) == {'source', 'accountId', field, 'currency', 'status',
                                         'reason', 'npv', 'greeks'}, 'invalid result item fields')
            key = (item['source'], item['accountId'], item[field])
            bundle.require(key in expected and key not in seen, 'unknown or duplicate result identity')
            bundle.require(item['currency'] == expected[key] and item['status'] == 'NOT_PRICED'
                           and item['reason'] == 'MOCK_ONLY' and item['npv'] is None
                           and item['greeks'] == {}, 'mock must not claim pricing')
            seen.add(key)
        bundle.require(seen == set(expected), 'missing result identities')
        coverage = result['coverage']
        bundle.require(isinstance(coverage, dict) and set(coverage) == {'submitted', 'priced'}
                       and type(coverage['submitted']) is int and type(coverage['priced']) is int
                       and coverage == {'submitted': len(expected), 'priced': 0}, 'invalid coverage')
        return bundle.digest(data)


class Coordinator:
    """Hold an OS lock for the entire lifetime, including worker execution.

    A new process can recover RUNNING rows only after acquiring this lock. No clock-based
    lease can accidentally reclaim a slow active worker. Local filesystem only.
    """
    def __init__(self, state, adapter=None):
        self.state = Path(state).absolute()
        self.adapter = adapter or MockAdapter()
        # New worker semantics require a new adapter implementation and profile version.
        bundle.require(self.adapter.profile in (PROFILE, HTTP_PROFILE, W0_PROFILE, PRICING_PROFILE), 'unsupported adapter profile')
        self.db = None
        self.lock = None

    def __enter__(self):
        bundle.require(not any((parent / '.git').exists() for parent in (self.state.resolve(), *self.state.resolve().parents)),
                       'state directory must be outside a git checkout')
        private_directory(self.state)
        try:
            fd = os.open(self.state / 'coordinator.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            self.lock = os.fdopen(fd, 'r+')
            try:
                fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise ValueError('coordinator already in use') from exc
            for name in ('inputs', 'results'):
                private_directory(self.state / name)
            db_path = self.state / 'jobs.sqlite3'
            bundle.require(not db_path.is_symlink(), 'database must not be a symlink')
            fd = os.open(db_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            os.close(fd)
            os.chmod(db_path, 0o600)
            self.db = sqlite3.connect(db_path)
            self.db.row_factory = sqlite3.Row
            self.db.execute('PRAGMA foreign_keys=ON')
            self.db.execute('PRAGMA synchronous=FULL')
            version = self.db.execute('PRAGMA user_version').fetchone()[0]
            bundle.require(version in (0, 1), 'unsupported coordinator database version')
            self.db.executescript('''
                CREATE TABLE IF NOT EXISTS jobs (
                    job_id TEXT PRIMARY KEY, bundle_id TEXT NOT NULL, manifest TEXT NOT NULL,
                    profile TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL, error TEXT, result_path TEXT, result_hash TEXT);
                CREATE TABLE IF NOT EXISTS attempts (
                    attempt_id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES jobs(job_id),
                    status TEXT NOT NULL, started_at TEXT NOT NULL, ended_at TEXT,
                    error TEXT, result_path TEXT NOT NULL);
                PRAGMA user_version=1;
            ''')
            profiles = [json.loads(r[0]) for r in self.db.execute('SELECT DISTINCT profile FROM jobs')]
            bundle.require(all(p == self.adapter.profile for p in profiles),
                           'state uses another adapter profile; choose its adapter or a separate state directory')
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, *args):
        if self.db is not None:
            self.db.close()
            self.db = None
        if self.lock is not None:
            self.lock.close()
            self.lock = None

    def discover(self, inbox):
        inbox = Path(inbox)
        bundle.require(inbox.is_dir() and not inbox.is_symlink(), 'inbox must be a local directory')
        report = {'queued': [], 'duplicates': [], 'invalid': [], 'incomplete': []}
        for path in sorted(inbox.iterdir()):
            if path.name.startswith('.'):
                continue  # publisher staging is not ready work
            if path.is_symlink():
                report['invalid'].append({'path': str(path), 'reason': 'symlink candidate'})
                continue
            if not path.is_dir():
                continue
            if not (path / 'manifest.json').exists():
                report['incomplete'].append(str(path))
                continue
            try:
                manifest, _ = bundle.validate(path)
                bundle_id = manifest['bundleId']
                job_id = bundle.digest(bundle.encoded({'bundleId': bundle_id, 'profile': self.adapter.profile}))
                # Copy privately, then validate those exact bytes, so later inbox changes cannot
                # change the accepted workload. Existing snapshots are verified, never overwritten.
                target = self.state / 'inputs' / bundle_id
                if not target.exists():
                    bundle.publish_directory(target, {p.name: p.read_bytes() for p in path.iterdir()})
                stored, _ = bundle.validate(target)
                bundle.require(stored == manifest, 'snapshot differs from discovered bundle')
                with self.db:
                    cursor = self.db.execute('INSERT OR IGNORE INTO jobs VALUES (?,?,?,?,?,?,?,?,?,?)',
                        (job_id, bundle_id, json.dumps(manifest), json.dumps(self.adapter.profile), 'QUEUED',
                         now(), now(), None, None, None))
                report['queued' if cursor.rowcount else 'duplicates'].append(job_id)
            except (ValueError, OSError, KeyError, TypeError) as exc:
                report['invalid'].append({'path': str(path), 'reason': str(exc)})
        return report

    def _finish(self, job, attempt):
        manifest, _ = bundle.validate(self.state / 'inputs' / job['bundle_id'])
        bundle.require(manifest == json.loads(job['manifest']), 'stored input changed')
        result_hash = self.adapter.validate_result(self.state / 'inputs' / job['bundle_id'],
                                                   self.state / attempt['result_path'])
        with self.db:
            self.db.execute('UPDATE attempts SET status=?, ended_at=? WHERE attempt_id=?',
                            (self.adapter.completion_status, now(), attempt['attempt_id']))
            self.db.execute('UPDATE jobs SET status=?, updated_at=?, error=NULL, result_path=?, '
                            'result_hash=? WHERE job_id=?',
                            (self.adapter.completion_status, now(), attempt['result_path'], result_hash, job['job_id']))

    def _failed(self, job, attempt, error, status='FAILED'):
        with self.db:
            self.db.execute('UPDATE attempts SET status=?, ended_at=?, error=? WHERE attempt_id=?',
                            (status, now(), error, attempt['attempt_id']))
            self.db.execute('UPDATE jobs SET status=?, updated_at=?, error=? WHERE job_id=?',
                            ('QUEUED' if status == 'INTERRUPTED' else 'FAILED', now(), error, job['job_id']))

    def recover(self):
        recovered = []
        for job in self.db.execute("SELECT * FROM jobs WHERE status='RUNNING'").fetchall():
            attempt = self.db.execute("SELECT * FROM attempts WHERE job_id=? AND status='RUNNING'",
                                      (job['job_id'],)).fetchone()
            bundle.require(attempt is not None, 'running job missing its attempt')
            path = self.state / attempt['result_path']
            if path.exists() or path.is_symlink():
                try:
                    self._finish(job, attempt)
                except (ValueError, OSError, KeyError, TypeError) as exc:
                    self._failed(job, attempt, f'RESULT_INVALID: {exc}')
            elif getattr(self.adapter, 'resumable', False):
                self._work(job, attempt)
            else:
                self._failed(job, attempt, 'INTERRUPTED: worker ended before result publication', 'INTERRUPTED')
            recovered.append(job['job_id'])
        return recovered

    def _work(self, job, attempt):
        phase = 'INPUT_INVALID'
        try:
            manifest, _ = bundle.validate(self.state / 'inputs' / job['bundle_id'])
            bundle.require(manifest == json.loads(job['manifest']), 'stored input changed')
            phase = 'WORKER_FAILURE'
            self.adapter.execute(self.state / 'inputs' / job['bundle_id'], self.state / attempt['result_path'])
            phase = 'RESULT_INVALID'
            self._finish(job, attempt)
        except Deferred as exc:
            with self.db:
                self.db.execute('UPDATE jobs SET updated_at=?, error=? WHERE job_id=?',
                                (now(), f'{getattr(self.adapter, "pending_label", "REMOTE_PENDING")}: {exc}', job['job_id']))
        except (ValueError, OSError, KeyError, TypeError, RuntimeError) as exc:
            self._failed(job, attempt, f'{phase}: {exc}')

    def run(self):
        recovered = self.recover()
        processed = []
        for job in self.db.execute("SELECT * FROM jobs WHERE status='QUEUED' ORDER BY created_at, job_id").fetchall():
            attempt_id = uuid.uuid4().hex
            private_directory(self.state / 'results' / job['job_id'])
            relative = f'results/{job["job_id"]}/{attempt_id}'
            with self.db:
                self.db.execute('INSERT INTO attempts VALUES (?,?,?,?,?,?,?)',
                                (attempt_id, job['job_id'], 'RUNNING', now(), None, None, relative))
                self.db.execute('UPDATE jobs SET status=?, updated_at=?, error=NULL WHERE job_id=?',
                                ('RUNNING', now(), job['job_id']))
            attempt = self.db.execute('SELECT * FROM attempts WHERE attempt_id=?', (attempt_id,)).fetchone()
            self._work(job, attempt)
            processed.append(job['job_id'])
        return {'recovered': recovered, 'processed': processed, **self.status()}

    def retry(self, job_id):
        with self.db:
            cursor = self.db.execute("UPDATE jobs SET status='QUEUED', updated_at=? WHERE job_id=? AND status='FAILED'",
                                     (now(), job_id))
            bundle.require(cursor.rowcount == 1, 'retry requires a failed job')
        return {'queued': job_id}

    def status(self):
        jobs = [dict(row) for row in self.db.execute('SELECT * FROM jobs ORDER BY created_at, job_id')]
        newest = {}
        for job in jobs:
            m = json.loads(job.pop('manifest'))
            job['profile'] = json.loads(job['profile'])
            job['cut'] = m['cut']
            job['clusterEpoch'] = m['clusterEpoch']
            job['valuationTime'] = m['valuationTime']
            # Ordering is only within one epoch and business date, never across unrelated runs.
            scope = (m['clusterEpoch'], m['cut']['sessionDate'])
            rank = (int(m['cut']['consensusSequence']), int(m['cut']['priceSnapshotVersion']))
            newest[scope] = max(newest.get(scope, rank), rank)
            job['_scope'], job['_rank'] = scope, rank
        for job in jobs:
            scope, rank = job.pop('_scope'), job.pop('_rank')
            peers = [j for j in jobs if j['clusterEpoch'] == scope[0]
                     and j['cut']['sessionDate'] == scope[1]
                     and (int(j['cut']['consensusSequence']), int(j['cut']['priceSnapshotVersion'])) == rank]
            job['currentCut'] = rank == newest[scope] and len(peers) == 1
            job['selectionAmbiguous'] = rank == newest[scope] and len(peers) > 1
            job['usableForRisk'] = False
            job['attempts'] = [dict(r) for r in self.db.execute(
                'SELECT * FROM attempts WHERE job_id=? ORDER BY started_at, attempt_id', (job['job_id'],))]
            if job['status'] in ('MOCK_COMPLETE', 'W0_VALIDATED', 'SYNTHETIC_PRICING_VALIDATED'):
                try:
                    actual = self.adapter.validate_result(self.state / 'inputs' / job['bundle_id'],
                                                          self.state / job['result_path'])
                    bundle.require(actual == job['result_hash'], 'accepted result hash changed')
                    job['resultIntegrity'] = 'VERIFIED'
                except (ValueError, OSError, KeyError, TypeError) as exc:
                    job['resultIntegrity'] = 'INVALID'
                    job['integrityError'] = str(exc)
            job['selectedMockResult'] = (job['currentCut'] and job['status'] == 'MOCK_COMPLETE'
                                          and job.get('resultIntegrity') == 'VERIFIED')
            job['selectedW0Result'] = (job['currentCut'] and job['status'] == 'W0_VALIDATED'
                                        and job.get('resultIntegrity') == 'VERIFIED')
            if job['profile'] == PRICING_PROFILE:
                job['selectedSyntheticPricingResult'] = (job['currentCut'] and
                    job['status'] == 'SYNTHETIC_PRICING_VALIDATED' and job.get('resultIntegrity') == 'VERIFIED')
            if job['status'] == 'W0_VALIDATED':
                job.update(pricedItems=0, portfolioRiskAvailable=False)
        return {'jobs': jobs, 'usableForRisk': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', required=True, help='private local state directory outside checkout')
    parser.add_argument('--http-worker', help='provisional local fake-worker URL: http://127.0.0.1:PORT')
    parser.add_argument('--w0-results', help='local Alex W0 result directory, files named BUNDLE_ID.json')
    parser.add_argument('--pricing-results', help='opt-in provisional synthetic Alex pricing result directory')
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('discover').add_argument('inbox')
    sub.add_parser('run')
    sub.add_parser('status')
    sub.add_parser('retry').add_argument('job_id')
    args = parser.parse_args()
    # SQLite sidecars and newly made result parents inherit private permissions.
    os.umask(0o077)
    try:
        bundle.require(sum(bool(x) for x in (args.http_worker, args.w0_results, args.pricing_results)) <= 1, 'choose one worker adapter')
        adapter = None
        if args.w0_results:
            from w0_result import W0FileAdapter
            adapter = W0FileAdapter(args.w0_results)
        if args.pricing_results:
            from pricing_result import PricingFileAdapter
            adapter = PricingFileAdapter(args.pricing_results)
        if args.http_worker:
            from http_adapter import HttpAdapter
            adapter = HttpAdapter(args.http_worker)
        with Coordinator(args.state, adapter) as coordinator:
            if args.command == 'discover':
                result = coordinator.discover(args.inbox)
                failed = bool(result['invalid'])
            elif args.command == 'retry':
                result = coordinator.retry(args.job_id)
                failed = False
            else:
                result = getattr(coordinator, args.command)()
                failed = any(j['status'] == 'FAILED' or j.get('resultIntegrity') == 'INVALID'
                             for j in result['jobs'])
            print(json.dumps(result, indent=2, allow_nan=False))
            return 1 if failed else (2 if args.command == 'run' and any(
                j['status'] == 'RUNNING' for j in result['jobs']) else 0)
    except (ValueError, OSError, KeyError, TypeError, sqlite3.Error) as exc:
        parser.exit(1, f'error: {exc}\n')


if __name__ == '__main__':
    raise SystemExit(main())
