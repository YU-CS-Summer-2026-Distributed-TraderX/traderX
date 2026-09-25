# Local run identity and projection migration

Updated 2026-09-24. Owner: Codex RI-06. This capability is implemented in the YU18 authoritative overrides; verification and review status are recorded in the recovery component tasks. It has not been activated on any retained rig. Earlier findings in [the recovery matrix](recovery-identity.md) describe the pre-migration baseline.

## Identity and compatibility

Each member's authoritative storage root contains the same immutable `run-identity.json` bytes. The descriptor's SHA-256 binds the epoch, ID scheme, storage lineage and projection scope. Startup reads this binding; it never creates an epoch. `RUN_DESCRIPTOR_PATH` selects the expected descriptor for members, gateways and the exporter. If `CLUSTER_EPOCH` is also configured, it must agree. Do not reformat a bound descriptor: identity includes its exact bytes.

A fresh descriptor is explicit operator input, for example:

```json
{"schema":"traderx.run.v1","epoch":"example_run","eventIdScheme":"epoch-v1","storageLineage":"example-storage","projectionScope":"example-scope","adoptionEvidenceSha256":null}
```

For epoch-v1, epoch matches `[a-z0-9_]{1,25}`. Epochs reserve one global order-ID namespace across all retained schemes, using the database collation; changing the scheme or letter case does not free an epoch. Trade legs use `e1-{epoch}-{tradeSeq}-{B|S}` (at most 50 characters); order IDs use `{epoch}-{orderRef}` (at most 36). Trades and source-order columns are 50 characters; the migration expands the former 32-character orderbook ID column to 50. Consumers retain strings rather than parsing a bare numeric trade ID. TCA/settlement references use the complete ID; order joins use the complete source-order ID. Kdb capture retains its existing epoch plus numeric reference columns and now obtains epoch from the descriptor; its final-order view groups by epoch/ref. Aggregate capture analytics are not a financial ledger or a recovery source. FIX ExecID remains a session protocol identifier, not the SQL trade ID.

Legacy-v0 keeps original trade IDs and original order prefix. The 25-character limit is not applied to legacy epochs. Unattributed SQL rows remain `legacy-unknown`; neither schema migration nor adoption rewrites their IDs, economics or metadata. Legacy publisher fallback behavior remains only for the unmanaged compatibility path. No descriptor plus no expected descriptor retains that older path; enabling managed operation requires explicit provisioning and consistent service configuration.

Snapshot format 12 persists descriptor hash, admission phase and frozen sequence. Existing formats 9–11 remain readable for legacy recovery; an epoch-v1 descriptor refuses an old unbound snapshot. All members in a managed cluster must run compatible binaries and the same descriptor. Normal restart retains storage, descriptor and counters; a genuinely new run needs different empty storage and a new explicit descriptor. Lost storage does not authorize reuse of an old identity.

## Offline provisioning and legacy adoption

Use the generated `recovery-identity/provision.py`, with storage writers stopped. `--offline` is an operator assertion, not an OS process detector. Fresh storage must be empty. The tool uses a lock, atomic no-replace installation and fsync; retrying the same bytes is safe, and replacing a binding is refused.

```bash
python3 recovery-identity/provision.py --offline --storage /absolute/fresh/member-0 \
  --descriptor /absolute/fresh-run.json
```

Run separately for every member using the same descriptor. Keep old storage and database backups. This command neither starts a member nor erases existing data.

For legacy adoption, provide a `legacy-v0` descriptor with `projectionScope: "legacy-unknown"` and `adoptionEvidenceSha256` equal to the SHA-256 of an explicit evidence file. The evidence has schema `traderx.legacy-evidence.v1`, the legacy epoch, `sourceConfiguration: {path, sha256}`, and either `storageFiles: {relativePath: sha256}` or `storageMembers: {memberKey: {relativePath: sha256}}`. The latter lets physically different replicas share one evidence/descriptor hash. Each member's complete nonempty retained file inventory must match its selected member key. Supply `--legacy-evidence /absolute/evidence.json --storage-member member-0` for that form. Symlinks and mismatched bytes are refused.

