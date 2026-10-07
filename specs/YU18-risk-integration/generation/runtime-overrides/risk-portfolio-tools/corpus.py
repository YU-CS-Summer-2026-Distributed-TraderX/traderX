"""Bounded synthetic USD Treasury corpus; no engine dependency or production data."""
import calendar
import hashlib
import json
import math
import random
import shutil
import tempfile
from datetime import date, timedelta
from pathlib import Path

ENGINE = "2df78cb08f782010f61acffc07d78030580e7639"
MAX_TRADES = 100_000
MAX_BYTES = 128 * 1024 * 1024
MAX_CUBE = 64 * 1024 * 1024


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def add_months(d, months):
    year, month = divmod(d.year * 12 + d.month - 1 + months, 12)
    return date(year, month + 1, min(d.day, calendar.monthrange(year, month + 1)[1]))


def header(asof, rate, samples, seed):
    body = {"schema_version": "2", "market": {"asof": asof.isoformat(), "currencies": {
        "USD": {"discount_curve": {"times": [0.0, 1.0, 5.0, 10.0], "rates": [rate] * 4}}}},
        "base_currency": "USD", "scenario_risk": bool(samples), "compute_greeks": False}
    if samples:
        body["simulation"] = {"dates": [(asof + timedelta(days=n)).isoformat() for n in (30, 90, 180)],
                              "base_currency": "USD", "samples": samples, "seed": seed,
                              "ir": {"USD": {"model": "HullWhite", "reversion": 0.03, "volatility": 0.01}}}
    return body


def trade(asof, rng, mode, identity, index):
    # Alternate bills/notes, and pairs of long/short positions, so every quartet covers both.
    note = index % 2 == 1
    sign = 1 if index % 4 < 2 else -1
    amount = 100_000 if mode == "repeated" else rng.randint(1, 20) * 10_000
    t = {"trade_type": "bond", "trade_id": identity, "currency": "USD", "face_amount": sign * amount,
         "redemption_fraction": 1.0, "coupon_rate": 0.0, "coupon_schedule": [],
         "accrual_day_count": "ACT/ACT (ICMA)"}
    if not note:
        days = 365 if mode == "repeated" else rng.choice([28, 91, 182, 364])
        t["maturity_date"] = (asof + timedelta(days=days)).isoformat()
        return t
    # Reference dates advance from one anchor, avoiding accumulated end-of-month clipping.
    anchor = add_months(asof.replace(day=min(asof.day, 28)), -3)
    periods = 4 if mode == "repeated" else rng.randint(2, 10)
    ends = [add_months(anchor, 6 * n) for n in range(periods + 1)]
    t.update(coupon_rate=0.04 if mode == "repeated" else rng.choice([0.02, 0.03, 0.04, 0.05]),
             maturity_date=ends[-1].isoformat(),
             coupon_schedule=[{"start_date": a.isoformat(), "end_date": b.isoformat(),
                               "payment_date": b.isoformat()} for a, b in zip(ends, ends[1:])])
    return t


