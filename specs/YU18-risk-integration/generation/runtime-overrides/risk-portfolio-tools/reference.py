"""Small analytic oracle only: flat continuous ACT/365, regular ICMA semiannual coupons.

No curve interpolation, scheduling, simulations, Greeks or engine pricing implementation.
The fixtures explicitly supply cashflows; PV = sum(CF * exp(-r * days/365)).
"""
import math
from datetime import date


def golden_reference():
    asof = date(2026, 1, 15)
    # Explicit independently enumerated cashflows, for long bill and regular note.
    flows = [(date(2027, 1, 15), 100_000.0)]
    bill = math.fsum(cf * math.exp(-0.03 * (d - asof).days / 365) for d, cf in flows)
    note_flows = [(date(2026, 4, 15), 2000.0), (date(2026, 10, 15), 102_000.0)]
    note = math.fsum(cf * math.exp(-0.03 * (d - asof).days / 365) for d, cf in note_flows)
    accrued = 2000 * (asof - date(2025, 10, 15)).days / (date(2026, 4, 15) - date(2025, 10, 15)).days
    return {"trade_ids": ["golden-bill-long", "golden-bill-short", "golden-note-long", "golden-note-short"],
            "dirty_npv": [bill, -bill, note, -note], "accrued_usd": [0.0, 0.0, accrued, -accrued],
            "clean_npv": [bill, -bill, note - accrued, -note + accrued], "net_npv": 0.0,
            "scope": "synthetic regular flat-curve base pricing only; not general financial validation"}


def check_result(body, result):
    if not isinstance(result, dict):
        raise ValueError("completed result must be an object")
    ids = [t["trade_id"] for t in body["trades"]]
    values = result.get("base_npv_per_trade")
    if result.get("trade_ids") != ids or not isinstance(values, list) or len(values) != len(ids):
        raise ValueError("result coverage/identity mismatch")
    base = result.get("base_npv")
    if type(base) not in (int, float) or not math.isfinite(base):
        raise ValueError("invalid base NPV")
    for t, v in zip(body["trades"], values):
        if type(v) not in (int, float) or not math.isfinite(v) or v * t["face_amount"] <= 0:
            raise ValueError("invalid NPV/sign")
    if not math.isclose(math.fsum(values), base, rel_tol=1e-10, abs_tol=1e-6):
        raise ValueError("base NPV does not equal per-trade sum")
    if result.get("scenario_risk_available") is not body["scenario_risk"]:
        raise ValueError("scenario availability mismatch")
    if not body["scenario_risk"] and (result.get("npv_cube") != [] or result.get("exposure") is not None):
        raise ValueError("unexpected scenario output")
    if ids == golden_reference()["trade_ids"]:
        expected = golden_reference()
        for actual, target in zip(values, expected["dirty_npv"]):
            if not math.isclose(actual, target, rel_tol=1e-10, abs_tol=1e-6):
                raise ValueError("golden dirty NPV mismatch")
        return "small-analytic-golden-passed"
    return "identity-count-sign-accounting-passed; no independent pricing oracle for this corpus"
