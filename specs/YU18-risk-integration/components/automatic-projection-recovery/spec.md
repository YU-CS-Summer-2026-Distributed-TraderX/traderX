# Automatic projection recovery specification

Scope: managed epoch-v1 runs whose SQL scope is the selected ACTIVE or DRAINING scope. Unknown
legacy history, sealed/unselected scopes, unproven initial balances, conflicting immutable rows
and missing/trimmed archives remain refusals that need operator review.

## Source contract (member, additive `GET /recon/catchup-events?afterSeq=&maxEvents=`)

- FR-APR01: The upper boundary B is the member's applied consensus sequence captured before the
  replay; applied means committed. Trading continues; outputs after B are ignored.
- FR-APR02: A page holds the exact production wire bytes of every projection event with
  afterSeq < seq <= throughSeq, whole commands only. throughSeq also covers commands with zero
  outputs. A single command larger than the page refuses (`RECOVERY_COMMAND_EXCEEDS_PAGE`).
- FR-APR03: Coordinates are (descriptor hash, consensus sequence, output-chain digest), where
  d0 = 64 zeros and d' = SHA-256(d + "\n" + wire). The page carries the digest through afterSeq
  and through throughSeq. Replay is strict from genesis across all recording terms
  (existing checks: missing genesis, discontinuity, archive behind B all refuse).
- FR-APR04: afterSeq == B answers `idle` without replay; afterSeq > B refuses.

## Consumer contract (trade-processor)

- FR-APR05 Cursor: `projection_catchup_cursor` per scope stores descriptor hash, protocol version,
  verified-through sequence, chain digest, event count, last observed source boundary, state,
  blocked reason and timestamps. A maximum observed sequence (`projection_runs.checkpoint_seq`)
  is never completeness.
- FR-APR06 Atomic page: under the existing projection write lock and fenced lease row, one
  transaction verifies the chain digest, identity, scope, ordering and writability; refuses
  orphans (retained trades positioned in the window but absent from the source), immutable trade
  conflicts, rejected bookings, trade/order provenance conflicts, and unexplained balances of
  EVERY key the page touches (inserted or already retained trades); verifies every retained order row positioned in the window against the
  SQL representation of its source event (reviewed timestamp-precision comparator) before
  writing; inserts missing trades; re-derives quantity and average cost of every page key from
  ALL retained trades in authoritative order (consensus sequence, engine trade sequence), so an
  arrival-order basis is never certified and newer live trades are kept (R1);
  persists order events without rewinding newer versions; and advances the cursor
  (compare-and-set on the old boundary). Notifications run after commit only; a key's final
  position is published when it differs from its PRE-page value, including when trade insertion
  alone already produced it (R2). Unchanged keys publish nothing; rollback publishes nothing.
- FR-APR07 Bootstrap: a new cursor always starts at genesis and re-verifies every retained row.
  An explicit-recovery checkpoint is never a starting point, only an identity cross-check;
  a different descriptor or protocol refuses.
- FR-APR08 Ownership: one lease row; takeover by a new owner or after expiry increments the fence.
  Every write transaction re-reads the lease row FOR UPDATE and requires owner, fence and
  unexpired lease, holding that row lock to commit, so a replaced owner cannot commit and a
  takeover cannot interleave with an in-flight page.
- FR-APR09 Run barriers: the worker never freezes, seals, selects, activates or reopens a run.
  If the selected scope/phase/descriptor changes, the page is superseded and nothing is written.
  A DRAINING scope is caught up only to its frozen sequence.
- FR-APR10 Scheduling: triggers are startup, a NATS subscriber reconnect edge, and a periodic
  interval independent of NATS. Pages are bounded; cycles are bounded; failures back off
  exponentially to a cap; shutdown cancels and releases the lease. No readiness/liveness probe
  depends on this worker.
- FR-APR11 Status: read-only `GET /v2/projection-completeness` separates point-in-time
  completeness (verified-through, last successful verification, observed source boundary,
  CATCHING_UP/CURRENT/RETRYING/BLOCKED + reason) from worker liveness. No operator action.
- NFR-APR01: additive, rerunnable migration `ri06-automatic-recovery.sql`; disabled by default;
  enabled only by the disposable `auto-recovery-local` profile.
- NFR-APR02: no throughput/latency/HA guarantee; each non-idle page costs a genesis-to-B shadow
  replay on the member.

Acceptance: see [tasks](tasks.md) for the case-by-case mapping and validation level of each claim.
