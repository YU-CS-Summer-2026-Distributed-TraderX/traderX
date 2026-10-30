"""FR-DRS-33 recon, against a real MariaDB: query 3 ignores a /trades ticket booking ("<epoch>-0", no order) and still
finds a real order with no row; query 1 still counts a stale filled quantity. Skipped without docker."""
import shutil
import subprocess
import time
import unittest
from pathlib import Path

SQL = Path(__file__).with_name('fr-drs-33-order-fill-recon.sql').read_text()
SCHEMA = '''create table trades (id varchar(64), quantity int, sourceorderid varchar(64), projectionscope varchar(64));
create table orderbook (orderid varchar(64), projectionscope varchar(64), status varchar(16), quantity int,
  remainingquantity int, filledquantity int, consensussequence bigint, updatedat datetime);'''


@unittest.skipUnless(shutil.which('docker'), 'needs docker')
class OrderFillRecon(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cid = subprocess.run(['docker', 'run', '-d', '--rm', '-e', 'MARIADB_ROOT_PASSWORD=pw', '-e',
                                  'MARIADB_DATABASE=traderx', 'mariadb:11.4'], check=True, capture_output=True,
                                 text=True).stdout.strip()
        for _ in range(90):
            if cls.q('select 1').strip() == '1':
                break
            time.sleep(1)
        cls.q(SCHEMA)

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '-f', cls.cid], capture_output=True)

    @classmethod
    def q(cls, sql):
        return subprocess.run(['docker', 'exec', '-i', cls.cid, 'mariadb', '-uroot', '-ppw', '-N', '-B', 'traderx'],
                              input=sql, capture_output=True, text=True).stdout

    def recon(self, rows):
        return self.q('delete from trades; delete from orderbook;' + rows + SQL).strip().splitlines()

    FILLED = ("insert into orderbook values ('1-1','s','FILLED',1,0,1,1,now()); "
              "insert into trades values ('1-B',1,'1-1','s');")
    TICKET = "insert into trades values ('13-B',1,'1-0','s'),('14-S',1,'1-0','s');"

    def test_a_ticket_booking_passes(self):
        out = self.recon(self.FILLED + self.TICKET)
        self.assertEqual(out, ['1\t0\t0\t0'])   # q1 agree 1, disagree 0; q2 and q3 empty

    def test_a_real_order_without_a_row_is_still_found(self):
        out = self.recon(self.FILLED + self.TICKET + "insert into trades values ('2-S',1,'1-10','s');")
        self.assertEqual(out[-1], 's\t1\t1')

    def test_a_stale_fill_is_still_a_disagreement(self):
        out = self.recon(self.FILLED.replace("0,1,1,now()", "0,0,1,now()"))
        self.assertEqual(out[0], '0\t0\t1\t0')


if __name__ == '__main__':
    unittest.main()
