# Local missed-event recovery

RI06 O1, 2026-09-24. Operator-triggered managed-run catch-up from retained Aeron history. No retained installation has been activated. See the [component specification](../../specs/YU18-risk-integration/components/event-recovery/spec.md).

## Why replay is required

The actual baseline stopped the Spring consumer, matched trades, and restarted against the same disposable MariaDB. SQL retained four of six expected trade legs, four of six orders, and buy quantity 40 instead of 60. Core NATS does not replay missed publication. A durable subscription alone would not prove that every engine event reached the publisher.

Recovery replays the authoritative archive through the current production serializers. It requires the archive from genesis, contiguous recording coverage, the descriptor bound to the selected SQL scope, and matching live/replayed applied sequence and trade count. A changing boundary, absent history or a capacity limit is a refusal, never partial success. Replay explicitly retains callback failures because Aeron Image.poll otherwise reports them to its error handler and can return normally; the capacity-refusal regression reproduced and corrected that behavior. It works at a sufficiently quiet ACTIVE boundary or for the selected DRAINING scope at its frozen boundary. It never freezes, reopens, selects, seals, or activates a run.

## Install and invoke

Generate YU18 normally. With the ordinary controlled schema rollout, apply **only** the additive generated `postgres-database-replacement/mariadb-migrations/ri06-event-recovery.sql` after `ri06.sql`. It is rerunnable and contains no deletes. The existing fresh initializer and ConfigMap are not modified by O1: explicitly apply this recovery migration on fresh and retained installations before invoking catch-up. Do not run the destructive inherited initializer against retained history.

Use the actual local trade-processor endpoint directly. The console intentionally blocks operator recovery routes. Supply the configured risk-control token and a nonempty operator header through your normal private credential mechanism, not command history or evidence logs:

```http
POST /v2/projection-recovery/catch-up
Content-Type: application/json
X-Risk-Control-Token: <configured privately>
X-Risk-Operator: <operator identity>

{"projectionScope":"example-scope","endpoint":"http://127.0.0.1:18081"}
```

`endpoint` is the existing local gateway origin, not a payload download URL. Only loopback HTTP origins without credentials/query/fragment/path are accepted; redirects are refused. The gateway proxies the additive `/recon/recovery-events` route using existing reconciliation authorization. Existing projection readers, run transitions, and scoped notification payloads are unchanged. The approved additive position-service `GET /v2/projections/active` returns exactly `{"projectionScope":"<scope>"}` from the current registered `projection_active` pointer in one SQL statement; it never infers selection from phases. Missing or dangling pointers return503. Existing `GET /v2/projections` keeps its list shape. Unknown scoped orders now return404 consistently with positions/trades; registered empty scopes still return200 with an empty list. The service accepts no uploaded event history.

The response contains `projectionScope`, `completeSequence`, `witnessHash`, and `eventCount`. `projection_recovery` stores this complete-prefix witness and descriptor hash. The existing `projection_runs.checkpoint_seq` remains a maximum observed sequence and must not be treated as proof of complete delivery. Recovery always rechecks full history; it does not skip events using either checkpoint.

## Transaction and conflict behavior

Under the existing SQL write lock, recovery checks all source identities and ordering, every retained trade's immutable economics/provenance, every retained order's exact source version and fields, and all position/source-order keys. SQL history ahead of the witness refuses and can be retried after obtaining a newer boundary. Existing conflicting/orphan rows are never deleted, relabelled or overwritten to force a pass.

Before recovery writes, every retained position quantity must equal the signed quantity sum of the nonrejected trades already booked for the same scope/account/security. This sum is independent of arrival order: a missing transport event cannot explain a discrepancy between two projections committed together. An unexplained same-key balance refuses with `RECOVERY_RETAINED_POSITION_QUANTITY_CONFLICT`, preserving quantity, basis, trades, orders, registry, checkpoint and notifications. A position missing despite retained trades refuses with `RECOVERY_RETAINED_POSITION_MISSING`; restoring deleted/corrupt position rows is a separate reviewed repair, even when the retained net quantity is zero. If no trades were retained for a source key, an absent position or an economically empty zero-quantity/zero-basis row is recoverable; a nonzero initial balance or unattributed basis refuses. The gate proves net-quantity attribution, not independent validation of historical arrival-order cost basis; cost basis remains a derived source-order projection.

Every trade's source order must agree on account, security and side, and those fields must remain stable across all source order lifecycle versions. Inconsistent provenance refuses before any recovery writes.

