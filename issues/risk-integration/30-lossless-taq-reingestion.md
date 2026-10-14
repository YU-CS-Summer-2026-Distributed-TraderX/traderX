# RI30 — Lossless TAQ preservation and private catalog

Updated2026-10-14. Status: preservation and compacted catalog milestones done and approved source integrated at898f5150; broader replay/financial/application use remains open.

[Preservation milestone](resolved/ri30-taq-preservation.md) retains all132sourcearchives and originalstrings/emptyvalues/duplicates/provenance,240506339565rows in508831parts. [Catalog milestone](resolved/ri30-taq-catalog.md) publishes the same population in16498files with readable date/archive/member paths, privateJSON/Parquet/HTMLcatalogs and exercised date/root/suffix/explicitarchive rowqueries. Daily/monthly overlaps remain separate. Filecount is30.8timeslower; compressedbytesincreased20.7percent to3348141897106. No storage saving, generalfinancialvalidation or liveapplication tape claim.

Human-approvedcandidatebec674a9 andscopedpreservationdependencies integrated into canonical. Independent58source tests/root/component/privategenerationpass; metadata/query/custody evidence reviewed. Source/preservation/pilot/attemptGCSobjects remainimmutable; all completedjobresources removed peracceptedreceipts, heartbeatremainspaused. No newcloudwork/push/data deletion atintegration. Remaining: explicitapplication replay/normalization/share-class/correction/timestamp semantics andconnected engine use. Raw/vendor/private data neverpublic.

--- Original October11 planning record, superseded for authorization/completion status ---

# RI30 — Lossless February–March TAQ reingestion

Status: deferred by user, 2026-10-11. Coordinator owns planning; no conversion run authorized now.

The CompSci demo has no mounted historical tape. The migrated source/data buckets retain historical February–March data, but loading the old reduced extract does not fix its dropped-column defects. The user requests a later DuckDB-to-Parquet reingestion resembling YU07, preserving every source column. Existing [share-class corruption issue](../open/tick-store-drops-taq-sym-suffix-and-merges-share-classes.md) remains open and is a required input to this task.

Before implementation, inventory the private raw files, formats, source dates, schemas, sizes and checksums, and settle the raw SYM_ROOT/SYM_SUFFIX question using actual raw samples. Keep the original source values and all columns, including symbol suffixes, trade conditions/corrections, timestamps, quote-side fields, venue/sequence and any fields not currently used by the application. Record normalization separately from lossless storage; never silently coerce, drop, deduplicate or merge records. Preserve source-file/row provenance and malformed-row counts.

Acceptance: full raw-to-Parquet column and type coverage, attributable input/output row reconciliation, lossless representative round trips for every file/schema family, independent share-class checks (BRK.A/B, GOOG/GOOGL and other suffix forms), source checksums, explicit correction/condition semantics, and private authorized storage. Derive replay/resample artifacts separately from retained full-column data. Mount/load a validated tape only in the explicitly approved later rollout; demonstrate provenance and timestamps without claiming current live market prices. No cleanup of old/raw/retained data or public data publication is included.

Dependencies: source inventory, licensed educational data access, measured temporary disk/storage capacity, exact destination/project approval, bounded implementation and live proof plan. Follow the user-approved task lifecycle. No current worker or automation assigned.
