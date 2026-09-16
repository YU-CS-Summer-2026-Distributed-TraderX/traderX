#!/usr/bin/env python3
"""Read-only local coordinator snapshot for the console; never runs or recovers jobs."""
import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import bundle
from coordinator import Coordinator, MockAdapter, now
from worker_protocol import PROFILE, HTTP_PROFILE, W0_PROFILE

SCHEMA = 'traderx.eod-job-status.v1'


def snapshot(state):
    state = Path(state).absolute()
    bundle.require(state.is_dir() and not state.is_symlink() and state.stat().st_uid == os.getuid()
                   and state.stat().st_mode & 0o077 == 0, 'private coordinator state unavailable')
    database = state/'jobs.sqlite3'
    bundle.require(database.is_file() and not database.is_symlink(), 'coordinator database unavailable')
    # No Coordinator.__enter__: GET must not create a state, initialise schema, take the worker
    # lock, recover interrupted attempts or contact a worker. BEGIN gives one SQLite snapshot.
    with closing(sqlite3.connect(database.as_uri()+'?mode=ro', uri=True, timeout=1)) as db:
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA query_only=ON')
        db.execute('BEGIN')
        bundle.require(db.execute('PRAGMA user_version').fetchone()[0] == 1, 'unsupported coordinator database')
        profiles = [json.loads(row[0]) for row in db.execute('SELECT DISTINCT profile FROM jobs')]
        bundle.require(len(profiles) <= 1, 'mixed coordinator profiles')
        profile = profiles[0] if profiles else PROFILE
        adapter = MockAdapter()
        if profile == HTTP_PROFILE:
            from http_adapter import HttpAdapter
            adapter = HttpAdapter('http://127.0.0.1:1')  # Validation only; no requests are issued.
        elif profile == W0_PROFILE:
            from w0_result import W0FileAdapter
            adapter = W0FileAdapter(state/'results')  # Intake location is unused for validation.
        else:
            bundle.require(profile == PROFILE, 'unsupported coordinator profile')
        coordinator = Coordinator(state, adapter)
        coordinator.db = db
        result = coordinator.status()
        for job in result['jobs']:
            job['coverage'] = None
            job['pricingAvailable'] = False
            job['portfolioRiskAvailable'] = False
            job['resultIntegrity'] = job.get('resultIntegrity', 'NOT_AVAILABLE')
            if job['resultIntegrity'] == 'VERIFIED':
                # Read coverage only after the existing adapter checked identities and custody.
                path = state/job['result_path']/'results.json'
                data = path.read_bytes()
                result_doc = json.loads(data)
                # Validate again after the coverage read to detect concurrent artifact changes.
                bundle.require(adapter.validate_result(state/'inputs'/job['bundle_id'], state/job['result_path'])
                               == job['result_hash'], 'result changed during status read')
                bundle.require(path.read_bytes() == data, 'coverage bytes changed during status read')
                job['coverage'] = result_doc['coverage']
            # Internal filesystem paths and raw result items are not part of this read surface.
            job.pop('result_path', None)
            for attempt in job['attempts']:
                attempt.pop('result_path', None)
        return {'schema': SCHEMA, 'availability': 'AVAILABLE', 'observedAt': now(),
                'workerConnectivity': 'NOT_PROBED', 'producerAuthentication': 'NOT_ESTABLISHED',
                **result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', required=True)
    args = parser.parse_args()
    try:
        result = snapshot(args.state)
        print(bundle.encoded(result).decode(), end='')
    except (OSError, ValueError, TypeError, KeyError, sqlite3.Error) as exc:
        parser.exit(1, f'local coordinator status unavailable: {exc}\n')


if __name__ == '__main__':
    main()
