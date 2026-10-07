"""Exercise the actual bash proof and subprocess helper, never a test-only selection path."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
PROOF = ROOT / 'scripts/proofs/yu05-recon.sh'
HELPER = ROOT / 'scripts/proofs/recon-proof-subject.py'
FAKE = Path(__file__).with_name('fake-command.py')
HASH = 'a' * 64


class SubjectProof(unittest.TestCase):
    def fixture(self, directory, scenario='normal'):
        d = Path(directory)
        (d / 'scenario').write_text(scenario)
        live = [dict(id='e1-epoch_a-7-S',tradeSeq=7,accountId=42,security='NVDA',side='Sell',quantity=25,price=100.5,execTimeMillis=1234),
                dict(id='e1-epoch_a-8-B',tradeSeq=8,accountId=43,security='NVDA',side='Buy',quantity=25,price=100.5,execTimeMillis=1234)]
        (d / 'run.json').write_text(json.dumps(dict(projectionScope='scope_a',clusterEpoch='epoch_a',descriptorHash=HASH,eventIdScheme='epoch-v1',runPhase=2)))
        db = sqlite3.connect(d / 'projection.sqlite')
        db.executescript('''CREATE TABLE trades(id TEXT PRIMARY KEY, accountid INTEGER,security TEXT,side TEXT,quantity INTEGER,price REAL,state TEXT,
        projectionscope TEXT,clusterepoch TEXT,eventidscheme TEXT,rundescriptorhash TEXT,consensussequence INTEGER,sourceorderid TEXT COLLATE NOCASE,created TEXT,updated TEXT,settlementdate TEXT,rejectionreason TEXT);
        CREATE TABLE projection_runs(projection_scope TEXT,cluster_epoch TEXT,event_id_scheme TEXT,descriptor_hash TEXT,phase TEXT);
        CREATE TABLE projection_active(singleton_id INTEGER,projection_scope TEXT);
        CREATE TABLE projection_recon(projection_scope TEXT PRIMARY KEY,cursor_seq INTEGER,matched INTEGER,missing INTEGER,mismatched INTEGER);''')
        db.execute('INSERT INTO projection_runs VALUES (?,?,?,?,?)',('scope_a','epoch_a','epoch-v1',HASH,'ACTIVE'))
        db.execute('INSERT INTO projection_active VALUES (1,?)',('scope_a',))
        db.execute('INSERT INTO projection_recon VALUES (?,?,?,?,?)',('scope_a',8,2,0,0))
        db.execute('INSERT INTO projection_recon VALUES (?,?,?,?,?)',('other_scope',100,90,9,1))
        for i, row in enumerate([dict(live[0],id='1-S'),dict(live[1],id='2-B')] + live):
            db.execute('INSERT INTO trades VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', (row['id'],row['accountId'],row['security'],row['side'],row['quantity'],row['price'],'Processing',
                      'scope_a' if i>=2 else 'legacy-unknown','epoch_a' if i>=2 else None,'epoch-v1' if i>=2 else None,HASH if i>=2 else None,10+i,None,None,None,None,None))
        if scenario == 'no-intersection':
            db.execute("DELETE FROM trades WHERE id LIKE 'e1-%'")
        elif scenario == 'foreign-sql':
            db.execute("UPDATE trades SET projectionscope='foreign' WHERE id='e1-epoch_a-7-S'")
        elif scenario == 'wrong-quantity':
            db.execute("UPDATE trades SET quantity=500 WHERE id='e1-epoch_a-7-S'")
        elif scenario == 'same-window':
            db.execute("DELETE FROM trades WHERE id IN ('1-S','2-B')")
        elif scenario == 'empty-window':
            live=[]
        elif scenario == 'foreign-live':
            live[0]['id']='e1-epoch_b-7-S'
        elif scenario == 'unknown-run':
            run=json.loads((d/'run.json').read_text());run['descriptorHash']=None;(d/'run.json').write_text(json.dumps(run))
        (d / 'window.json').write_text(json.dumps(live))
        db.commit(); db.close()
        (d / 'runtime.json').write_text(json.dumps(dict(replicas=1,generation=0,uid='original-uid',logs='',
           status=dict(cursor=8,matched=2,missingInProjection=0,fieldMismatch=0,lastSweepAt='2026-10-07T18:00:00Z'))))
        binpath=d/'bin';binpath.mkdir()
        for name in ('kubectl','curl','sleep','pkill'):
            (binpath/name).symlink_to(FAKE)
        return dict(os.environ,PATH=str(binpath)+os.pathsep+os.environ['PATH'],RECON_FIXTURE=str(d),
                    CTX='offline',AUTH_MASTER_SECRET='fixture',RECON_PROOF_ATTEMPTS='10',RECON_PROOF_POLL_SECONDS='0.02')

    def snapshot(self, d):
        db=sqlite3.connect(Path(d)/'projection.sqlite')
        result=[db.execute('SELECT * FROM '+table+' ORDER BY 1').fetchall() for table in ('trades','projection_recon','projection_runs','projection_active')]
        db.close();return result

    def run_case(self, scenario='normal', old=None):
        with tempfile.TemporaryDirectory() as d:
            env=self.fixture(d,scenario);before=self.snapshot(d)
            proof=PROOF
            mutant = globals().get('HELPER_MUTANT_SOURCE')
            if old is not None or mutant is not None:
                # Keep original common helper and source script, execute unchanged.
                local=Path(d)/'old';local.mkdir();proof=local/'yu05-recon.sh';proof.write_text(old if old is not None else PROOF.read_text())
                if mutant is not None:
                    (local/'recon-proof-subject.py').write_text(mutant)
                (local/'yu05-common.sh').write_text((PROOF.parent/'yu05-common.sh').read_text())
            p=subprocess.Popen(['bash',str(proof)],env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
            try:
                out,_=p.communicate(timeout=30)
            finally:
                # Old proof leaves its forward; fixture cleanup never touches real processes.
                import signal
                try: os.killpg(p.pid,signal.SIGTERM)
                except ProcessLookupError: pass
            after=self.snapshot(d)
            if (Path(d)/'foreign-row.json').exists():
                before[0].append(tuple(json.loads((Path(d)/'foreign-row.json').read_text())))
                before[0].sort(key=lambda r:r[0])
            if (Path(d)/'external-active').exists():
                before[3]=[(1,'other_scope')]
            runtime=json.loads((Path(d)/'runtime.json').read_text())
            queries=(Path(d)/'queries.log').read_text()
            self.assertEqual(before,after, out)
            self.assertEqual(1,runtime['replicas'],out)
            return p.returncode,out,queries

    def test_rolled_window_older_sql(self):
        rc,out,q=self.run_case();self.assertEqual(0,rc,out)
        self.assertIn('subject=e1-epoch_a-7-S named=True',out)
        self.assertIn('subject=e1-epoch_a-7-S named=False',out)
        self.assertIn('both members replay',out)
        self.assertIn('named as ORPHAN_IN_PROJECTION',out)
        self.assertNotIn("UPDATE trades SET quantity=26 WHERE id='1-S'",q)

    def test_preserved_history_and_orphan_assertions(self):
        old=subprocess.check_output(['git','show','a0d6da0b:scripts/proofs/yu05-recon.sh'],cwd=ROOT,text=True)
        new=PROOF.read_text()
        self.assertEqual(old.split('# ---- 3b.')[0],new.split('# ---- 3b.')[0])
        # Orphan setup/cleanup/assertions moved into the checked ownership helper for R1.
        # The full-history metrics arm remains; executable orphan identity controls cover semantics.
        self.assertEqual(old.split('# ---- 4.')[1].split('sweep(){')[0],
                         new.split('# ---- 4.')[1].split('# The same proof helper')[0])

    def test_same_window(self):
        rc,out,_=self.run_case('same-window');self.assertEqual(0,rc,out)

    def test_unique_probe_each_invocation(self):
        import re
        first=self.run_case();second=self.run_case()
        ids=[]
        for rc,out,q in (first,second):
            self.assertEqual(0,rc,out)
            probe=re.search(r'planted projection-only row (orphan-probe-([a-f0-9]{32})-B)',out)
            self.assertIsNotNone(probe,out)
            ids.append(probe.group(1))
            self.assertLessEqual(len(probe.group(1)),50)
            self.assertIn('ri19-'+probe.group(2),q)
            self.assertIn('SELECT ROW_COUNT()',q)
        self.assertNotEqual(*ids)

    def test_orphan_owns_forward_after_processor_restarts(self):
        rc,out,q=self.run_case('orphan-stale-shared-forward')
        self.assertEqual(0,rc,out)
        self.assertIn('named as ORPHAN_IN_PROJECTION',out)

    def test_original_failure(self):
        original=subprocess.check_output(['git','show','a0d6da0b:scripts/proofs/yu05-recon.sh'],cwd=ROOT,text=True)
        rc,out,q=self.run_case(old=original);self.assertNotEqual(0,rc,out)
        self.assertIn('PLANTED persistent mismatch was NOT caught',out)
        self.assertIn("UPDATE trades SET quantity=26 WHERE id='1-S'",q)

    def refusal(self, scenario, reason):
        rc,out,q=self.run_case(scenario);self.assertNotEqual(0,rc,out)
        self.assertIn(reason,out)
        self.assertNotIn('Traceback',out)
        self.assertNotIn('INSERT INTO trades',q)
        if scenario in EARLY:
            self.assertNotIn('UPDATE trades SET quantity',q)
            self.assertNotIn('UPDATE projection_recon',q)

    def interruption(self, scenario):
        rc,out,q=self.run_case(scenario);self.assertNotEqual(0,rc,out)
        self.assertIn('restored original subject',out)
        self.assertIn("UPDATE trades SET quantity=26 WHERE id='e1-epoch_a-7-S'",q)

    def orphan_refusal(self, scenario, reason):
        rc,out,q=self.run_case(scenario)
        self.assertNotEqual(0,rc,out)
        self.assertIn(reason,out)
        self.assertNotIn('Traceback',out)
        if scenario in ('orphan-empty','orphan-all','orphan-insert-race','orphan-insert-error','orphan-insert-noop','orphan-scope-before-insert'):
            self.assertNotIn('DELETE FROM trades',q)
        elif scenario in ('orphan-row-scope-change','orphan-row-owner-change','orphan-row-owner-case-change','orphan-row-field-change'):
            self.assertNotIn('DELETE FROM trades',q)
        elif scenario == 'orphan-insert-ambiguous':
            self.assertIn('attributed orphan probe removed',out)
            self.assertIn('DELETE FROM trades WHERE id=',q)
            self.assertIn('sourceorderid=',q)
        # Any deletion has a complete row/provenance/nonce predicate, never only an id.
        for line in q.splitlines():
            if line.startswith('DELETE FROM trades'):
                for field in ('accountid=','security=','side=','quantity=','price=','state=',
                              'projectionscope=','clusterepoch=','eventidscheme=','rundescriptorhash=',
                              'consensussequence=','sourceorderid=','created=','updated=',
                              'settlementdate IS NULL','rejectionreason IS NULL'):
                    self.assertIn(field,line)



REFUSALS = {
    'no-intersection':'no live-window/SQL intersection',
    'empty-window':'empty live forward window',
    'foreign-live':'foreign run',
    'foreign-sql':'SQL/live identity or scope differs',
    'unknown-run':'invalid identity reading',
    'wrong-quantity':'SQL/live identity or scope differs',
    'http-error':'command failed: curl',
    'invalid-json':'HTTP response is not JSON',
    'sql-error':'command failed: kubectl',
    'checkpoint-error':'command failed: kubectl',
    'other-mismatch':'did not name planted subject',
    'prefix-mismatch':'did not name planted subject',
    'logs-error':'command failed: kubectl',
    'invalid-status':'invalid integer reading',
    'cursor-short':'did not reach the selected subject',
    'stale-uid':'processor pod was not replaced',
    'update-error':'command failed: kubectl',
    'mutation-noop':'subject quantity write did not apply',
    'run-churn':'run descriptor changed',
}
EARLY = set(('no-intersection','empty-window','foreign-live','foreign-sql','unknown-run',
             'wrong-quantity','http-error','invalid-json','sql-error','checkpoint-error'))
for scenario, reason in REFUSALS.items():
    def test(self, scenario=scenario, reason=reason):
        self.refusal(scenario, reason)
    setattr(SubjectProof,'test_refusal_' + scenario.replace('-','_'),test)
for scenario in ('term','int','hup','shell-term','shell-int','shell-hup','poll-term'):
    def test(self, scenario=scenario):
        self.interruption(scenario)
    setattr(SubjectProof,'test_interruption_' + scenario.replace('-','_'),test)

ORPHAN_REFUSALS = {
    'orphan-empty':'nothing to reconcile',
    'orphan-all':'every projection row an orphan',
    'orphan-insert-race':'unattributed orphan row retained',
    'orphan-insert-error':'command failed: kubectl',
    'orphan-insert-noop':'INSERT did not establish one owned row',
    'orphan-insert-ambiguous':'attributed orphan probe removed',
    'orphan-scope-before-insert':'INSERT did not establish one owned row',
    'orphan-scope-after-insert':'SQL active scope does not agree',
    'orphan-row-scope-change':'owned orphan probe contents changed',
    'orphan-row-owner-change':'owned orphan probe contents changed',
    'orphan-row-owner-case-change':'owned orphan probe contents changed',
    'orphan-row-field-change':'owned orphan probe contents changed',
    'orphan-delete-replace':'row retained',
    'orphan-wrong-id':'planted row was NOT detected',
    'orphan-still-named':'probe STILL named',
}
for scenario, reason in ORPHAN_REFUSALS.items():
    def test(self, scenario=scenario, reason=reason):
        self.orphan_refusal(scenario,reason)
    setattr(SubjectProof,'test_' + scenario.replace('-','_'),test)
for scenario in ('orphan-term','orphan-hup','orphan-int','orphan-shell-term','orphan-shell-hup',
                 'orphan-shell-int','orphan-sweep-term','orphan-sweep-hup'):
    def test(self, scenario=scenario):
        self.orphan_refusal(scenario,'attributed orphan probe removed')
    setattr(SubjectProof,'test_' + scenario.replace('-','_'),test)


if __name__ == '__main__':
    unittest.main(verbosity=2)
