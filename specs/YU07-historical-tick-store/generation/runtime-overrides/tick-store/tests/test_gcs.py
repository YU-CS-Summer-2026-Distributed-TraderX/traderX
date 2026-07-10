import os
import sys

import duckdb
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import gcs  # noqa: E402


def test_is_gcs_path():
    assert gcs.is_gcs_path("gs://bucket/path")
    assert not gcs.is_gcs_path("/data/ticks")
    assert not gcs.is_gcs_path("./local/dir")


def test_configure_gcs_raises_clearly_without_env_vars(monkeypatch):
    monkeypatch.delenv("GCS_HMAC_KEY_ID", raising=False)
    monkeypatch.delenv("GCS_HMAC_SECRET_ACCESS_KEY", raising=False)
    con = duckdb.connect()
    with pytest.raises(RuntimeError, match="GCS_HMAC_KEY_ID"):
        gcs.configure_gcs(con)


def test_configure_gcs_registers_secret_with_env_vars(monkeypatch):
    monkeypatch.setenv("GCS_HMAC_KEY_ID", "dummy-key-id")
    monkeypatch.setenv("GCS_HMAC_SECRET_ACCESS_KEY", "dummy-secret")
    con = duckdb.connect()
    gcs.configure_gcs(con)
    names = [row[0] for row in con.execute("SELECT name FROM duckdb_secrets()").fetchall()]
    assert gcs.GCS_SECRET_NAME in names


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
