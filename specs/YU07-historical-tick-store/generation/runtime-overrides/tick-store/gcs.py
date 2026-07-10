"""Shared GCS wiring for capture.py and ingest_taq_quotes.py.

DuckDB talks to GCS via its native `TYPE gcs` secret (HMAC key/secret, the same credential shape
as S3 — see research.md Decision 6). A `gs://` output path is opt-in: local/PVC paths behave
exactly as before, unchanged.
"""
import os

GCS_SECRET_NAME = "tickstore_gcs"


def is_gcs_path(path):
    return path.startswith("gs://")


def configure_gcs(con):
    """Registers the GCS HMAC credential from env vars. Call once per DuckDB connection before
    any COPY/read_parquet against a gs:// path. No-op-safe to call even if already configured."""
    key_id = os.environ.get("GCS_HMAC_KEY_ID")
    secret = os.environ.get("GCS_HMAC_SECRET_ACCESS_KEY")
    if not key_id or not secret:
        raise RuntimeError(
            "gs:// output path requires GCS_HMAC_KEY_ID and GCS_HMAC_SECRET_ACCESS_KEY env vars "
            "(see kubernetes-runtime tick-store-gcs-hmac Secret)"
        )
    con.execute("INSTALL httpfs")
    con.execute("LOAD httpfs")
    con.execute(
        f"CREATE OR REPLACE SECRET {GCS_SECRET_NAME} (TYPE gcs, KEY_ID ?, SECRET ?)",
        [key_id, secret],
    )
