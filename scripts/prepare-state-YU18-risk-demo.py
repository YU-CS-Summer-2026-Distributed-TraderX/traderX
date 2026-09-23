#!/usr/bin/env python3
"""Prepare synthetic-only UI evidence and read-only coordinator custody from a real local run.

Run demo-state-YU18-alex-pricing.py first. This script never invokes a worker and never
accepts arbitrary portfolios: the existing provisional validator pins both input bundles.
"""
import argparse
from datetime import datetime, timezone
from decimal import Decimal, localcontext
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
COMPONENT = ROOT / 'specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles'
sys.path.insert(0, str(COMPONENT))
import bundle
import coordinator
import job_status
import pricing_result as pricing


def dec(value):
    return format(value if isinstance(value, Decimal) else Decimal(str(value)), 'f')


def comparison(actual, expected):
    actual = Decimal(str(actual))
    difference = actual - expected.value
    bundle.require(abs(difference) <= expected.tolerance, 'comparison outside tolerance')
    return dict(alexUsd=dec(actual), referenceUsd=dec(expected.value),
                differenceUsd=dec(difference), toleranceUsd=dec(expected.tolerance), withinTolerance=True)


def prepare(evidence, output):
    bundle.require(not output.exists(), 'output must be a new private directory')
    original = json.loads((evidence/'evidence.json').read_text())
    bundle.require(original['engineCommit'] == pricing.ENGINE_COMMIT
                   and original['alexTrackedBytesUnchanged'] is True, 'not a reviewed real producer run')
    output.mkdir(mode=0o700, parents=True)
    incoming = output/'incoming'; incoming.mkdir(mode=0o700)
    inbox = output/'inbox'; inbox.mkdir(mode=0o700)
    producer_completed = []
    for name in ('bill', 'note'):
        src = evidence/name/'inbox/cut'
        data = next((evidence/name/'incoming').glob('*.json')).read_bytes()
        pricing.validate(src, data)
        manifest, _, _ = pricing.inputs(src)
        bundle.require(bundle.digest(data) == original['examples'][name]['resultSha256'], 'original result changed')
        old_status = job_status.snapshot(evidence/name/'state')
        old_job = old_status['jobs'][0]
        bundle.require(old_job['resultIntegrity'] == 'VERIFIED' and next((evidence/name/'state/results').rglob('results.json')).read_bytes() == data, 'original custody invalid')
        producer_completed.append(old_job['attempts'][-1]['ended_at'])
        shutil.copytree(src, inbox/name)
        (incoming/(manifest['bundleId']+'.json')).write_bytes(data)
    state = output/'state'
    with coordinator.Coordinator(state, pricing.PricingFileAdapter(incoming)) as ctl:
        ctl.discover(inbox)
        jobs = ctl.run()['jobs']
        bundle.require(len(jobs) == 2 and all(j['status'] == 'SYNTHETIC_PRICING_VALIDATED' for j in jobs), 'intake failed')
    # Consolidate WAL before packaging. SQLite backup reads a consistent committed snapshot;
    # neither a naked copy of a live DB nor a new producer execution is implied.
    db = state/'jobs.sqlite3'
    with sqlite3.connect(db) as connection:
        connection.execute('PRAGMA wal_checkpoint(TRUNCATE)')
        connection.execute('PRAGMA journal_mode=DELETE')
        bundle.require(connection.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'SQLite integrity failure')
    snapshot = job_status.snapshot(state)
    document = dict(schema='traderx.risk-demo.v1', scope='synthetic-fixture-validation',
        usableForRisk=False, portfolioRiskAvailable=False, workerConnectivity='NOT_PROBED',
        businessDate='2025-06-02', valuationTime='2025-06-02T16:00:00-04:00',
        assumedMarketProfile='flat-3pct-v1', marketProvenance='assumed',
        generatedAt=datetime.now(timezone.utc).isoformat(),
        traderxCommit=subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'], text=True).strip(),
        compatibilityProfile=dict(adapter=pricing.PROFILE_ID, engineCommit=pricing.ENGINE_COMMIT),
        producerExecution=dict(location='LOCAL', completedAt=max(producer_completed)), jobs=[])
    for name in ('bill', 'note'):
        source = inbox/name
        manifest, rows, terms = pricing.inputs(source)
        data = (incoming/(manifest['bundleId']+'.json')).read_bytes()
        pricing.validate(source, data)
        result = json.loads(data, parse_float=Decimal)
        job = next(j for j in snapshot['jobs'] if j['bundle_id'] == manifest['bundleId'])
        positions=[]
        for row, item in zip(rows, result['items']):
            npv, accrued, sensitivity = pricing.reference(row, terms, manifest['cut']['sessionDate'])
            calc = item['calculations']; actual = calc['npv']
            detail=None
            if name == 'note':
                rec = npv['accrualReconciliation']
                detail=dict(cleanNpv=comparison(actual['cleanNpv'], npv['cleanNpv']),
                    accruedInterest=comparison(calc['accruedInterest']['value'], accrued['value']),
                    dirtyMinusCleanMinusAccruedUsd=dec(actual['value']-actual['cleanNpv']-actual['accruedInterest']),
                    accrualReconciliation=dict(exportedFraction=dec(rec['exportedFraction']),
                        recomputedFraction=dec(rec['recomputedFraction'].value),
                        differenceUsd=dec(rec['difference'].value), boundUsd=dec(rec['tolerance'].value),
                        withinBound=abs(rec['difference'].value)<=rec['tolerance'].value),
                    parallelBump1bpUsd=comparison(calc['rateSensitivity']['value'], sensitivity['value']))
            positions.append(dict(side='long' if Decimal(row['quantity'])>0 else 'short',
                signedFaceUsd=dec(row['quantity']), npv=comparison(actual['value'],npv['value']), noteDetail=detail))
        document['jobs'].append(dict(instrument=name,jobId=job['job_id'],bundleId=job['bundle_id'],
            status=job['status'],resultIntegrity=job['resultIntegrity'],resultSha256=bundle.digest(data),
            validatedAt=snapshot['observedAt'],attemptCount=len(job['attempts']),clusterEpoch=job['clusterEpoch'],
            cut=job['cut'],positions=positions,coverage=result['coverage']))
    (output/'risk-demo.json').write_text(json.dumps(document,indent=2,allow_nan=False)+'\n')
    (output/'status.json').write_text(json.dumps(snapshot,indent=2)+'\n')
    print(json.dumps({'artifact':str(output/'risk-demo.json'),'state':str(state),'jobs':len(jobs)}))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args=parser.parse_args()
    with localcontext() as context:
        context.prec=50
        prepare(args.evidence.resolve(),args.output.resolve())
