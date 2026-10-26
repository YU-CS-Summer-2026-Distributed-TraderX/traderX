"""Explicit local transport profiles; legacy EOD contracts are separate."""
LEGACY_ENGINE = "2df78cb08f782010f61acffc07d78030580e7639"
# Alex's upstream, unmodified: POST /runs + GET /runs/{run_id} with idempotency_key/keep.
CURRENT_ENGINE = "f9cc4c16fa843859729a9fb0956dd8cdfd2fa09b"
CURRENT = "current-cpu-v2"
LEGACY = "legacy-2df78cb"
PROFILES = {LEGACY: LEGACY_ENGINE, CURRENT: CURRENT_ENGINE}
CONTRACT = "portfolio-runs-v1@" + CURRENT_ENGINE


def request_profile(body):
    return LEGACY if body.get("schema_version") == "2" else CURRENT


def trades(body):
    """The positions: `portfolio` in a run request, `trades` in the legacy body."""
    return body["trades"] if request_profile(body) == LEGACY else body["portfolio"]


def scenario(body):
    return body["scenario_risk"] if request_profile(body) == LEGACY else "exposure" in body["analytics"]
