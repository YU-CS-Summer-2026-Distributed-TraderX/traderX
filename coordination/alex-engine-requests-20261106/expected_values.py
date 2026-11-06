#!/usr/bin/env python3
"""Independent ORE/QuantLib expected values for the four engine requests (R1-R4).

Run with the engine's own environment (it has the ORE wheel):
    <engine venv>/bin/python coordination/alex-engine-requests-20261106/expected_values.py
Market = the VaR/ES proof's synthetic market: flat continuous ACT/365F zero rates, USD discount 4.0%,
index 4.1%; equity inputs as stated per request. Prints one JSON object.
"""
import json
import math

import ORE as ql

ASOF = ql.Date(26, 10, 2026)
ql.Settings.instance().evaluationDate = ASOF
A365 = ql.Actual365Fixed()


def flat(rate):
    return ql.YieldTermStructureHandle(ql.FlatForward(ASOF, rate, A365, ql.Continuous))


disc, fwd = flat(0.040), flat(0.041)
out = {}

# R3: USD-SOFR OIS, pay fixed 4.2% on 10mm, 2026-10-28 -> 2031-10-28, annual both legs, ACT/360,
# SOFR compounded in arrears, 2-business-day payment lag, US government-bond calendar, ModifiedFollowing.
def ois(fixed_dc):
    cal = ql.UnitedStates(ql.UnitedStates.GovernmentBond)
    sched = ql.Schedule(ql.Date(28, 10, 2026), ql.Date(28, 10, 2031), ql.Period(1, ql.Years), cal,
                        ql.ModifiedFollowing, ql.ModifiedFollowing, ql.DateGeneration.Forward, False)
    swap = ql.OvernightIndexedSwap(ql.OvernightIndexedSwap.Payer, 10_000_000, sched, 0.042, fixed_dc,
                                   ql.Sofr(fwd), 0.0, 2, ql.ModifiedFollowing, cal)
    swap.setPricingEngine(ql.DiscountingSwapEngine(disc))
    return swap.NPV()


out['R3_usd_sofr_ois_npv'] = ois(ql.Actual360())
out['R3_same_terms_fixed_leg_act365_npv'] = ois(ql.Actual365Fixed())

# R2: listed equity option, AAPL 2026-12-18 150 call (OCC AAPL261218C00150000), American, 100 multiplier,
# spot 150, vol 30%, r 4.0% (the USD discount curve), dividend yield 0.5%.
spot, vol, q = 150.0, 0.30, 0.005
proc = ql.BlackScholesMertonProcess(ql.QuoteHandle(ql.SimpleQuote(spot)), flat(q), disc,
                                    ql.BlackVolTermStructureHandle(ql.BlackConstantVol(ASOF, ql.NullCalendar(), vol, A365)))
opt = ql.VanillaOption(ql.PlainVanillaPayoff(ql.Option.Call, 150.0), ql.AmericanExercise(ASOF, ql.Date(18, 12, 2026)))
opt.setPricingEngine(ql.FdBlackScholesVanillaEngine(proc, 400, 400))
out['R2_option_price_per_share'] = opt.NPV()
out['R2_option_npv_one_contract'] = opt.NPV() * 100
out['R2_option_delta_per_share'] = opt.delta()

# R1: cash equity, 100 AAPL at spot 150, USD: NPV = 100 x 1 x 150 x 1. Ten-day 99% VaR on a lognormal spot
# with 30% annual vol (no drift): 15000 x (1 - exp(-z99 x 0.30 x sqrt(10/252))).
z99 = 2.3263478740408408
out['R1_equity_npv'] = 100 * 1 * 150.0
out['R1_equity_var99_10d_lognormal'] = 15000 * (1 - math.exp(-z99 * 0.30 * math.sqrt(10 / 252)))

# R4: corporate bond GS 5.750% 2036-01-24, 1mm face, semiannual from maturity, 30/360 (bond basis), on the
# USD discount curve plus a flat 120bp continuous credit spread. Dirty NPV.
sched = ql.Schedule(ql.Date(24, 7, 2026), ql.Date(24, 1, 2036), ql.Period(6, ql.Months), ql.NullCalendar(),
                    ql.Unadjusted, ql.Unadjusted, ql.DateGeneration.Backward, False)
bond = ql.FixedRateBond(0, 1_000_000, sched, [0.0575], ql.Thirty360(ql.Thirty360.BondBasis))
spreaded = ql.YieldTermStructureHandle(ql.ZeroSpreadedTermStructure(disc, ql.QuoteHandle(ql.SimpleQuote(0.012)),
                                                                     ql.Continuous, ql.Annual, A365))
bond.setPricingEngine(ql.DiscountingBondEngine(spreaded))
out['R4_corporate_dirty_npv'] = bond.NPV()
bond.setPricingEngine(ql.DiscountingBondEngine(disc))
out['R4_same_bond_treasury_discounted_npv'] = bond.NPV()
print(json.dumps(out, indent=2))