Missing trades and final orders, derived positions replayed in source order, and the checkpoint commit in one transaction. This repairs interior gaps, where simply adding an older fill can give the wrong cost basis. Existing trade timestamps, settlement state and economics remain intact. Missing trades receive the existing booking service's processing/settlement metadata; this is not reconstruction of their original consumer processing time. Retained rejected bookings or newly rejected historical bond bookings refuse the complete transaction and require review. No missing metadata is silently replaced with synthetic financial data.

A failure at checkpoint insertion or before commit rolls back all projection changes and emits no notifications. A lost reply after commit is safely retried. New trade and final position notifications run only after the outer commit, using existing scoped subjects. Notifications remain best-effort; a process crash after SQL commit can lose UI notifications, and retries may emit duplicate final position snapshots. Scoped readers remain authoritative. Exactly-once notification delivery is not claimed.

## Bounds and limits

Recovery is explicit full replay, not automatic startup/background catch-up. Default `recovery.max-events=10000`, allowed 1–100000; peer response capped at 16 MiB. Archive `REGULATORY_MAX_RECORDS` and its existing replay deadline are additional independent limits. Retain the complete archive and configure `RECON_BLOTTER_CAPACITY` to enable reconciliation. A busy source may not supply a stable boundary and must be retried later; recovery does not pause business admission. Full replay allocates a bounded event collection and holds a SQL transaction during validation/application. No throughput, memory-usage measurement, or latency guarantee is implied by configured bounds.

Only explicitly registered epoch-v1 scopes are repaired. Unattributed legacy history, initial balances outside source trades, rejected booking-policy outcomes, missing/truncated archives, sealed scopes and unselected runs require separate review. The trusted local archive and immutable run-descriptor custody are the authority; descriptor hashes are not signatures against a malicious authorized operator. No external risk-engine, cloud, financial valuation or HA guarantee is added.

## Executable validation

The complete launcher is generated `recovery-identity/test-live-event-recovery.sh`: with Java 21 selected, run `RI06_EVENT_RECOVERY_PROOF=1 bash recovery-identity/test-live-event-recovery.sh` from the generated runtime root. It creates fresh evidence directories and executes both live methods plus SQL controls.

For individual runs, from generated `order-matcher`, compile with Java 21 and export the runtime classpath as shown in the existing recovery-identity live proof launcher. `EventRecoveryLiveIT` takes `RI06_MATCHER_CLASSPATH_FILE` and a fresh `RI06_LIVE_PROOF_DIR`. Run each live method with a separate empty output directory:

```sh
./gradlew integrationTest --tests '*EventRecoveryLiveIT.consumerOutageLosesEventsAndRestartDoesNotCatchUp' --no-daemon --max-workers=2
./gradlew integrationTest --tests '*EventRecoveryLiveIT.managedConsumerAndPublisherOutagesRecoverFromArchive' --no-daemon --max-workers=2
./gradlew integrationTest --tests '*EventRecoveryPersistenceIT' --no-daemon --max-workers=2
```

These commands run from generated `trade-processor`. `TESTCONTAINERS_RYUK_DISABLED=true` avoids an unrelated cleanup listener; fixtures close their own containers. Reserve named containers `traderx-o1-live-sql`, `traderx-o1-live-nats`, `traderx-o1-controls-sql`, and ports 24800–24890 / 25800–25890. Run sequentially, never against an existing rig. SQL is capped at 512 MiB, NATS at 128 MiB, child matcher/gateway heaps at 384 MiB each. The archive regression `RunIdentityConsensusTest` uses its existing separate 22800 range and disposable three-member fixture.

Validation status and immutable evidence references are recorded in component tasks and the delivery post. Source-authored tests execute on generated services; this is not a separate ungenerated service build or hosted CI run.

## SQL timestamp precision contract (2026-09-25)

Order event digests bind the complete archive event, including millisecond wire timestamps.
Retained order fields must equal the event's SQL representation: createdAt/updatedAt are cast
using the actual orderbook column datetime precision and the persistence connection's conversion
rules. The generated ConfigMap stores DATETIME seconds; a legitimate round-trip loses subsecond
bits. Fractional-precision installations retain those bits as declared. No timestamp is ignored:
a difference representable in the declared SQL type refuses, as do altered economics, provenance,
or event digest. Missing/unsupported precision metadata refuses. This requires no schema migration
and does not rewrite retained rows or recover precision that the schema never stored.

The initial unchanged combined live proof refused a legitimate retained order. A regression using
the actual generated ConfigMap order-table DDL isolates updatedAt 1005 -> 1000 ms; exact digest
comparison remains intact. Precision 0/3/6, repeated catch-up/checkpoint stability and genuine stored
timestamp/economic/provenance corruption are separate controls. Final execution results are recorded
in the combined acceptance report; this paragraph defines the contract, not a pass claim.
