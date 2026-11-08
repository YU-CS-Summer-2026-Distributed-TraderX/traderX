import re
import unittest

import book_gen as g

#: OccSymbol.isOption: uppercase root, yymmdd, C or P, strike x 1000 in digits.
OCC = re.compile(r'^[A-Z]+\d{6}[CP]\d+$')


class BookGenTest(unittest.TestCase):
    """SC-RP-BOOK-01."""
    def test_same_arguments_same_plan(self):
        self.assertEqual(g.plan(5, crosses=50, otc=40)['planSha256'], g.plan(5, crosses=50, otc=40)['planSha256'])
        self.assertNotEqual(g.plan(5, crosses=50, otc=40)['planSha256'], g.plan(6, crosses=50, otc=40)['planSha256'])

    def test_every_class_and_the_booked_records_limits(self):
        p = g.plan(3, accounts=24, crosses=500, otc=600)
        kinds = {s['kind'] for s in p['securities']}
        self.assertEqual({'EQUITY', 'ETF', 'OPTION', 'TREASURY', 'CORPORATE'}, kinds)
        styles = {o['body'].get('exerciseStyle', 'SWAP') for o in p['otc']}
        self.assertEqual({'SWAP', 'European', 'Bermudan', 'American'}, styles)
        self.assertEqual({c[0] for c in g.CONVENTIONS}, {o['body']['conventions'] for o in p['otc']})
        self.assertTrue(all(0 < o['body']['notional'] <= 2 ** 31 - 1 for o in p['otc']), 'int notional')
        self.assertTrue(all(x['qty'] <= 1_000_000 for x in p['crosses']), 'MAX_ORDER_QUANTITY')
        self.assertTrue(all(x['seller'] != x['buyer'] for x in p['crosses']))
        for s in p['securities']:
            self.assertEqual(s['kind'] == 'OPTION', bool(OCC.match(s['ticker'])), s['ticker'])
        for o in p['otc']:
            b = o['body']
            if 'expiryDate' in b:
                self.assertLessEqual(b['expiryDate'], b['effectiveDate'], 'FR-OTC36: expiry on or before the underlying')
        self.assertEqual({'RATES-NY', 'EQ-CASH-NY'} <= {a['desk'] for a in p['accounts']}, True)

    def test_a_plan_past_one_epoch_is_refused(self):
        with self.assertRaises(SystemExit):
            g.plan(1, accounts=65)
        with self.assertRaises(SystemExit):
            g.plan(1, otc=4097)


if __name__ == '__main__':
    unittest.main()
