"""Small analytic oracle only: flat continuous ACT/365, regular ICMA semiannual coupons.

No curve interpolation, scheduling, simulations, Greeks or engine pricing implementation.
The fixtures explicitly supply cashflows; PV = sum(CF * exp(-r * days/365)).
"""
import math
from datetime import date
from profiles import CURRENT, request_profile, trades as positions


def analytic_reference(body):
    """Restricted flat ACT/365 curve, explicit regular semiannual cashflows only.

    No engine import, curve interpolation, model calibration or simulation.
    """
    asof = date.fromisoformat(body["market"]["asof"])
    rate = body["market"]["currencies"]["USD"]["discount_curve"]["rates"][0]
    values, accrued = [], []
    for trade in positions(body):
        face = trade["face_amount"]
        maturity = date.fromisoformat(trade["maturity_date"])
        flows = [(maturity, face)]
        accrual = 0.0
        for period in trade["coupon_schedule"]:
            start, end, payment = (date.fromisoformat(period[k]) for k in ("start_date", "end_date", "payment_date"))
            coupon = face * trade["coupon_rate"] / 2
            if payment > asof:
                flows.append((payment, coupon))
            if start <= asof < end:
                accrual = coupon * (asof - start).days / (end - start).days
        values.append(math.fsum(cf * math.exp(-rate * (d - asof).days / 365) for d, cf in flows))
        accrued.append(accrual)
    return {"dirty_npv": values, "accrued_usd": accrued}


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
    ids = [t["trade_id"] for t in positions(body)]
    if request_profile(body) == CURRENT:
        return check_current_result(body, result, ids)
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


def _within(requested, run):
    """Every field the request gave is the one the run used (the run adds every default)."""
    if isinstance(requested, dict):
        return isinstance(run, dict) and all(k in run and _within(v, run[k]) for k, v in requested.items())
    return requested == run


def request_binding(body, result):
    """The result's own echo of what was run: analytics, date, currency and the configuration as
    run. Witnesses a run polled by ID; prices and economics are checked against the oracle."""
    return (isinstance(result, dict) and result.get("analytics") == body["analytics"]
            and result.get("asof") == body["market"]["asof"] and result.get("base_currency") == "USD"
            and _within(body["config"], result.get("config")))


def _float64(policy):
    return all(policy.get(stage, {}).get(field) == "float64" for stage in ("simulation", "market", "pricing")
               for field in ("compute", "storage", "accumulate"))


def check_current_result(body, result, ids):
    if not request_binding(body, result):
        raise ValueError("result was run from another request")
    rows = result.get("trades")
    if not isinstance(rows, list) or not all(isinstance(r, dict) for r in rows) or [r.get("trade_id") for r in rows] != ids:
        raise ValueError("result coverage/identity/order mismatch")
    if any(r.get("product") != "bond" for r in rows):
        raise ValueError("result product mismatch")
    values = [r.get("npv") for r in rows]
    expected = analytic_reference(body)["dirty_npv"]
    for value, target in zip(values, expected):
        if type(value) not in (int, float) or not math.isfinite(value) or not math.isclose(value, target, rel_tol=1e-10, abs_tol=1e-6):
            raise ValueError("independent synthetic dirty NPV mismatch")
    base = result.get("npv")
    if type(base) not in (int, float) or not math.isfinite(base) or not math.isclose(base, math.fsum(values), rel_tol=1e-10, abs_tol=1e-6):
        raise ValueError("base NPV does not equal per-trade sum")
    # The configuration as run; an npv-only run carries no device report of its own.
    policy = result["config"].get("precision", {})
    if not _float64(policy) or policy.get("by_product") != {} or policy.get("by_trade") != {}:
        raise ValueError("current synthetic acceptance requires float64 throughout")
    if result.get("warnings") != []:
        raise ValueError("selected float64 Treasury profile returned warnings")
    if any(result.get(k) is not None for k in ("sensitivities", "market_risk", "calibration")):
        raise ValueError("unexpected analytic output")
    if "exposure" not in body["analytics"]:
        if result.get("exposure") is not None or any(r.get("exposure") is not None or r.get("greeks") is not None for r in rows):
            raise ValueError("unexpected scenario output")
    else:
        section = result.get("exposure")
        samples, dates = body["config"]["simulation"]["samples"], body["config"]["simulation"]["dates"]
        if not isinstance(section, dict) or section.get("measure") != "risk-neutral-pricing" or section.get("dates") != dates:
            raise ValueError("exposure measure/date grid mismatch")
        precision = section.get("precision")
        if (not isinstance(precision, dict) or precision.get("backend") != "cpu" or not precision.get("devices")
                or precision.get("paths") != samples or not _float64(precision.get("policy", {}))):
            raise ValueError("current local profile requires an attributed float64 CPU exposure")
        cube = section.get("npv_cube")
        if (not isinstance(cube, dict) or cube.get("form") != "inline" or cube.get("shape") != [samples, len(dates), len(ids)]
                or cube.get("axes") != ["scenario", "date", "trade"] or cube.get("items", {}).get("ids") != ids):
            raise ValueError("scenario cube coverage mismatch")
        values = cube.get("values")
        if not isinstance(values, list) or len(values) != samples:
            raise ValueError("scenario cube coverage mismatch")
        for path in values:
            if len(path) != len(dates) or any(len(row) != len(ids) for row in path):
                raise ValueError("scenario cube shape mismatch")
            if any(type(x) not in (int, float) or not math.isfinite(x) for row in path for x in row):
                raise ValueError("nonfinite scenario value")
        if not isinstance(section.get("netting_set"), dict) or any(not isinstance(r.get("exposure"), dict) for r in rows):
            raise ValueError("missing exposure profiles")
        # The cube has future dates only; profiles prepend t=0.
        asof = date.fromisoformat(body["market"]["asof"])
        times = [0.0] + [(date.fromisoformat(d) - asof).days / 365 for d in dates]
        for profile, initial in [(section["netting_set"], base)] + [(r["exposure"], v) for r, v in zip(rows, expected)]:
            if profile.get("times") != times:
                raise ValueError("exposure date grid mismatch")
            for field, initial_value in (("epe", max(initial, 0)), ("ene", max(-initial, 0))):
                values = profile.get(field)
                if not isinstance(values, list) or len(values) != len(times) or any(type(x) not in (int, float) or not math.isfinite(x) or x < 0 for x in values):
                    raise ValueError("invalid exposure values/coverage")
                if not math.isclose(values[0], initial_value, rel_tol=1e-10, abs_tol=1e-6):
                    raise ValueError("t=0 exposure contradicts independent prices")
    return "synthetic-flat-curve-cashflows-passed; general-financial-validation=false"
