# Recovery identity specification

Status: review needed. Owner: Codex RI-06. Dependencies: existing EOD bundle/coordinator contracts. The original receipt custody guard is extended by the explicitly authorized local run/projection migration below.

- FR-RI01: A validated receipt directory is bound to its caller-supplied epoch within retained inbox custody. Repeated or restarted imports must preserve the binding; conflicting epochs fail before bundle publication.
- FR-RI02: Validate receipt, artifact hashes, common cut and manifest before adopting identity. Bad input must not reserve an epoch. Source receipt bytes and original bundles remain unchanged.
- FR-RI03: Persist binding transactionally before publication. Interrupted publication retains identity; concurrent conflicting adopters cannot both succeed. Corrupt/unsupported lineage storage fails closed.
- FR-RI04: Fresh independently provisioned receipt directories may reuse numeric counters under different explicit epochs. Preserve historical jobs/results and reject cross-epoch result identity. Late and duplicate records retain original scope.
- NFR-RI01: Single-host local custody, private SQLite store, no distributed authority, financial validation, automatic reset or epoch creation. Existing legacy input/CLI formats remain accepted.
- NFR-RI02: First-use provenance is an operator assertion, not inferred from receipts. Separate inboxes, directory copies and removed binding databases are outside the guarantee. Document migration before use.

Acceptance: SC-RI01 same-directory relabel refusal; SC-RI02 restart/dedup; SC-RI03 distinct fresh directory with historical result preservation; SC-RI04 invalid and stale-boundary refusal; SC-RI05 before/after-commit interrupted binding/publication; SC-RI06 conflicting concurrent first imports; SC-RI07 corrupt/versioned/symlink store refusal; SC-RI08 cross-epoch result refusal. Executable tests live in the shared YU18 runtime override tests directory.


## Authorized migration extension (2026-09-24)

- FR-RI05: Legacy-v0 IDs remain stable through replay/restart. Explicit fresh epoch-v1 run descriptors bind epoch, scheme, storage and projection lineage. Ambiguous legacy adoption requires source evidence. Mismatches fail before admission/publication.
- FR-RI06: Same-key trade is a duplicate only when account, security, side, quantity, numeric price and source-order provenance match; processing status/timestamps/settlement metadata do not participate. Single and batch conflicts refuse without SQL/position/publication mutation. Identical in-batch repeats book once. Across consumers, a database transaction lock serializes dedup and position updates; publication follows successful commit. Global consumer lock is correctness-first; no performance claim.
- FR-RI07: Managed projection scope is part of position keys, reads, checkpoints and joins. Unknown legacy records stay explicitly unknown, never relabelled from current environment. Historical results remain addressable.
- FR-RI08: Explicit fresh transition records a frozen consensus boundary and consumer agreement; interrupted stages resume or fail closed. New admission requires completed verification. No implicit wipes or process-derived epochs.

SC-RI09: TradeIdentityPersistenceIT on disposable MariaDB tests same-epoch retry after service reconstruction, provenance conflict, batch rollback, within-batch duplicates/conflicts, outer rollback without publication, concurrent services and immutable-economics conflict versus mutable-state replay. Stage1 source owner is a deliberate YU18 override copied from YU16 TradeService; no ancestor mutation. PROJECTION_WRITE_LOCK migration is additive and seeded idempotently in source MariaDB initial SQL and ConfigMap initial/migration SQL. Missing schema refuses writes.

SC-RI10: Real MariaDB additive migration retains nonempty historical rows after interruption and rerun; same counter in a fresh scope books a distinct trade/position and publishes on a scoped topic. Late legacy traffic cannot mutate fresh positions.

SC-RI11: RunIdentityTest and RunIdentityConsensusTest exercise descriptor mismatch refusal, format-12 snapshot identity, real three-member snapshot/tail/restart, permanent freeze and nonempty full archive replay using production serializers. Gateway activation requires a matching selected SQL witness.

SC-RI12: Real MariaDB transitions with fault-injected peer acknowledgments reconstruct services and resume every durable phase; missing/conflicting projection evidence and premature activation are refused. Provisioning tests cover interrupted immutable installation and shared multi-member legacy evidence. CLI tests cover response loss and immutable intent.

SC-RI13: Managed v2 receipts verify the expected descriptor against the actual cut hash/header before receipt custody/publication; legacy receipts retain the explicit attribution path. Existing risk result identity remains mandatory.

See [run migration](../../../../docs/risk-integration/run-migration.md) for versioned interfaces and limitations. No automatic NATS gap repair, financial valuation, managed UI rollout or retained deployment is claimed.

FR-RI09 (M1): Because order IDs use epoch-ref for both schemes, reserve epoch globally across retained runs using the actual SQL collation. Reject registered or historical-prefix namespace reuse before preparing a fresh transition or freezing the old run. A version-1 schema with conflicts must refuse upgrade without rewriting rows. Missing attribution never permits namespace reuse.

SC-RI14 (M2): RunMigrationLiveIT connects real old/new HTTP gateways and Aeron runs, NATS, the Spring controller and MariaDB/JPA. Discard a committed freeze reply, retry, complete activation, verify first fresh IDs/isolated positions and byte-equivalent old SQL history. Premature gateway activation is refused. This explicit opt-in fixture has separate resource/port requirements documented in the runbook.