The archived source configuration must explicitly contain matching `tradePublisherEpoch`, `orderPublisherEpoch` and `eventIdScheme: "legacy-v0"`. Current environment values are never substituted as history. Evidence verifies supplied bytes, not the truth or authorship of the historical assertion. In particular, a legacy installation whose trade and order publishers used conflicting defaults 0/1 cannot be automatically adopted. Resolve and separately review its historical attribution before attempting migration; this tool refuses ambiguity.

## SQL barrier and rollout order

With admission and SQL consumers stopped, back up the retained database and apply only `postgres-database-replacement/mariadb-migrations/ri06.sql`. Do not apply `mariadb-init/initialSchema.sql` to retained data: that inherited initializer contains destructive setup statements. MariaDB DDL is not transactional; the additive migration can resume after interruption, and its completion marker is written last. Schema version 2 adds a unique index on `projection_runs.cluster_epoch` independent of scheme. If a previous registry already contains colliding epochs, the upgrade refuses before advancing the marker and preserves the rows; do not delete or relabel historical data to force it through. The operator must resolve that pre-existing attribution conflict under separate review. Deploy the scoped readers/consumers before admitting epoch-v1 events. Do not run old consumers against the new managed projection.

Preparation checks every retained registered epoch plus existing order/source-order prefixes before writing transition intent or contacting a peer. It compares using SQL collation, never Java case folding. The epoch, order ID and source-order ID columns must have the same collation; a mismatch refuses preparation/adoption and requires an explicit reviewed schema normalization before retry. Prefix reservation does not attribute or rewrite unknown legacy rows.

The schema adds the run registry, active-scope pointer, durable transitions, projection write lock and scoped reconciliation checkpoints. Positions are keyed by scope/account/security. Orders, trades and EOD position marks carry scope. Single and batch trades compare immutable economics and provenance before dedup; conflicts roll back without publishing. Transactional booking is serialized across consumers and publication happens after commit. This is a correctness mechanism, with no throughput claim. Managed order updates use consensus sequence plus output ordinal and reject equal-position conflicting payloads; trace IDs and processing times are not immutable economics.

Existing position/trade/order read routes select the active scope. Explicit history routes are `GET /v2/projections` and `/v2/projections/{scope}/accounts/{account}/positions|trades` on position-service, and the corresponding `/orders` route on trade-processor. Managed notifications use `/v2/projections/{scope}/accounts/{account}/trades|positions`; `legacy-unknown` retains `/accounts/{account}/trades|positions`. Managed clients must subscribe explicitly. The console and demo scripts have not been adapted or automatically switched.

## Durable transition

Keep old and fresh clusters on distinct local endpoints and storage. Start the adopted old run with its preserved descriptor. Start the fresh run with its provisioned descriptor: business admission is closed. Set matching `RISK_CONTROL_TOKEN` in the gateway and trade-processor, use the existing shared JWT secret for authenticated reconciliation, and set each managed gateway's `RUN_PROJECTION_CONTROL_URL` to the loopback trade-processor HTTP origin. The tool's optional `RUN_MIGRATION_AUTHORIZATION` supplies any enclosing deployment authorization. Never put credentials in command-line arguments or evidence logs.

Enable `RECON_BLOTTER_CAPACITY` on all members (its default is disabled), retain the complete Aeron archive, and size `RECON_FULL_HISTORY_MAX` and `REGULATORY_MAX_RECORDS` for the complete witness. Truncation is a refusal, not a partial success. Configure the gateway member health port/roster consistently; this local workflow assumes the members are reachable through that existing proxy.

The gateway's `/run/status` requires all configured members to agree on descriptor, scope, phase and frozen boundary. Operator `/run/control` requires the risk token, operator header and matching descriptor hash. A fresh activation additionally queries `/v2/projection-control/activation/{hash}` and requires the durable SQL selection witness. Internal Aeron/control transport is a trusted boundary; this is not authentication for arbitrary direct Aeron clients.

First register the evidenced legacy descriptor with the SQL registry:

