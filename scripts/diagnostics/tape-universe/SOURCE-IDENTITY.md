# Offline source identity loss

`source_identity.py` observes CT/CQ reader tuples and current output-symbol projection. It does not define what a root/suffix means, map share classes, change ingestion, or inspect stored data. Fixtures are explicitly invented. The locally reviewed universe diagnostic commit is a dependency for private bounded file handling; neither candidate is self-integrated.

```bash
python3 scripts/diagnostics/tape-universe/source_identity.py \
  --kind CT \
  --input scripts/diagnostics/tape-universe/fixtures/identity-CT.csv \
  --reader-python /path/to/existing/cached-duckdb/python
```

Use `--kind CQ` with `identity-CQ.csv` for the quote proof. Supply an existing interpreter with DuckDB; no package/extension installation occurs. If the dependency is absent, proof is unavailable and exit2. The CLI never imports either ingester, invokes ingest/main, configures GCS, runs COPY, or writes Parquet. Only local regular-file snapshots and in-memory SELECTs are used. Real-data inspection is outside this delivery; keep any later authorized input/report private.

The source owner is YU07 (`tick-store/ingest_taq_trades.py` and `ingest_taq_quotes.py`), with no later override. Their exact hashes are pinned. Python AST parsing isolates the current SQL literal/interpolation structure from `ingest` as data. Only the read_csv/header/auto_detect, SELECT and WHERE inside the COPY wrapper are retained; local file parameters use `?`. Unknown source/hash/shape refuses. No hand-coded root-to-symbol formula substitutes for the ingester expression.

The current full SELECT is retained as an inner query and only identity-bearing columns are materialized into Python. This preserves current reader, filter and symbol expressions while making no price/timestamp conversion or financial claim. Fetching unused TIMESTAMPTZ values would require `pytz` in the cached environment; narrowing materialization avoids installing it. A separate annotated query adds reader row ordinals/root/suffix to the same inner projection/filter. Its eligibility IDs, count and output-symbol multiset must agree with the actual current SELECT. Query text/hashes, inferred raw/projection and identity types, input/source hashes and cached DuckDB version/binary hash are recorded.

CT eligibility requires non-null root/date/time/price; CQ requires root/date/time only. Other conditions/correction fields are not interpreted by this diagnostic. Zero price or blank bid/ask is not financial eligibility: the tool reports only whether the current WHERE admits the supplied row. Schema/type incompatibility is the actual reader/binder's refusal, not an invented invalid-date filter.

`unfilteredReader` reports every reader-observed tuple and excluded row ordinal, not raw lexical vendor identity. `eligibleProjection` reports the admitted observations, distinct typed root/suffix tuples, repeated observations of one tuple, output groups and lost distinctions when multiple distinct tuples share one output symbol. Row ordinals are logical reader records, one-based after the header, not trade IDs or physical line numbers. Repeats alone are not collisions or duplicate trades. Different roots and exact case variants remain separate where the actual reader distinguishes them.

CSV null/type inference is part of the observation: unquoted empty and quoted-empty suffix fields in these fixtures both read as SQL NULL, whereas literal `NULL` remains a string. Exact lower-case suffix `a` differs from `A`. The tool preserves these observed values and types; it does not infer vendor null conventions or canonical tickers. Input header uniqueness and explicit SYM_ROOT/SYM_SUFFIX are stricter diagnostic preconditions; the production ingester might otherwise ignore a missing suffix column. Missing suffix, declaration-only/incompatible/empty/zero-eligible/over-budget input cannot be successful empty proof. Unfiltered population counts must not be described as ingested rows.

Outputs use the existing private exclusive writer: 0600, outside Git, no overwrite. Stdout has status/counts/path only. No timestamps or snapshot filenames occur in complete report data. CLI budgets cap raw input at128MiB, observations at10,000 by default and report at4MiB, with20-second child timeout. DuckDB uses128MiB memory setting, one thread, disabled extension auto-install/load and disabled disk spill. These are local diagnostic safety settings, not a total RSS bound or production sizing recommendation; oversized data refuses without a truncated verdict.

Synthetic inventory: both fixtures have16 reader observations/13 distinct tuples. CT admits12 observations/9 distinct tuples; CQ admits13/10. Both lose3 tuple distinctions under current output symbol: MERGE has A/B/a, EMPTY has SQL NULL/literal NULL. SAME repeats one tuple and is not a collapse; merge is a separate exact-case root. These are fixture counts, not any cloud corpus or security universe observation. `identity-inventory.json` is an explicit independent expected inventory.

```bash
IDENTITY_READER_PYTHON=/path/to/existing/cached-duckdb/python \
  python3 scripts/diagnostics/tape-universe/test_source_identity.py
```

For generated ingester text use `--tick-store-root <private-generated>/code/target-generated/tick-store`; test acceptance accepts `TEST_TICK_STORE_ROOT` similarly. Source/hash/query parity does not establish live ingestion, partition contents, licensing, real share-class mapping, pricing or financial correctness. Deliberate omitted-suffix/wrong-projection variants exist only in disposable negative-control copies, are expected to fail acceptance, and are never called production.
