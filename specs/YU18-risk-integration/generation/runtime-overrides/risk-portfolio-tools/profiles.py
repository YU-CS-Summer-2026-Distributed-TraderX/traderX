"""Explicit local transport profiles; legacy EOD contracts are separate."""
LEGACY_ENGINE = "2df78cb08f782010f61acffc07d78030580e7639"
CURRENT_ENGINE = "c3d7c6ae485213dbb24abd2724f9ae8cb85acbb8"
CURRENT = "current-cpu-v1"
LEGACY = "legacy-2df78cb"
PROFILES = {LEGACY: LEGACY_ENGINE, CURRENT: CURRENT_ENGINE}
CONTRACT = "portfolio-local-cpu-v2@" + CURRENT_ENGINE
CONTENT_DIGEST_ALGORITHM = "json-typed-decimal-sha256-v1"


def request_profile(body):
    return LEGACY if body.get("schema_version") == "2" else CURRENT
