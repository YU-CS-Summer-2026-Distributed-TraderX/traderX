# TAQ catalog specification

Status: implementation and private execution accepted 2026-10-14; coordinator review and separate integration approval pending.

FR-TC01: Admit exactly 132 frozen source identities only after preservation finishes and every nonempty
commit validates source generation, manifest/image identity, original schema, row counts and referenced
GCS object generations, size, CRC32C and SHA256 metadata. Exclude uncommitted/superseded attempts.
FR-TC02: Compact across small input part boundaries into larger Parquet files with readable type/date/
archive/source-generation paths. Keep original archive and ZIP-member identity, including overlapping
daily and monthly sources. Do not combine quote/trade schemas or deduplicate records.
FR-TC03: Preserve every original column, text value, empty value, duplicate and all provenance fields.
Invalid/missing dates belong in an explicit reported partition; never drop or silently normalize them.
FR-TC04: Reconcile exact input/output populations per source/member/date, including duplicate
multiplicity and provenance, with independent Parquet readback. Empty populations, altered values,
missing/duplicate records or incompatible schemas fail publication.
FR-TC05: Publish create-only source completion records last. Build catalogs only from verified output.
FR-TC06: Provide JSON and Parquet catalogs mapping archive name/type/source generation/identity,
dates, schema, rows, verified file URIs/generations/checksums and overlap relationships.
FR-TC07: Provide a browsable searchable metadata view and exercised private DuckDB data query by
date, SYM_ROOT, SYM_SUFFIX and explicitly selected archive. Metadata search alone is insufficient.
FR-TC08: Keep raw archives and preservation output available. Query defaults must not silently
combine overlapping daily/monthly data. No raw data or credentials enter public/tracked artifacts.
NFR-TC01: Initially measure 128–256 MiB compressed output targets; bounded memory, row groups and
scratch. Report actual file-size distribution and tail files; do not claim sizing optimal without evidence.
NFR-TC02: Use scoped existing private GCS/IAM and a separately admitted immutable compactor image.
Pilot first, at most 32 workers total, exact submission reconciliation, no automatic duplicate jobs.
NFR-TC03: Verify terminal-job worker/group/boot/scratch cleanup with exact job ID/UID ownership.
NFR-TC04: Execution requires renewed direct approval; calendar date or preserved older authorization
must not trigger implementation or launch while this task is on hold.

SC-TC01: Synthetic controls reject altered fields, missing/duplicate rows, schema drift and wrong identity;
cover invalid dates, tiny tails, retries/interruption and same-date overlapping source archives.
SC-TC02: Real quote and monthly-trades pilot demonstrates independent all-field/provenance equivalence,
measured file sizes and query results before full compacted publication.
SC-TC03: Catalog reconciles all 132 sources and source/date counts; actual date/symbol/archive query
returns checked real rows privately and refuses implicit ambiguous overlap selection.
SC-TC04: Incomplete attempts stay invisible; final successful publication has verified resource cleanup.
