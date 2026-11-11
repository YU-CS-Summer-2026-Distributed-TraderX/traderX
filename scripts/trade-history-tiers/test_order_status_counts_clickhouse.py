"""FR-THT-12: order status counts by order, against a real ClickHouse 25.8 with the history feed's column types. A status
repeated on one order counts that order once and shows as a repeated row. Skipped without docker."""
import shutil
import subprocess
import time
import unittest
from pathlib import Path

SQL = Path(__file__).with_name('order-status-counts.clickhouse.sql').read_text()
SCHEMA = '''CREATE TABLE orders (ts DateTime64(3), seq UInt64, part UInt16, order_ref UInt64, qty UInt32, remaining UInt32,
  status LowCardinality(String), cseq UInt64, ordinal UInt16) ENGINE=MergeTree ORDER BY ts;'''


@unittest.skipUnless(shutil.which('docker'), 'needs docker')
class OrderStatusCountsClickHouse(unittest.TestCase):
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

    def counts(self, rows):
        out = self.q('TRUNCATE orders;' + rows + SQL).strip().splitlines()
        self.assertEqual(out[0].split('\t'), ['status', 'orders', 'rows', 'repeated_rows'])
        return {line.split('\t')[0]: line.split('\t')[1:] for line in out[1:]}

    # order 1: NEW then FILLED; order 2: NEW, rests; order 3: NEW then CANCELED. No position (cseq 0), as the legacy ids.
    ROWS = '''INSERT INTO orders VALUES (now64(3),1,0,1,1,1,'NEW',0,0),(now64(3),2,0,1,1,0,'FILLED',0,0),
      (now64(3),3,0,2,1,1,'NEW',0,0),(now64(3),4,0,3,1,1,'NEW',0,0),(now64(3),5,0,3,1,0,'CANCELED',0,0);'''

    def test_each_status_counts_its_orders(self):
        self.assertEqual(self.counts(self.ROWS), {'CANCELED': ['1', '1', '0'], 'FILLED': ['1', '1', '0'],
                                                  'NEW': ['3', '3', '0']})

    def test_a_repeated_event_counts_its_order_once(self):
        # the same FILLED event twice and order 2's NEW twice: two rows each, one order each
        repeats = "INSERT INTO orders VALUES (now64(3),6,0,1,1,0,'FILLED',0,0),(now64(3),7,0,2,1,1,'NEW',0,0);"
        got = self.counts(self.ROWS + repeats)
        self.assertEqual(got['FILLED'], ['1', '2', '1'])
        self.assertEqual(got['NEW'], ['3', '4', '1'])


if __name__ == '__main__':
    unittest.main()