```bash
python3 recovery-identity/transition.py adopt-legacy --controller http://127.0.0.1:18080 \
  --descriptor /absolute/legacy-run.json --old-endpoint http://127.0.0.1:18081
```

Then run or resume one immutable transition:

```bash
python3 recovery-identity/transition.py run --controller http://127.0.0.1:18080 \
  --transition-id example-transition --old-scope legacy-unknown \
  --descriptor /absolute/fresh-run.json \
  --old-endpoint http://127.0.0.1:18081 --new-endpoint http://127.0.0.1:18082
```

These are example ports, not a deployed configuration. Both CLI and Java peer transport accept only loopback HTTP origins and refuse redirects. The CLI never starts, deletes or repoints a rig. After adoption, later transitions can use the previous managed scope as `--old-scope`.

| SQL phase | Committed action and safe recovery |
|---|---|
| PREPARED | Persist immutable intent and fresh descriptor; declare fresh core, still closed. Retry declaration after lost reply. |
| FROZEN | Old core permanently freezes at a committed sequence; old SQL scope drains only eligible events. Retry freeze after lost reply. |
| VERIFIED | Replay the complete frozen archive with production serializers; compare all trade economics/provenance, final orders and signed position quantities. Seal old projection and persist hashed witness atomically. |
| SELECTED | Verify fresh core remains declared/closed and fresh SQL scope empty; atomically select fresh scope. Its gateway can now validate the SQL witness. |
| COMPLETE | Activate fresh core, confirm member agreement and record completion. A lost acknowledgment resumes the same activation. |

No frozen run can be reopened. Failures preserve durable phase and history; rerun the same command and bytes. Missing events, orphans, conflicting state, descriptor disagreement, truncated archive replay or an empty witness refuse verification. The proof intentionally requires nonempty trade/order history; an empty legacy source is not silently accepted. Normal endpoint reads may briefly observe the selected empty scope before activation; no fresh business event is admitted before SQL selection.

NATS publication remains best-effort. A consumer outage can leave missing projections (RI06 O1). This transition workflow detects that discrepancy and stays frozen. The separate [operator catch-up](event-recovery.md) can repair an explicitly managed selected ACTIVE or DRAINING scope from complete authoritative history; it does not promise automatic repair or durable NATS delivery. Initial balances outside the archive, unknown legacy history, and legacy publisher disagreement still require separately reviewed data recovery. Position verification checks signed quantities and immutable trade prices, not an independent financial valuation or cost-basis recomputation.

## Exports and proof boundaries

Managed cut headers bind descriptor hash, projection scope and scheme inside the cut SHA. Ready receipt v2 (`traderx.risk-extract.ready.v2`) adds platform identity and cut URI. The bridge requires `--run-descriptor`, verifies descriptor bytes, cut hash/header/stamp, CSV hashes and supplied epoch before custody binding or publication. Existing explicitly attributed receipts keep the compatibility path and retained directory epoch guard. GCS staging understands the v2 cut artifact, but no cloud execution is claimed. Existing immutable risk-result identity checks are unchanged.

Validation includes disposable MariaDB/JPA migration, concurrency, rollback and interrupted SQL transition tests; actual three-member local Aeron snapshot/tail/retained restart and frozen archive replay; receipt and provisioning tests; generated parity and affected regressions. The original SQL interruption tests use a fault-injected peer, and the three-member recovery proof uses real Aeron. The M2 correction adds the connected real HTTP/Aeron/NATS/SQL proof below. These are not financial validation, a latency campaign, arbitrary member-loss HA proof, or authorization to activate on retained storage. External risk-engine gaps and managed UI rollout remain open.

## Verified delivery evidence

Final isolated YU18 generation and byte parity covered 174 runtime files. The full matching-engine run discovered 574 tests: 568 passed and six existing cloud/benchmark/baseline assumptions skipped, with zero failures. All five allocation gate tasks passed under the checked-in test profile; no latency or cross-profile allocation claim is made. A subsequent focused run passed all ten identity tests including the added long-epoch legacy adoption compatibility case. Generated trade-processor unit tests passed 98/98, position-service 11/11, and real MariaDB integration 14/14. Source and generated receipt suites each passed 167/167; source and generated provisioning/transition suites each passed 15/15. The SQL final run initially exposed a fixture whose sequence exceeded its frozen boundary; corrected fixture plus reason-specific negative assertions passed without relaxing production checks.

