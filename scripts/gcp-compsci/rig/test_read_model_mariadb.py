"""FR-PRG-27, against a real MariaDB (the eod-price-db image): rig.sh's own sql() and read_model(), with kc pointed at a
local container. The fingerprint must be blind to row order and see any one changed cell (NULL apart from 'NULL'), and
at production size it must be one aggregate, not a dump. Skipped without docker."""
import shutil
import subprocess
import time
import unittest
from pathlib import Path

RIG = Path(__file__).with_name('rig.sh')
IMAGE = 'mariadb:11.4'
SCHEMA = '''
create table trades (id varchar(64) primary key, accountid int, security varchar(32), side varchar(8), quantity int,
  price decimal(18,6), state varchar(16));
create table positions (accountid int, security varchar(32), quantity int, averagecostbasis decimal(18,6),
  primary key (accountid, security));
create table orderbook (orderid varchar(64) primary key, accountid int, security varchar(32), side varchar(8),
  status varchar(16), quantity int, remainingquantity int);
'''


@unittest.skipUnless(shutil.which('docker'), 'needs docker')
class ReadModelFingerprint(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cid = subprocess.run(['docker', 'run', '-d', '--rm', '-e', 'MARIADB_ROOT_PASSWORD=pw', '-e',
                                  'MARIADB_DATABASE=traderx', IMAGE], check=True, capture_output=True,
                                 text=True).stdout.strip()
        for _ in range(90):
            if cls.q('select 1').strip() == '1':
                break
            time.sleep(1)
        else:
            raise RuntimeError('mariadb did not start')
        cls.q(SCHEMA)

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '-f', cls.cid], capture_output=True)

    @classmethod
    def q(cls, sql):
        return subprocess.run(['docker', 'exec', cls.cid, 'mariadb', '-uroot', '-ppw', '-N', '-B', 'traderx', '-e', sql],
                              capture_output=True, text=True).stdout

    def read_model(self):
        # kc exec deploy/eod-price-db -c mariadb -- sh -c CMD  ->  docker exec CID sh -c CMD
        body = (f'RIG_LIB=1 . "{RIG}"; tp_mode(){{ echo rows; }}; '
                f'kc(){{ docker exec -e MARIADB_ROOT_PASSWORD=pw {self.cid} "${{@:6}}"; }}; read_model')
        p = subprocess.run(['bash', '-c', body], capture_output=True, text=True, timeout=600)
        return p.returncode, p.stdout

    def reset(self, rows):
        self.q('delete from trades; delete from positions; delete from orderbook;' + rows)

    def test_order_blind_and_every_cell_seen(self):
        base = ("insert into trades values ('1-B',22214,'MSFT','Buy',1,393,'Settled'),('2-S',42422,'MSFT','Sell',1,393,"
                "'Settled'); insert into positions values (22214,'MSFT',1,393); insert into orderbook values "
                "('1-1',22214,'MSFT','Buy','FILLED',1,0);")
        self.reset(base); rc, a = self.read_model(); self.assertEqual(rc, 0, a)
        self.assertRegex(a, r'^trades=2:\d+:\d+\npositions=1:\d+:\d+\norderbook=1:\d+:\d+\n$')
        self.reset(base.replace("('1-B',22214,'MSFT','Buy',1,393,'Settled'),('2-S',42422,'MSFT','Sell',1,393,'Settled')",
                                "('2-S',42422,'MSFT','Sell',1,393,'Settled'),('1-B',22214,'MSFT','Buy',1,393,'Settled')"))
        self.assertEqual(self.read_model()[1], a)
        for old, new in (("'Settled'),('2-S'", "'New'),('2-S'"), ("(22214,'MSFT',1,393)", "(22214,'MSFT',2,393)"),
                         ("'FILLED',1,0", "'FILLED',1,1"), ("393,'Settled'),('2-S'", "393.000001,'Settled'),('2-S'")):
            self.reset(base.replace(old, new, 1)); self.assertNotEqual(self.read_model()[1], a, new)
        self.reset(base.replace("'Buy','FILLED'", "NULL,'FILLED'")); n = self.read_model()[1]
        self.reset(base.replace("'Buy','FILLED'", "'NULL','FILLED'")); self.assertNotEqual(self.read_model()[1], n)

    def test_an_empty_table_and_a_dead_db(self):
        self.reset(''); rc, out = self.read_model()
        self.assertEqual((rc, out), (0, 'trades=0:NULL:NULL\npositions=0:NULL:NULL\norderbook=0:NULL:NULL\n'))
        body = f'RIG_LIB=1 . "{RIG}"; tp_mode(){{ echo rows; }}; kc(){{ return 1; }}; read_model'
        self.assertEqual(subprocess.run(['bash', '-c', body], capture_output=True).returncode, 1)

    def test_production_size_is_one_aggregate(self):
        # 3.4M trade rows, as on production 2026-10-30; the answer is three numbers, never the rows
        self.reset("insert into trades select concat(seq,if(seq%2,'-B','-S')),22214+seq%7,'MSFT',if(seq%2,'Buy','Sell'),"
                   "1,393,'Settled' from seq_1_to_3400000;")
        t = time.time(); rc, out = self.read_model()
        self.assertEqual(rc, 0, out); self.assertTrue(out.startswith('trades=3400000:'), out)
        print(f'\n3.4M rows fingerprinted in {time.time() - t:.1f} s: {out.splitlines()[0]}')


if __name__ == '__main__':
    unittest.main()