def generate(output, *, seed=42, trades=100, portfolios=1, asof="2026-10-07", rate=0.03,
             mode="repeated", samples=0, max_bytes=MAX_BYTES):
    """Stream one trade at a time. Publish a fresh directory only after all files succeed."""
    asof = date.fromisoformat(asof)
    if not (0 <= seed <= 2**32 - 1 and 1 <= trades <= MAX_TRADES and 1 <= portfolios <= 1000
            and trades * portfolios <= MAX_TRADES and 0 <= samples <= 128):
        raise ValueError("seed/count/sample limits exceeded")
    if not math.isfinite(rate) or not -0.05 <= rate <= 0.20 or mode not in ("repeated", "heterogeneous"):
        raise ValueError("unsupported rate or mode")
    if not 1 <= max_bytes <= MAX_BYTES:
        raise ValueError("byte limit must be 1..128 MiB")
    cube = samples * 4 * trades * 8  # includes t=0; lower bound, excludes intermediates/JSON
    if cube > MAX_CUBE:
        raise ValueError("raw cube lower bound exceeds 64 MiB; use file-only/base NPV or fewer trades")
    output = Path(output).resolve()
    if output.exists():
        raise ValueError("output directory already exists; refusing overwrite")
    output.parent.mkdir(parents=True, exist_ok=True)
    if any((p / ".git").exists() for p in [output.parent, *output.parent.parents]):
        raise ValueError("corpora/results must be outside Git checkouts")
    settings = dict(seed=seed, trades=trades, portfolios=portfolios, asof=asof.isoformat(),
                    rate=rate, mode=mode, samples=samples)
    corpus_id = "synthetic-" + hashlib.sha256(encoded(settings)).hexdigest()[:24]
    staging = Path(tempfile.mkdtemp(prefix=".ri14-", dir=output.parent))
    manifest = {"version": 1, "synthetic": True, "provenance": "seeded-local-fiction; not market observations",
                "engine_revision": ENGINE, "request_schema": "MarketPortfolioRequestSchema@2df78cb",
                "generator_version": 1, "corpus_id": corpus_id, "settings": settings,
                "cube_bytes_per_portfolio_lower_bound": cube,
                "financial_validation": False, "expected_refused": 0, "files": []}
    total = 0
    rng = random.Random(seed)
    try:
        for p in range(portfolios):
            pid = f"{corpus_id}-p{p:04d}"
            filename = f"portfolio-{p:04d}.json"
            stats = {"bill": 0, "note": 0, "long": 0, "short": 0, "net_face_usd": 0,
                     "gross_face_usd": 0, "coupon_periods": 0}
            path = staging / filename
            with path.open("xb") as f:
                def emit(data):
                    nonlocal total
                    total += len(data)
                    if total > max_bytes:
                        raise ValueError("corpus request bytes exceed limit")
                    f.write(data)
                emit(encoded(header(asof, rate, samples, seed))[:-1] + b',"trades":[')
                for i in range(trades):
                    t = trade(asof, rng, mode, f"{pid}-t{i:06d}", i)
                    stats["note" if t["coupon_schedule"] else "bill"] += 1
                    stats["long" if t["face_amount"] > 0 else "short"] += 1
                    stats["net_face_usd"] += t["face_amount"]
                    stats["gross_face_usd"] += abs(t["face_amount"])
                    stats["coupon_periods"] += len(t["coupon_schedule"])
                    emit((b"," if i else b"") + encoded(t))
                emit(b"]}\n")
            manifest["files"].append({"path": filename, "sha256": digest(path), "bytes": path.stat().st_size,
                                      "portfolio_id": pid, "submission_id": pid,
                                      "expected_supported": trades, "economics": stats})
        manifest["request_bytes"] = total
        data = encoded(manifest) + b"\n"
        if total + len(data) > max_bytes:
            raise ValueError("corpus including manifest exceeds byte limit")
        (staging / "manifest.json").write_bytes(data)
        staging.rename(output)
        return manifest
    except BaseException:
        shutil.rmtree(staging)
        raise


