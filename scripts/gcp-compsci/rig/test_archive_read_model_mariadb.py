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
        data = yaml.safe_load(INIT.read_text())['data']
        for key in sorted(data):
            p = cls.q(data[key]); assert p.returncode == 0, (key, p.stderr[-500:])

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
            body = (f'set -euo pipefail; EVIDENCE={d}; RIG_LIB=1 . "{RIG}"; wait_for(){{ :; }}; '
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

    def test_control_like_alone_drops_the_foreign_key(self):
        self.q('CREATE TABLE ctl_positions LIKE positions')
        fk = self.q("SELECT count(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='traderx' "
                    "AND TABLE_NAME='ctl_positions'").stdout.strip()
        self.assertEqual(fk, '0')   # why archive_read_model re-adds it


if __name__ == '__main__':
    unittest.main()
