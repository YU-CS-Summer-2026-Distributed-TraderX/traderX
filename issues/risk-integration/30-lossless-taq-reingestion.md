# RI30 — Lossless February–March TAQ reingestion

Status: deferred by user, 2026-10-11. Coordinator owns planning; no conversion run authorized now.

The CompSci demo has no mounted historical tape. The migrated source/data buckets retain historical February–March data, but loading the old reduced extract does not fix its dropped-column defects. The user requests a later DuckDB-to-Parquet reingestion resembling YU07, preserving every source column. Existing [share-class corruption issue](../open/tick-store-drops-taq-sym-suffix-and-merges-share-classes.md) remains open and is a required input to this task.

Before implementation, inventory the private raw files, formats, source dates, schemas, sizes and checksums, and settle the raw SYM_ROOT/SYM_SUFFIX question using actual raw samples. Keep the original source values and all columns, including symbol suffixes, trade conditions/corrections, timestamps, quote-side fields, venue/sequence and any fields not currently used by the application. Record normalization separately from lossless storage; never silently coerce, drop, deduplicate or merge records. Preserve source-file/row provenance and malformed-row counts.

Acceptance: full raw-to-Parquet column and type coverage, attributable input/output row reconciliation, lossless representative round trips for every file/schema family, independent share-class checks (BRK.A/B, GOOG/GOOGL and other suffix forms), source checksums, explicit correction/condition semantics, and private authorized storage. Derive replay/resample artifacts separately from retained full-column data. Mount/load a validated tape only in the explicitly approved later rollout; demonstrate provenance and timestamps without claiming current live market prices. No cleanup of old/raw/retained data or public data publication is included.

Dependencies: source inventory, licensed educational data access, measured temporary disk/storage capacity, exact destination/project approval, bounded implementation and live proof plan. Follow the user-approved task lifecycle. No current worker or automation assigned.