def load_request(path, limit=8 * 1024 * 1024):
    """Bounded structural validation; engine-schema validation is a separate optional proof."""
    path = Path(path)
    with path.open("rb") as f:
        raw = f.read(limit + 1)
    if len(raw) > limit:
        raise ValueError("validation/client limit is 8 MiB per request")
    body = json.loads(raw, parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
    validate(body)
    return body


def validate(body):
    """The intentionally narrow generated profile, not a replacement for engine validation."""
    allowed = {"schema_version", "market", "base_currency", "scenario_risk", "compute_greeks", "trades", "simulation"}
    if set(body) - allowed or body.get("schema_version") != "2" or body.get("base_currency") != "USD":
        raise ValueError("unsupported request profile")
    asof = date.fromisoformat(body["market"]["asof"])
    if set(body["market"]) != {"asof", "currencies"} or set(body["market"]["currencies"]) != {"USD"}:
        raise ValueError("USD-only synthetic market required")
    c = body["market"]["currencies"]["USD"]["discount_curve"]
    if c["times"] != [0.0, 1.0, 5.0, 10.0] or len(c["rates"]) != 4 or len(set(c["rates"])) != 1:
        raise ValueError("flat synthetic curve profile required")
    if not all(math.isfinite(r) and -0.05 <= r <= 0.20 for r in c["rates"]):
        raise ValueError("invalid curve rate")
    if body.get("compute_greeks") is not False or type(body.get("scenario_risk")) is not bool:
        raise ValueError("unsupported computation profile")
    trades = body["trades"]
    if not 1 <= len(trades) <= MAX_TRADES:
        raise ValueError("trade count outside bounds")
    ids = set()
    fields = {"trade_type", "trade_id", "currency", "face_amount", "maturity_date", "redemption_fraction",
              "coupon_rate", "coupon_schedule", "accrual_day_count"}
    for t in trades:
        if set(t) != fields or t["trade_type"] != "bond" or t["currency"] != "USD":
            raise ValueError("unsupported instrument/fields/currency")
        identity = t["trade_id"]
        if not isinstance(identity, str) or not identity or identity in ids:
            raise ValueError("missing/duplicate trade identity")
        ids.add(identity)
        if not math.isfinite(t["face_amount"]) or t["face_amount"] == 0:
            raise ValueError("finite nonzero signed face required")
        maturity = date.fromisoformat(t["maturity_date"])
        if maturity <= asof or t["redemption_fraction"] != 1 or t["accrual_day_count"] != "ACT/ACT (ICMA)":
            raise ValueError("unsupported maturity/redemption/day count")
        rate = t["coupon_rate"]
        if not math.isfinite(rate) or not 0 <= rate <= 0.10:
            raise ValueError("invalid coupon rate")
        schedule = t["coupon_schedule"]
        if bool(schedule) != bool(rate):
            raise ValueError("coupon/schedule contradiction")
        prior = None
        for period in schedule:
            if set(period) != {"start_date", "end_date", "payment_date"}:
                raise ValueError("unexpected coupon fields")
            start, end, payment = (date.fromisoformat(period[k]) for k in ("start_date", "end_date", "payment_date"))
            if end <= start or payment != end or end != add_months(start, 6) or (prior and start != prior):
                raise ValueError("regular contiguous semiannual unadjusted schedule required")
            prior = end
        if schedule and (prior != maturity or not date.fromisoformat(schedule[0]["start_date"]) <= asof < date.fromisoformat(schedule[0]["end_date"])):
            raise ValueError("schedule must cover valuation and end at maturity")
    simulation = body.get("simulation")
    if body["scenario_risk"]:
        if not simulation or not 1 <= simulation["samples"] <= 128 or simulation["base_currency"] != "USD":
            raise ValueError("bounded USD simulation required")
        dates = [date.fromisoformat(d) for d in simulation["dates"]]
        if len(dates) != 3 or dates != sorted(set(dates)) or dates[0] <= asof:
            raise ValueError("three increasing future scenario dates required")
        seed = simulation.get("seed")
        if type(seed) is not int or not 0 <= seed <= 2**32 - 1 or type(simulation["samples"]) is not int:
            raise ValueError("invalid simulation seed/sample type")
        expected = header(asof, c["rates"][0], simulation["samples"], seed)["simulation"]
        if simulation != expected:
            raise ValueError("unsupported simulation profile")
        if simulation["samples"] * 4 * len(trades) * 8 > MAX_CUBE:
            raise ValueError("cube lower bound exceeds budget")
    elif simulation is not None:
        raise ValueError("base-NPV profile has no simulation")


def verify_manifest(directory):
    directory = Path(directory)
    with (directory / "manifest.json").open("rb") as f:
        data = f.read(1024 * 1024 + 1)
    if len(data) > 1024 * 1024:
        raise ValueError("manifest exceeds 1 MiB")
    m = json.loads(data)
    if m["synthetic"] is not True or m["engine_revision"] != ENGINE or not m["files"]:
        raise ValueError("invalid provenance/engine identity")
    settings = m["settings"]
    expected_id = "synthetic-" + hashlib.sha256(encoded(settings)).hexdigest()[:24]
    if m["corpus_id"] != expected_id or len(m["files"]) != settings["portfolios"] or not 1 <= len(m["files"]) <= 1000:
        raise ValueError("corpus identity/portfolio count mismatch")
    paths = set()
    total = 0
    for index, item in enumerate(m["files"]):
        name = item["path"]
        if Path(name).name != name or name in paths:
            raise ValueError("unsafe/duplicate manifest path")
        paths.add(name)
        pid = f"{expected_id}-p{index:04d}"
        if item["portfolio_id"] != pid or item["submission_id"] != pid or item["expected_supported"] != settings["trades"]:
            raise ValueError("manifest identity/coverage mismatch")
        p = directory / name
        if p.is_symlink() or p.stat().st_size != item["bytes"] or digest(p) != item["sha256"]:
            raise ValueError("request checksum/size mismatch")
        total += p.stat().st_size
    if total != m["request_bytes"] or total + len(data) > MAX_BYTES:
        raise ValueError("manifest total bytes mismatch/budget exceeded")
    return m


def verify_entry(body, item):
    ts = body["trades"]
    if len(ts) != item["expected_supported"]:
        raise ValueError("manifest coverage mismatch")
    expected = {"bill": sum(not t["coupon_schedule"] for t in ts),
                "note": sum(bool(t["coupon_schedule"]) for t in ts),
                "long": sum(t["face_amount"] > 0 for t in ts), "short": sum(t["face_amount"] < 0 for t in ts),
                "net_face_usd": sum(t["face_amount"] for t in ts), "gross_face_usd": sum(abs(t["face_amount"]) for t in ts),
                "coupon_periods": sum(len(t["coupon_schedule"]) for t in ts)}
    if expected != item["economics"]:
        raise ValueError("manifest economics mismatch")
    if any(t["trade_id"] != f'{item["portfolio_id"]}-t{i:06d}' for i, t in enumerate(ts)):
        raise ValueError("trade identity does not match portfolio")
