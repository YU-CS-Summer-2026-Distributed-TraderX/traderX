"""FR-PRG-38, against a real MariaDB with the shipped schema: test-data-cleanup.sh deletes exactly the test accounts'
rows from an archived epoch, leaves every other account's rows and the live epoch alone, refuses --accounts while any
table still holds one of their rows, and `plan` changes nothing. Skipped without docker."""
import shlex
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

import yaml

HERE = Path(__file__).parent
RIG = HERE / 'rig.sh'
CLEANUP = HERE / 'test-data-cleanup.sh'
INIT = HERE.parents[2] / 'specs/YU18-risk-integration/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml'
ROWS = {  # one row per account table, per account
    'trades': "INSERT INTO trades(id,accountid,security,side,quantity,price,state,created,updated,settlementdate) VALUES ('{k}-{a}',{a},'MSFT','Buy',1,393,'Settled',NOW(),NOW(),NOW())",
    'positions': "INSERT INTO positions(accountid,security,updated,quantity,averagecostbasis) VALUES ({a},'MSFT{k}',NOW(),1,393)",
    'orderbook': "INSERT INTO orderbook(orderid,accountid,security,side,quantity,remainingquantity,limitprice,status,createdat,updatedat) VALUES ('{k}-{a}',{a},'MSFT','Buy',1,0,393,'FILLED',NOW(),NOW())",
    'order_view_snapshot': "INSERT INTO order_view_snapshot(projection_scope,partition_id,orderid,accountid,status,payload) VALUES ('s',0,'{k}-{a}',{a},'FILLED','{{}}')",
}
TEST, DEMO = 9900004, 22214   # a FIX load account and a seeded demo account


@unittest.skipUnless(shutil.which('docker'), 'needs docker')
class TestDataCleanup(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cid = subprocess.run(['docker', 'run', '-d', '--rm', '-e', 'MARIADB_ROOT_PASSWORD=pw', '-e', 'MARIADB_DATABASE=traderx',
                                  'mariadb:11.4'], check=True, capture_output=True, text=True).stdout.strip()
        for _ in range(90):
            if cls.q('select 1').stdout.strip() == '1':
                break
            time.sleep(1)

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '-f', cls.cid], capture_output=True)

    @classmethod
    def q(cls, sql):
        return subprocess.run(['docker', 'exec', '-i', cls.cid, 'mariadb', '-uroot', '-ppw', '-N', '-B', 'traderx'],
                              input=sql, capture_output=True, text=True)

    def n(self, sql):
        return int(self.q(sql).stdout.strip())

    def setUp(self):   # an archived epoch e1 and a live epoch, each with one row per table on TEST and on DEMO
        self.q('DROP DATABASE traderx; CREATE DATABASE traderx;')
        data = yaml.safe_load(INIT.read_text())['data']
        for key in sorted(data):
            p = self.q(data[key]); assert p.returncode == 0, (key, p.stderr[-500:])
        self.q(f"INSERT INTO accounts(id,displayname) VALUES ({TEST},'load') ON DUPLICATE KEY UPDATE id=id;"
               f"INSERT INTO accountusers(accountid,username) VALUES ({TEST},'load');")
        self.insert('a')
        p = self.rig('archive_read_model e1'); assert p.returncode == 0, p.stdout + p.stderr
        self.insert('b')

    def insert(self, k):
        for a in (TEST, DEMO):
            for sql in ROWS.values():
                p = self.q(sql.format(k=k, a=a)); assert p.returncode == 0, p.stderr

    def stub(self):   # rig.sh's kubectl/gcloud helpers, redefined after it is sourced: SQL goes to the container
        return (f'kc(){{ case "$*" in "exec deploy/eod-price-db"*) docker exec -e MARIADB_ROOT_PASSWORD=pw {self.cid} "${{@:6}}";; '
                '"get deploy trade-processor"*) echo 1;; "get secret"*) echo secret/fix-test-clients;; *) :;; esac; }; '
                'gc(){ :; }; preflight(){ :; }; wait_for(){ :; }; kubectl(){ echo "KUBECTL $*"; }\n')

    def bash(self, body):
        with tempfile.TemporaryDirectory() as d:
            return subprocess.run(['bash', '-c', body], env={'PATH': '/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin', 'EVIDENCE': d},
                                  capture_output=True, text=True, timeout=300)

    def rig(self, fn):
        return self.bash(f'set -euo pipefail; RIG_LIB=1 . "{RIG}"; {self.stub()}{fn}')

    def cleanup(self, *args):   # the script itself, with the stub inserted right after it sources rig.sh
        line = 'RIG_LIB=1 . "$HERE/rig.sh"\n'
        src = CLEANUP.read_text(); assert line in src
        with tempfile.NamedTemporaryFile('w', suffix='.sh', dir=HERE, delete=False) as f:
            f.write(src.replace(line, line + self.stub()))
        try:
            return self.bash(f'bash "{f.name}" ' + ' '.join(map(shlex.quote, args)))
        finally:
            Path(f.name).unlink()

    def counts(self):
        return {(p, t, a): self.n(f'SELECT count(*) FROM {p}{t} WHERE accountid={a}')
                for p in ('arc_e1_', '') for t in ROWS for a in (TEST, DEMO)}

    def test_apply_deletes_only_the_test_accounts_rows_of_the_named_archive(self):
        before = self.counts()
        p = self.cleanup('apply', 'e1'); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        for (prefix, t, a), n in self.counts().items():
            want = 0 if prefix and a == TEST else before[(prefix, t, a)]
            self.assertGreater(before[(prefix, t, a)], 0, (prefix, t, a))   # control: there was a row to keep or delete
            self.assertEqual(n, want, (prefix, t, a))
        self.assertIn('0 rows remain', p.stdout)

    def test_plan_and_dry_run_change_nothing(self):
        before = self.counts()
        p = self.cleanup('plan'); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn('archived epoch e1', p.stdout); self.assertIn('trades 1', p.stdout)
        p = self.cleanup('apply', 'e1', '--accounts', '--secret', '--dry-run'); self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn('still referenced', p.stdout + p.stderr)   # the live epoch holds TEST's rows: refused even dry
        self.assertEqual(self.counts(), before)

    def test_accounts_are_refused_while_referenced_then_deleted(self):
        p = self.cleanup('apply', 'e1', '--accounts'); self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn('still referenced', p.stdout + p.stderr)
        self.assertEqual(self.n(f'SELECT count(*) FROM accounts WHERE id={TEST}'), 1)
        p = self.rig('archive_read_model e2'); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)   # the fresh epoch
        p = self.cleanup('apply', 'e2', '--accounts', '--secret'); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(self.n(f'SELECT count(*) FROM accounts WHERE id={TEST}'), 0)
        self.assertEqual(self.n(f'SELECT count(*) FROM accountusers WHERE accountid={TEST}'), 0)
        self.assertEqual(self.n(f'SELECT count(*) FROM accounts WHERE id={DEMO}'), 1)
        self.assertIn('KUBECTL --context', p.stdout); self.assertIn('delete secret fix-test-clients', p.stdout)

    def test_an_unknown_or_malformed_label_is_refused(self):
        for args in (('apply', 'e9'), ('apply', 'E1;drop'), ('apply',)):
            before = self.counts()
            p = self.cleanup(*args); self.assertEqual(p.returncode, 2, (args, p.stdout + p.stderr))
            self.assertEqual(self.counts(), before)


if __name__ == '__main__':
    unittest.main()
