import copy
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import unittest

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
CLI=HERE/'source_identity.py'
READER=os.environ.get('IDENTITY_READER_PYTHON',sys.executable)
TICK_STORE=Path(os.environ.get('TEST_TICK_STORE_ROOT',str(ROOT/'specs/YU07-historical-tick-store/generation/runtime-overrides/tick-store')))


class SourceIdentityTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='identity-test-');self.addCleanup(self.tmp.cleanup)
        self.folder=Path(self.tmp.name)
        self.inventory=json.loads((HERE/'fixtures/identity-inventory.json').read_text())

    def run_cli(self,kind='CT',path=None,extra=(),expect=0):
        if path is None:path=HERE/f'fixtures/identity-{kind}.csv'
        args=[sys.executable,str(CLI),'--kind',kind,'--input',str(path),'--reader-python',READER,
              '--tick-store-root',str(TICK_STORE),*extra]
        p=subprocess.run(args,capture_output=True,text=True,timeout=30)
        self.assertEqual(p.returncode,expect,p.stdout+p.stderr)
        status=json.loads(p.stdout);output=Path(status['privateOutput']);self.addCleanup(output.unlink,missing_ok=True)
        self.assertEqual(stat.S_IMODE(output.stat().st_mode),0o600)
        self.assertNotIn('MERGE',p.stdout)
        return json.loads(output.read_text()),output.read_bytes()

    def verify_inventory(self,report,kind):
        expected=self.inventory[kind];raw=report['unfilteredReader'];eligible=report['eligibleProjection']
        self.assertEqual(raw['observations'],16)
        self.assertEqual(raw['distinctReaderTuples'],self.inventory['rawDistinctReaderTuples'])
        self.assertEqual(raw['repeatExtraObservations'],3)
        self.assertEqual(raw['excludedObservationRows'],expected['excludedRows'])
        self.assertEqual(eligible['observations'],expected['eligibleObservations'])
        self.assertEqual(eligible['distinctReaderTuples'],expected['eligibleDistinctReaderTuples'])
        self.assertEqual(eligible['lostTupleDistinctions'],3)
        self.assertEqual(eligible['repeatExtraObservations'],3)
        groups={g['outputSymbol']['value']:g for g in eligible['outputGroups']}
        self.assertEqual(sorted(groups),expected['outputSymbols'])
        self.assertFalse(groups['SAME']['collapse'],'two observations of one tuple are not a collision')
        self.assertFalse(groups['merge']['collapse'],'exact lower-case root is not upper-case MERGE')
        collapsed={g['outputSymbol']['value']:g for g in eligible['collapseGroups']}
        self.assertEqual(set(collapsed),{'MERGE','EMPTY'})
        for symbol,want in expected['collapseGroups'].items():
            group=collapsed[symbol]
            got=[v['identity']['suffix'].get('value') for v in group['distinctSourceTuples']]
            self.assertCountEqual(got,want['suffixes'])
            self.assertEqual(group['observationRows'],want['observationRows'])
            self.assertEqual(group['lostTupleDistinctions'],want['lostDistinctions'])
        self.assertTrue(report['proof']['actualSelectLineageAgreement'])
        self.assertGreater(eligible['distinctReaderTuples'],len(eligible['outputGroups']))

    def test_current_ct_projection_and_nonempty_independent_inventory(self):
        r,_=self.run_cli('CT');self.verify_inventory(r,'CT')
        self.assertIn('AND PRICE IS NOT NULL',r['proof']['predicate'])

    def test_current_cq_projection_honors_its_different_filter(self):
        r,_=self.run_cli('CQ');self.verify_inventory(r,'CQ')
        self.assertNotIn('PRICE',r['proof']['predicate'])

    def test_pure_select_structure_and_exact_source_input_dependency_hashes(self):
        r,_=self.run_cli('CT')
        src=TICK_STORE/'ingest_taq_trades.py'
        self.assertEqual(r['source']['sha256'],hashlib.sha256(src.read_bytes()).hexdigest())
        self.assertEqual(r['input']['sha256'],hashlib.sha256((HERE/'fixtures/identity-CT.csv').read_bytes()).hexdigest())
        self.assertIn('SYM_ROOT AS symbol',r['proof']['extractedCurrentSelect']['sql'])
        for query in r['proof']['queries'].values():
            self.assertTrue(query['sql'].startswith('SELECT '));self.assertNotIn('COPY (',query['sql'])
            self.assertNotIn('PARQUET',query['sql']);self.assertNotIn('INSTALL ',query['sql'])
            self.assertEqual(query['sha256'],hashlib.sha256(query['sql'].encode()).hexdigest())
        self.assertIn(['SYM_SUFFIX','VARCHAR'],r['proof']['sourceSchemas']['reader'])
        self.assertIn(['symbol','VARCHAR'],r['proof']['sourceSchemas']['projection'])
        self.assertTrue(r['proof']['duckdb']['spillDisabled'])
        self.assertFalse(r['proof']['duckdb']['extensionAutoInstallAndLoad'])
        self.assertEqual(len(r['proof']['duckdb']['extensionSha256']),64)

    def test_deterministic_reports_with_no_materialized_timestamps(self):
        _,a=self.run_cli('CT');_,b=self.run_cli('CT');self.assertEqual(a,b)

    def test_missing_suffix_and_declaration_only_input_refuse(self):
        for data in ['SYM_ROOT\nMERGE\n','SYM_ROOT,SYM_SUFFIX\nMERGE,A\n']:
            p=self.folder/'bad.csv';p.write_text(data)
            r,_=self.run_cli(path=p,expect=2)
            self.assertEqual(r['proof']['state'],'invalid');self.assertIsNone(r['eligibleProjection'])

    def test_missing_empty_and_duplicate_header_refuse(self):
        r,_=self.run_cli(path=self.folder/'missing.csv',expect=2)
        self.assertEqual(r['input']['state'],'unavailable');self.assertIsNone(r['eligibleProjection'])
        for data in ['', 'SYM_ROOT,SYM_ROOT,SYM_SUFFIX\nMERGE,MERGE,A\n']:
            p=self.folder/'bad.csv';p.write_text(data)
            r,_=self.run_cli(path=p,expect=2);self.assertIsNone(r['eligibleProjection'])

    def test_empty_and_zero_eligible_population_cannot_pass(self):
        data=(HERE/'fixtures/identity-CT.csv').read_text().splitlines()
        for content in [data[0]+'\n',data[0]+'\n'+data[12]+'\n']:
            p=self.folder/'zero.csv';p.write_text(content)
            r,_=self.run_cli(path=p,expect=2);self.assertEqual(r['analysis'],'incomplete')
            self.assertIsNone(r['eligibleProjection'])

    def test_incompatible_reader_types_are_not_guessed_date_filters(self):
        lines=(HERE/'fixtures/identity-CT.csv').read_text().splitlines()
        p=self.folder/'types.csv';p.write_text(lines[0]+'\n'+lines[1].replace('2025-02-03','not-a-date')+'\n')
        r,_=self.run_cli(path=p,expect=2);self.assertEqual(r['proof']['state'],'invalid')
        self.assertIsNone(r['eligibleProjection'])

    def test_budgets_refuse_not_truncate(self):
        for extra in [('--max-rows','3'),('--max-input-bytes','32')]:
            r,_=self.run_cli(extra=extra,expect=2);self.assertIsNone(r['eligibleProjection'])
        p=subprocess.run([sys.executable,str(CLI),'--kind','CT','--max-input-bytes',str(129*1024*1024)],capture_output=True,text=True)
        self.assertEqual(p.returncode,2)

    def test_unsupported_ingester_is_never_imported_or_executed(self):
        folder=self.folder/'source';folder.mkdir()
        src=TICK_STORE/'ingest_taq_trades.py'
        (folder/src.name).write_text(src.read_text()+"\nraise RuntimeError('must never execute source')\n")
        r,_=self.run_cli(extra=('--tick-store-root',str(folder)),expect=2)
        self.assertEqual(r['source']['state'],'invalid');self.assertIsNone(r['proof'])

    def test_cached_dependency_unavailable_refuses_without_install(self):
        wrapper=self.folder/'no-site-python'
        wrapper.write_text('#!/bin/sh\nexec '+shlex.quote(sys.executable)+' -S "$@"\n');wrapper.chmod(0o700)
        r,_=self.run_cli(extra=('--reader-python',str(wrapper)),expect=2)
        self.assertEqual(r['proof']['state'],'unavailable');self.assertIsNone(r['eligibleProjection'])

    def test_child_deadline_refuses_without_retained_process(self):
        fake=self.folder/'slow-python'
        fake.write_text('#!'+sys.executable+'\nimport time\ntime.sleep(30)\n')
        fake.chmod(0o700)
        started=time.monotonic()
        r,_=self.run_cli(extra=('--reader-python',str(fake)),expect=2)
        elapsed=time.monotonic()-started
        self.assertGreaterEqual(elapsed,19)
        self.assertLess(elapsed,27)
        self.assertIn('TimeoutExpired',r['proof']['reason'])
        self.assertIsNone(r['eligibleProjection'])

    def test_independent_inventory_refuses_omitted_suffix_and_wrong_projection(self):
        r,_=self.run_cli('CT')
        bad=copy.deepcopy(r);bad['eligibleProjection']['collapseGroups'][0]['distinctSourceTuples'][0]['identity'].pop('suffix')
        with self.assertRaises((AssertionError,KeyError)):self.verify_inventory(bad,'CT')
        bad=copy.deepcopy(r);bad['eligibleProjection']['outputGroups'][0]['outputSymbol']['value']='A'
        with self.assertRaises(AssertionError):self.verify_inventory(bad,'CT')


if __name__=='__main__':unittest.main()
