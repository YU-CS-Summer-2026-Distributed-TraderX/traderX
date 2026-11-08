"""FR-PRG-29, against a real MariaDB with the shipped schema (database-init-sql 001 + 900): rig.sh's own
archive_read_model() moves each epoch table into arc_<label>_* in one RENAME and recreates it empty with exactly the
same definition, positions' foreign key included (CREATE TABLE ... LIKE drops it). A rerun changes nothing. Skipped
without docker."""
import re
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

import yaml

HERE = Path(__file__).parent
RIG = HERE / 'rig.sh'
INIT = HERE.parents[2] / 'specs/YU18-risk-integration/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml'
TABLES = 'trades positions orderbook order_view_snapshot projection_accounting projection_cursor projection_trade_days projection_recon'.split()


@unittest.skipUnless(shutil.which('docker'), 'needs docker')
class ArchiveReadModel(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cid = subprocess.run(['docker', 'run', '-d', '--rm', '-e', 'MARIADB_ROOT_PASSWORD=pw', '-e', 'MARIADB_DATABASE=traderx',
                                  'mariadb:11.4'], check=True, capture_output=True, text=True).stdout.strip()
        for _ in range(90):
            if cls.q('select 1').stdout.strip() == '1':
                break
            time.sleep(1)

    def setUp(self):   # each test on a freshly initialised database
        self.q('DROP DATABASE traderx; CREATE DATABASE traderx;')
        data = yaml.safe_load(INIT.read_text())['data']
        for key in sorted(data):
            p = self.q(data[key]); assert p.returncode == 0, (key, p.stderr[-500:])

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '-f', cls.cid], capture_output=True)

    @classmethod
    def q(cls, sql):
        return subprocess.run(['docker', 'exec', '-i', cls.cid, 'mariadb', '-uroot', '-ppw', '-N', '-B', 'traderx'],
                              input=sql, capture_output=True, text=True)

    def definition(self, table, as_name):
        out = self.q(f'SHOW CREATE TABLE {table}').stdout.split('\t', 1)[1]
        out = out.replace(f'`{table}`', f'`{as_name}`').replace('\\n', '\n')
        out = re.sub(r'AUTO_INCREMENT=\d+ ', '', re.sub(r'CONSTRAINT `[^`]*` FOREIGN', 'CONSTRAINT FOREIGN', out))
        # every column, key, foreign key and check, as a set: SHOW CREATE lists indexes in creation order only
        return sorted(line.strip().rstrip(',') for line in out.splitlines())

    def archive(self, label):
        with tempfile.TemporaryDirectory() as d:
            # reset_feed (NATS, FR-PRG-29) is test_rig's; this test is about the tables
            body = (f'set -euo pipefail; EVIDENCE={d}; RIG_LIB=1 . "{RIG}"; wait_for(){{ :; }}; reset_feed(){{ :; }}; '
                    f'kc(){{ case "$*" in "exec deploy/eod-price-db"*) docker exec -e MARIADB_ROOT_PASSWORD=pw {self.cid} "${{@:6}}";; '
                    f'"get deploy trade-processor"*) echo 1;; *) :;; esac; }}; archive_read_model {label}')
            return subprocess.run(['bash', '-c', body], capture_output=True, text=True, timeout=300)

    def test_tables_move_and_come_back_identical_and_empty(self):
        before = {t: self.definition(t, t) for t in TABLES}
        self.q("INSERT INTO accounts(id,displayname) VALUES (22214,'a') ON DUPLICATE KEY UPDATE id=id;"
               "INSERT INTO trades(id,accountid,security,side,quantity,price,state,created,updated,settlementdate) "
               "VALUES ('1-B',22214,'MSFT','Buy',1,393,'Settled',NOW(),NOW(),NOW());")
        p = self.archive('e2'); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        for t in TABLES:
            self.assertEqual(self.definition(t, t), before[t], t)                       # same definition, FK included
            self.assertEqual(self.q(f'SELECT count(*) FROM {t}').stdout.strip(), '0', t)
            self.assertEqual(self.definition(f'arc_e2_{t}', t), before[t], t)          # the archive kept it all
        self.assertIn('1-B', self.q('SELECT id FROM arc_e2_trades').stdout.split())   # with the seed rows: the epoch starts empty
        fk = self.q("SELECT count(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='traderx' "
                    "AND TABLE_NAME='positions' AND REFERENCED_TABLE_NAME='accounts'").stdout.strip()
        self.assertEqual(fk, '1')
        arc = self.q('SELECT count(*) FROM arc_e2_trades').stdout.strip()
        p = self.archive('e2'); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)    # a rerun renames nothing
        self.assertEqual(self.q('SELECT count(*) FROM arc_e2_trades').stdout.strip(), arc)
        for t in TABLES:
            self.assertEqual(self.definition(t, t), before[t], t)   # no second copy of a foreign key

    def test_tables_a_volume_never_got_are_left_absent(self):
        # an older volume: first-boot SQL that created these never ran on it
        self.q('DROP TABLE IF EXISTS projection_accounting; DROP TABLE IF EXISTS order_view_snapshot;')
        p = self.archive('e3'); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        names = set(self.q("SELECT table_name FROM information_schema.tables WHERE table_schema='traderx'").stdout.split())
        self.assertNotIn('projection_accounting', names); self.assertNotIn('arc_e3_projection_accounting', names)
        self.assertIn('arc_e3_trades', names); self.assertIn('trades', names)

    def prune(self, keep):   # FR-PRG-39 with no ClickHouse, keep's three READY snapshots, e1 and e2 on record
        hist = '{"items":[' + ','.join(f'{{"metadata":{{"name":"rig-epoch-{l}-history","creationTimestamp":"2026-11-0{i}T00:00:00Z"}}}}'
                                       for i, l in enumerate(('e1', 'e2'), 1)) + ']}'
        with tempfile.TemporaryDirectory() as d:
            body = (f'set -euo pipefail; EVIDENCE={d}; RIG_LIB=1 . "{RIG}"; '
                    f'kc(){{ case "$*" in "exec deploy/eod-price-db"*) docker exec -e MARIADB_ROOT_PASSWORD=pw {self.cid} "${{@:6}}";; '
                    f'"get configmap -o json") echo \'{hist}\';; *) :;; esac; }}; '
                    f'gc(){{ case "$*" in *"snapshots list"*) printf "traderx-{keep}-m%s\\n" 0 1 2;; *"snapshots describe"*) printf "READY\\tx\\n";; esac; }}; '
                    f'prune_archives {keep}')
            return subprocess.run(['bash', '-c', body], capture_output=True, text=True, timeout=300)

    def names(self):
        return set(self.q("SELECT table_name FROM information_schema.tables WHERE table_schema='traderx'").stdout.split())

    def test_prune_drops_only_the_older_epochs_archive(self):
        before = {t: self.definition(t, t) for t in TABLES}
        self.q("INSERT INTO accounts(id,displayname) VALUES (22214,'a') ON DUPLICATE KEY UPDATE id=id;")
        for label in ('e1', 'e2'):
            p = self.archive(label); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        p = self.prune('e2'); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertFalse({n for n in self.names() if n.startswith('arc_e1_')})
        self.assertEqual({n for n in self.names() if n.startswith('arc_e2_')}, {f'arc_e2_{t}' for t in TABLES})
        for t in TABLES:
            self.assertEqual(self.definition(t, t), before[t], t)   # live tables untouched, foreign key included
        self.assertEqual(self.q('SELECT count(*) FROM accounts WHERE id=22214').stdout.strip(), '1')
        p = self.prune('e2'); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)   # a rerun: nothing older
        self.assertIn('nothing older than e2', p.stdout)

    def test_prune_refuses_while_a_live_table_references_an_old_archive(self):
        for label in ('e1', 'e2'):
            p = self.archive(label); self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.q('CREATE TABLE ctl_ref (t VARCHAR(100), FOREIGN KEY (t) REFERENCES arc_e1_trades(id))')
        p = self.prune('e2')
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr); self.assertIn('ctl_ref->arc_e1_trades', p.stderr)
        self.assertEqual({n for n in self.names() if n.startswith('arc_e1_')}, {f'arc_e1_{t}' for t in TABLES})

    def test_control_like_alone_drops_the_foreign_key(self):
        self.q('CREATE TABLE ctl_positions LIKE positions')
        fk = self.q("SELECT count(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='traderx' "
                    "AND TABLE_NAME='ctl_positions'").stdout.strip()
        self.assertEqual(fk, '0')   # why archive_read_model re-adds it


if __name__ == '__main__':
    unittest.main()
