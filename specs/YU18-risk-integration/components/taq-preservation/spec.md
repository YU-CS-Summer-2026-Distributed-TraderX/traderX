# TAQ preservation specification

FR-TP01: Preserve every original CSV header and field value as UTF-8 strings, with explicit
source identity, archive member and logical record number. Preserve empty strings and timestamp
precision. Refuse duplicate/reserved headers; do not rename them silently.
FR-TP02: Keep archives as source authority; verify source object generation/size/CRC32C and compute
SHA256. Read every selected CSV member to EOF and validate ZIP CRC. Fail on unsupported members.
FR-TP03: Reconcile nonzero input/output record counts and independently read back every Parquet
part, verifying field digest and all provenance values before upload/publication.
FR-TP04: Publish one create-only completion per source in a frozen input run. Only committed
manifests expose data. Retry and concurrent attempts cannot produce duplicate published records.
FR-TP05: Record failures without completion, including observed malformed-row counts. Never skip,
normalize, merge, correct or deduplicate source records. Independent source archives can overlap.
NFR-TP01: Bound chunk rows/bytes, field size, DuckDB memory/threads, scratch admission, cloud task
runtime/concurrency/retries. Qualify limits as pilot defaults until measured.
NFR-TP02: Keep real data, inventories and results private and outside Git. Dedicated cloud
identity reads sources and creates/reads outputs; no source delete or mutation.

SC-TP01: Synthetic preservation cases cover suffixes, nanoseconds, empty values, decimal strings,
unknown columns, embedded delimiters/newlines and record ordering.
SC-TP02: Corrupt ZIP, malformed CSV, altered Parquet, missing/empty manifest and wrong checksum fail.
SC-TP03: Retry after commit is a no-op; interruption before commit stays invisible; competing
commit uses and verifies the winner. Local fake-storage tests do not establish real GCS behavior.
SC-TP04: Full real pilot must establish all-member/schema inventory, independent row reconciliation,
share-class cases from raw data, real GCS integrity/retry, and bounded resource/cost evidence.
