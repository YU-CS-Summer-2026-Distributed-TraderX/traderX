import math
import unittest

import var_es_e2e as m


def position(**over):
    row = {'accountId': '22214', 'security': 'UST-BILL-20270630', 'instrumentType': 'TREASURY', 'quantity': '100000',
           'contractMultiplier': '1', 'currency': 'USD', 'coupon': '0', 'maturityDate': '2027-06-30'}
    row.update(over)
    return row


def contract(**over):
    row = {'contractId': 'SW-7', 'accountId': '22214', 'payReceive': 'PAY_FIXED', 'notional': '10000000',
           'fixedRate': '0.042000', 'floatIndex': 'USD-SOFR', 'effectiveDate': '2026-10-28', 'maturityDate': '2031-10-28',
           'paymentFrequency': '1Y', 'dayCount': 'ACT/360', 'currency': 'USD', 'productType': 'SWAP'}
    row.update(over)
    return row


class MappingTest(unittest.TestCase):
    def test_bill_maps_to_an_engine_bond(self):
        t = m.map_position(position())
        self.assertEqual(('bond', 100000.0, '2027-06-30', 0.0, []),
                         (t['trade_type'], t['face_amount'], t['maturity_date'], t['coupon_rate'], t['coupon_schedule']))

    def test_equity_and_option_are_refused_by_name(self):
        for kind in ('EQUITY', 'OPTION'):
            with self.assertRaises(m.Refused) as ctx:
                m.map_position(position(instrumentType=kind, security='AAPL'))
            self.assertEqual(kind, ctx.exception.instrument_class)
            self.assertIn('no ' + kind.lower() + ' product', ctx.exception.reason)

    def test_act360_swap_is_refused_not_defaulted(self):
        with self.assertRaises(m.Refused) as ctx:
            m.map_contract(contract())
        self.assertIn('ACT/360', ctx.exception.reason)
        self.assertIn('refused, not defaulted', ctx.exception.reason)

    def test_act365f_is_the_engines_act365(self):
        t = m.map_contract(contract(dayCount='ACT/365F', floatIndex='GBP-SONIA', currency='GBP', payReceive='RECEIVE_FIXED'))
        self.assertEqual(('swap', 'ACT/365', 12, False, 'GBP'),
                         (t['trade_type'], t['accrual_day_count'], t['index_tenor_months'], t['payer'], t['currency']))
        market = m.market_for([t], '2026-10-26')
        self.assertIn('GBP-SIMINDEX-12M', market['currencies']['GBP']['index_curves'])
        self.assertEqual({'GBPUSD': 1.27}, market['fx_spots'])
        self.assertIn('USD', market['currencies'], 'the base currency is a market currency even with no USD trade')
        self.assertEqual(len(m.PILLARS) * 3, len(m.factor_labels(market)))


class CoverageTest(unittest.TestCase):
    """FR-RP-VARES-03/04: coupon Treasuries, swaptions, and who owns each refusal."""
    def test_coupon_note_schedule_is_the_extracts_own(self):
        t = m.map_position(position(security='UST-4.250-20291115', coupon='4.250', maturityDate='2029-11-15',
                                    lastCouponDate='2026-05-15'))
        self.assertEqual(0.0425, t['coupon_rate'])
        self.assertEqual({'start_date': '2026-05-15', 'end_date': '2026-11-15'}, t['coupon_schedule'][0])
        self.assertEqual('2029-11-15', t['coupon_schedule'][-1]['end_date'])
        self.assertEqual(7, len(t['coupon_schedule']))
        # end-of-month anchor: every date measured from maturity, clamped (Aug 31 -> Feb 28 -> Aug 31)
        t = m.map_position(position(coupon='1', maturityDate='2027-08-31', lastCouponDate='2026-08-31'))
        self.assertEqual(['2026-08-31', '2027-02-28'], [p['start_date'] for p in t['coupon_schedule']])
        with self.assertRaises(m.Refused):
            m.map_position(position(coupon='4.250', maturityDate='2029-11-15', lastCouponDate='2026-05-14'))

    def test_swaption_styles_map_to_the_engines_three_products(self):
        base = contract(contractId='SWPT-9', productType='SWAPTION', dayCount='ACT/365F', currency='GBP', floatIndex='GBP-SONIA',
                        effectiveDate='2027-10-28', maturityDate='2032-10-28', expiryDate='2027-10-26')
        eu = m.map_contract(dict(base, exerciseStyle='EUROPEAN'))
        self.assertEqual(('european_swaption', '2027-10-26', 'Long', True), (eu['trade_type'], eu['exercise_date'], eu['long_short'], eu['payer']))
        be = m.map_contract(dict(base, exerciseStyle='BERMUDAN'))
        self.assertEqual(['2027-10-26', '2028-10-28', '2029-10-28', '2030-10-28', '2031-10-28'], be['exercise_dates'])
        am = m.map_contract(dict(base, exerciseStyle='AMERICAN'))
        self.assertEqual(('2027-10-26', '2031-10-28'), (am['first_exercise_date'], am['last_exercise_date']))
        market = m.market_for([eu], '2026-10-26')
        self.assertEqual(len(m.VOL_TENORS), len(market['currencies']['GBP']['swaption_vols']['vols']))
        with self.assertRaises(m.Refused) as ctx:
            m.map_contract(dict(base, exerciseStyle='EUROPEAN', dayCount='ACT/360'))
        self.assertEqual(('engine', 'R3'), (m.refusal(ctx.exception)['owner'], m.refusal(ctx.exception)['request']))

    def test_every_engine_gap_names_its_request(self):
        for kind, request in (('EQUITY', 'R1'), ('OPTION', 'R2'), ('CORPORATE', 'R4')):
            with self.assertRaises(m.Refused) as ctx:
                m.map_position(position(instrumentType=kind))
            self.assertEqual({'owner': 'engine', 'request': request},
                             {k: m.refusal(ctx.exception)[k] for k in ('owner', 'request')})
        self.assertEqual('traderx', m.refusal(m.Refused('TREASURY', 'x', 'zero face'))['owner'])


class RankRuleTest(unittest.TestCase):
    """The engine's rule (engine/risk/market/var_es.py): idx = ceil(N(1-p)) - 1 on the ascending sort,
    VaR = max(-sorted[idx], 0), ES = -mean(sample strictly below -VaR)."""
    def test_var_and_es_on_a_known_sample(self):
        pnl = [-100.0, -50.0, -10.0, 5.0, 20.0, 30.0, 40.0, 60.0, 80.0, 90.0]   # N = 10
        var, es = m.var_es(pnl, 0.9)        # ceil(1) - 1 = 0 -> VaR 100, tail below -100 empty -> NaN
        self.assertEqual(100.0, var)
        self.assertTrue(math.isnan(es))
        var, es = m.var_es(pnl, 0.75)       # ceil(2.5) - 1 = 2 -> VaR 10, tail {-100, -50} -> ES 75
        self.assertEqual((10.0, 75.0), (var, es))
        self.assertEqual(0.0, m.var_es([1.0, 2.0, 3.0], 0.5)[0], 'a sample with no loss has VaR 0 (clamped)')

    def test_finite_nonzero(self):
        self.assertTrue(m.finite_nonzero(1.5))
        for x in (0, 0.0, None, float('nan'), float('inf'), 'x'):
            self.assertFalse(m.finite_nonzero(x))


if __name__ == '__main__':
    unittest.main()
