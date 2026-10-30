"""FR-DRS-36 migration on a real MariaDB: from every starting state of a persistent volume (no table, the
pre-FR-DRS-36 table holding a cursor, already migrated), applied twice, it ends with the column and keeps the cursor;
the pre-FR-DRS-36 cursor write still works afterwards (the rollback). Its statements are the shipped ri06.sql's.
Skipped without docker."""
import re
import shutil
import subprocess
import time
import unittest
from pathlib import Path

HERE = Path(__file__).parent
MIGRATION = HERE.joinpath('migrate-fr-drs-36.sql').read_text()
RI06 = HERE.parents[2].joinpath('specs/YU18-risk-integration/generation/runtime-overrides/postgres-database-replacement/'
                                'mariadb-migrations/ri06.sql').read_text()
OLD_TABLE = '''CREATE TABLE projection_cursor (projection_scope VARCHAR(64) NOT NULL, partition_id INTEGER NOT NULL,
 stream_seq BIGINT NOT NULL DEFAULT 0, consensus_seq BIGINT NOT NULL DEFAULT 0, snapshot_at DATETIME,
 PRIMARY KEY (projection_scope, partition_id));
INSERT INTO projection_cursor(projection_scope,partition_id,stream_seq,consensus_seq,snapshot_at) VALUES ('s',0,42,7,NOW());'''
OLD_WRITE = ("INSERT INTO projection_cursor(projection_scope,partition_id,stream_seq,consensus_seq,snapshot_at) "
             "VALUES ('s',1,9,9,NOW()) ON DUPLICATE KEY UPDATE stream_seq=VALUES(stream_seq);")


def statements(sql):
    return [s.strip() for s in re.sub(r'--[^\n]*', '', sql).split(';') if s.strip()]


@unittest.skipUnless(shutil.which('docker'), 'needs docker')
class MigrateFrDrs36(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cid = subprocess.run(['docker', 'run', '-d', '--rm', '-e', 'MARIADB_ROOT_PASSWORD=pw', 'mariadb:11.4'],
                                 check=True, capture_output=True, text=True).stdout.strip()
        for _ in range(90):
            if cls.q('select 1', db='mysql').stdout.strip() == '1':
                break
            time.sleep(1)

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '-f', cls.cid], capture_output=True)

    @classmethod
    def q(cls, sql, db='traderx'):
        return subprocess.run(['docker', 'exec', '-i', cls.cid, 'mariadb', '-uroot', '-ppw', '-N', '-B', db],
                              input=sql, capture_output=True, text=True)

    def volume(self, setup):
        self.q('DROP DATABASE IF EXISTS traderx; CREATE DATABASE traderx;', db='mysql'); self.q(setup)

    def migrate(self):
        p = self.q(MIGRATION); self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn('projection_cursor.stream_created_ns: 1', p.stdout)

    def test_every_starting_volume_twice(self):
        for name, setup in (('empty', ''), ('pre-FR-DRS-36 with a cursor', OLD_TABLE), ('migrated', MIGRATION)):
            with self.subTest(name):
                self.volume(setup); self.migrate(); self.migrate()
                if 'cursor' in name:
                    self.assertEqual(self.q("SELECT stream_seq, consensus_seq, stream_created_ns FROM projection_cursor "
                                            "WHERE projection_scope='s' AND partition_id=0").stdout, '42\t7\tNULL\n')
                p = self.q(OLD_WRITE); self.assertEqual(p.returncode, 0, p.stderr)   # rollback: the old build writes

    def test_it_is_the_shipped_ddl(self):
        shipped = statements(RI06)
        for s in statements(MIGRATION)[:2]:
            self.assertIn(s, shipped)

    def test_control_the_old_table_lacks_the_column(self):
        self.volume(OLD_TABLE)
        p = self.q("SELECT stream_created_ns FROM projection_cursor")
        self.assertNotEqual(p.returncode, 0)   # what the FR-DRS-36 build would hit on an unmigrated volume


if __name__ == '__main__':
    unittest.main()
