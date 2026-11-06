"""FR-DRS-33 recon on the history store (FR-THT-05), against a real ClickHouse 25.8 with the history feed's column
types: the control counts agreeing orders, a stale fill and a missing order are found, a ticket side is ignored.
Skipped without docker."""
import shutil
import subprocess
import time
import unittest
from pathlib import Path

SQL = Path(__file__).with_name('order-fill-recon.clickhouse.sql').read_text()
SCHEMA = '''CREATE TABLE trades (ts DateTime64(3), seq UInt64, part UInt16, trade_seq UInt64, side FixedString(1),
  qty UInt32, order_ref UInt64) ENGINE=MergeTree ORDER BY ts;
CREATE TABLE orders (ts DateTime64(3), seq UInt64, part UInt16, order_ref UInt64, qty UInt32, remaining UInt32,
  status LowCardinality(String), cseq UInt64, ordinal UInt16) ENGINE=MergeTree ORDER BY ts;'''


@unittest.skipUnless(shutil.which('docker'), 'needs docker')
class OrderFillReconClickHouse(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cid = subprocess.run(['docker', 'run', '-d', '--rm', '-e', 'CLICKHOUSE_SKIP_USER_SETUP=1',
                                  'clickhouse/clickhouse-server:25.8'], check=True, capture_output=True, text=True).stdout.strip()
        for _ in range(60):
            if cls.q('SELECT 1').strip() == '1':
                break
            time.sleep(1)
        cls.q(SCHEMA)

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '-f', cls.cid], capture_output=True)

    @classmethod
    def q(cls, sql):
        return subprocess.run(['docker', 'exec', '-i', cls.cid, 'clickhouse-client', '--multiquery'], input=sql,
                              capture_output=True, text=True).stdout

    def recon(self, rows):
        out = self.q('TRUNCATE trades; TRUNCATE orders;' + rows + SQL).strip().splitlines()
        blocks, cur = [], []
        for line in out:                               # three TSVWithNames results, each starting with its header
            if line.split('\t')[0] in ('agree_with_fills', 'order_ref', 'orders_without_events'):
                cur = []; blocks.append(cur)
            cur.append(line.split('\t'))
        return blocks

    # order 1: 10 shares, filled 4 then 6 (two sides); order 2: rests unfilled; order 3 filled 5 then CANCELED.
    GOOD = '''INSERT INTO orders VALUES (now64(3),1,0,1,10,10,'NEW',1,0),(now64(3),2,0,1,10,6,'PARTIALLY_FILLED',2,0),
      (now64(3),3,0,1,10,0,'FILLED',3,0),(now64(3),4,0,2,7,7,'NEW',4,0),(now64(3),5,0,3,9,4,'PARTIALLY_FILLED',5,0),
      (now64(3),6,0,3,9,0,'CANCELED',6,0);
      INSERT INTO trades VALUES (now64(3),1,0,1,'B',4,1),(now64(3),2,0,2,'B',6,1),(now64(3),3,0,3,'S',5,3),
      (now64(3),4,0,4,'B',100,0);'''

    def test_a_consistent_store_agrees_and_the_control_counts(self):
        summary, disagree, missing = self.recon(self.GOOD)
        self.assertEqual(summary[1], ['2', '1', '0'])  # orders 1 and 3 agree with fills, 2 unfilled; the ticket ignored
        self.assertEqual(len(disagree), 1)
        self.assertEqual(missing[1], ['0', '0'])

    def test_a_lost_trade_side_is_a_disagreeing_order(self):
        summary, disagree, _ = self.recon(self.GOOD + "ALTER TABLE trades DELETE WHERE seq = 2 SETTINGS mutations_sync = 1;")
        self.assertEqual(summary[1], ['1', '1', '1'])
        self.assertEqual(disagree[1][:5], ['1', 'FILLED', '10', '4', '1'])

    def test_a_lost_fill_event_before_a_cancel_is_found(self):
        summary, disagree, _ = self.recon(self.GOOD + "ALTER TABLE orders DELETE WHERE seq = 5 SETTINGS mutations_sync = 1;")
        self.assertEqual(summary[1][2], '1')
        self.assertEqual(disagree[1][:4], ['3', 'CANCELED', '0', '5'])

    def test_a_side_whose_order_has_no_event_is_counted(self):
        _, _, missing = self.recon(self.GOOD + "INSERT INTO trades VALUES (now64(3),9,0,9,'S',1,42);")
        self.assertEqual(missing[1], ['1', '1'])


if __name__ == '__main__':
    unittest.main()