The actual three-member witness had two snapshot trade legs, two retained-tail legs, four final trade legs, eight order updates and 18 replayed messages, with frozen sequence 14. Detailed logs, XML, baseline failures, parity and source ownership inventory are delivered in the coordinator's `review-evidence/ri06-migration-20260924` directory. The accepted gateway buffer correction (77f851b1) is separate and already integrated by the coordinator.

## Review corrections M1/M2

M1 was reproduced against real MariaDB: both exact and case-equivalent legacy epochs were persisted as fresh transition intents before the fix. The correction enforces the global order namespace above, with regression coverage for case/accent collation, retained unknown order prefixes and an interrupted upgrade containing a conflicting version-1 registry. Old IDs remain unchanged.

M2 connects real old/fresh **single-member** Aeron processes, their actual HTTP gateways, the production NATS publishers/subscribers, and the real Spring HTTP controller/JPA on disposable MariaDB. It preserves nonempty old trade/order/position history, executes all five durable stages, discards a committed freeze HTTP reply then retries, and checks first fresh order/trade IDs and isolated fresh positions. The extra gateway check refuses activation before SQL selection. This supplements the separate three-member snapshot/tail proof; it does not claim two three-member clusters or a retained deployment. The only mocked failure is the discarded HTTP reply; no run peer, gateway decision or projection service is faked.

Run explicitly from the generated tree with Java 21 and Docker configured:

```bash
bash recovery-identity/test-live-migration.sh
```

The launcher allocates a unique local proof directory, builds the actual matcher classpath and runs `RunMigrationLiveIT`. It reserves ports 24800–24900 and 25800–25900 and uniquely named `traderx-ri06-live-sql` / `traderx-ri06-live-nats` containers. Do not overlap another lease on those resources. The fixture closes child JVMs and containers; it keeps synthetic logs/storage in the printed proof directory. Ordinary integration runs skip this explicitly gated live fixture unless `RI06_MATCHER_CLASSPATH_FILE` is set. A skip is not acceptance evidence. Review-correction evidence is packaged separately from the original delivery.

Correction validation completed: MariaDB persistence/namespace suite 19/19; affected trade-processor unit suite 98/98; connected live test 1/1 through the delivered launcher, with zero skips. The live witness records two retained old trades/orders, two fresh trades/orders, unchanged old history, COMPLETE phase and successful lost-freeze-reply retry. The correction evidence directory is `review-evidence/ri06-migration-corrections-20260924`.

## ConfigMap placement correction C1

The initial integrated migration placed the RI-06 SQL after a dedented YAML section comment, outside either SQL literal. C1 moves that comment to immediately before the `900-migrations.sql` key; SQL statements are unchanged. The complete canonical RI-06 migration now belongs to both `001-initialSchema.sql` (fresh initialization) and `900-migrations.sql` (retained startup). Retained startup must never execute the destructive `001` initializer.

`GeneratedDatabaseManifestTest` parses the actual generated ConfigMap, verifies both keys contain the complete canonical migration exactly once, and checks the generated Deployment mounts `001`/`900` for fresh entrypoint initialization but only `900` for the retained schema-migrate initContainer. `GeneratedDatabaseSchemaIT` executes those parsed SQL payloads on disposable MariaDB: fresh `001` then repeated `900`, and a populated pre-RI06 schema upgraded with only repeated `900`. It compares every pre-existing column, preserves nonempty trades/positions/orders and verifies deliberately deleted retained demo rows are not re-seeded, scope keys and the global epoch index exist, and schema version is 2. This is generated SQL/manifest validation, not a Kubernetes deployment or retained-rig operation.

Combined recovery + managed UI acceptance (2026-09-25): see [reproducible local proof](managed-recovery-acceptance.md) for the corrected generated-schema runtime, real managed-to-managed transition, unchanged initial failure and bounded claims.
