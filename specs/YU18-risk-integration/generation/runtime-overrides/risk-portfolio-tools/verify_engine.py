"""Opt-in CPU proof against a clean pinned engine checkout, using its dependencies.

Run with the engine's Python environment. File generation never imports the engine.
"""
import argparse
import copy
import json
import math
import subprocess
import sys
from pathlib import Path

from corpus import ENGINE, digest, load_request
from reference import check_result, golden_reference


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--engine-root", required=True, type=Path)
    p.add_argument("--evidence", required=True, type=Path)
    args = p.parse_args()
    root = args.engine_root.resolve()
    rev = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if rev != ENGINE or subprocess.call(["git", "-C", str(root), "diff", "--quiet", "HEAD", "--", "engine"]):
        raise SystemExit("require clean pinned engine sources")
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(root))
    from engine.api.market_schemas import MarketPortfolioRequestSchema
    from engine.api.schemas import PortfolioResultSchema
    from engine.instruments.treasury import accrued_interest
    from engine.portfolio import price_portfolio
    from engine.portfolio.market_path import validate_request
    fixture = Path(__file__).parent / "fixtures/usd-treasury-golden.json"
    body = load_request(fixture)
    request = MarketPortfolioRequestSchema.model_validate(body).to_dataclass()
    validate_request(request)
    native = price_portfolio(request)
    result = PortfolioResultSchema.from_dataclass(native).model_dump(mode="json")
    checks = check_result(body, result)
    expected = golden_reference()
    accrued = [accrued_interest(t) for t in request.trades]
    for actual, target in zip(accrued, expected["accrued_usd"]):
        if not math.isclose(actual, target, rel_tol=1e-10, abs_tol=1e-6):
            raise AssertionError("independent accrued interest mismatch")
    # Engine-specific negative controls (beyond local profile checks).
    rejected = []
    for label, mutate in [
        ("duplicate-identity", lambda b: b["trades"][1].update(trade_id=b["trades"][0]["trade_id"])),
        ("missing-USD-market", lambda b: b["market"].update(currencies={})),
        ("unsupported-instrument", lambda b: b["trades"][0].update(trade_type="equity")),
        ("inconsistent-note-schedule", lambda b: b["trades"][2]["coupon_schedule"][1].update(start_date="2026-04-16")),
        ("unknown-field", lambda b: b.update(portfolio_id="not-in-base-schema")),
    ]:
        bad = copy.deepcopy(body)
        mutate(bad)
        try:
            validate_request(MarketPortfolioRequestSchema.model_validate(bad).to_dataclass())
        except Exception as exc:
            rejected.append({"case": label, "exception": type(exc).__name__, "detail": str(exc)})
        else:
            raise AssertionError("engine accepted negative control " + label)
    args.evidence.mkdir(parents=True, exist_ok=False)
    (args.evidence / "result.json").write_text(json.dumps(result, sort_keys=True, indent=2, allow_nan=False) + "\n")
    report = {"engine_revision": rev, "request_sha256": digest(fixture), "checks": checks,
              "reference": expected, "actual_accrued_usd": accrued, "negative_controls": rejected,
              "schema_sha256": digest(root / "engine/api/market_schemas.py"),
              "scope": "four synthetic USD positions, direct CPU engine; no HTTP or broad financial validation"}
    (args.evidence / "report.json").write_text(json.dumps(report, sort_keys=True, indent=2) + "\n")
    print(json.dumps({"checks": checks, "negative_controls": len(rejected), "evidence": str(args.evidence)}))


if __name__ == "__main__":
    main()
