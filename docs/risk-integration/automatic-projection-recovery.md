# Automatic projection catch-up

RI06, 2026-09-25. Managed-run SQL projections catch up from the authoritative Aeron archive
without an operator call: on consumer startup, on a NATS subscriber reconnect, and on a periodic
cadence that does not depend on NATS. See the
[component specification](../../specs/YU18-risk-integration/components/automatic-projection-recovery/spec.md).
The explicit [operator catch-up](event-recovery.md) remains unchanged as the diagnostic/refusal path.
Nothing is enabled on any retained or GKE profile.

## How it works

1. One trade-processor instance holds the lease (`projection_catchup_lease`). Takeover by another
   instance, or after expiry, increments a fence.
2. The worker reads the selected scope. Only an ACTIVE or DRAINING epoch-v1 run is eligible.
3. It finds the configured loopback gateway whose `/run/status` serves that exact descriptor.
4. It asks for `GET /recon/catchup-events?afterSeq=<cursor>&maxEvents=<page>`. The member
   captures its applied (committed) sequence B, replays the archive strictly from genesis, and
   returns whole commands after the cursor up to B, as exact production wire bytes, with the
   output-chain digest through the cursor and through the page end. Trading is not paused.
5. One SQL transaction, under the projection write lock and the lease row lock: verify identity,
   chain digest and ordering; refuse orphans, immutable conflicts, rejected bookings, provenance
   conflicts, unexplained balances and retained order rows that differ from their source event;
   insert missing trades; re-derive every page key's position from every retained trade in
   authoritative order (newer live trades included), even when no trade was missing, so an
   arrival-order cost basis is corrected rather than certified; apply order versions without
   rewinding newer ones; advance the cursor by compare-and-set; append `projection_catchup_log`.
6. Notifications (scoped subjects, best-effort) are sent after commit only: recovered trades,
   and each page key's final position when it differs from its value before the page.

Average cost is a derived value. Equity average cost is independent of order except when a
position lands flat, so re-derivation changes it only in those histories; quantity is never
re-derived past an unexplained balance, which still refuses.

Live NATS delivery continues throughout. Its dedup, immutable-economics checks and order
(sequence, ordinal) monotonicity make overlap with pages safe. Page transactions briefly block
live consumers through the shared write lock; that is the backpressure, and page size bounds it.

## Enable (disposable environments only)

1. Stop SQL consumers, back up, then apply the additive, rerunnable
   `postgres-database-replacement/mariadb-migrations/ri06-automatic-recovery.sql` after `ri06.sql`
   and `ri06-event-recovery.sql`. It creates three tables and one lease row; no deletes, no
   changes to existing tables. Never run the destructive initializer against retained data.
2. Keep the complete archive from genesis for every managed run (no segment purge while this is
   enabled): a trimmed archive refuses rather than rebuilding from an incomplete source.
   `RECON_BLOTTER_CAPACITY` must be set so members serve `/recon/*`.
3. Start trade-processors with `--spring.profiles.active=auto-recovery-local` and
   `AUTO_RECOVERY_ENDPOINTS=<loopback gateway origins>`. Defaults in that profile: interval 2 s,
   lease 5 s, backoff cap 10 s, 50 events per page (library defaults when enabled otherwise:
   15 s, 30 s, 300 s, 500, 50 pages per cycle). These are local-proof settings, not sizing.
4. Watch `GET /v2/projection-completeness`. `verifiedThroughSeq` and
   `lastSuccessfulVerificationAt` are point-in-time completeness; the `worker` block is liveness
   only. Connection status, `projection_runs.checkpoint_seq` and existing recon counters do not
   imply completeness.

Rollout for any retained installation needs its own review: apply the migration, start one
instance with the profile, and let the first cursor re-verify the whole scope from genesis. Its
cost is a full shadow replay per page on the member serving it.

## Refusals (BLOCKED)

BLOCKED records the reason and writes nothing else. The worker re-checks at the backoff cadence
without mutation and never restarts the process. Reasons include `TRADE_ID_CONFLICT`,
`RECOVERY_ORPHAN_OR_AHEAD_TRADE`, `RECOVERY_RETAINED_POSITION_*`,
`RECOVERY_RETAINED_ORDER_ROW_CONFLICT`, `AUTO_RECOVERY_SOURCE_HISTORY_CHANGED`,
`AUTO_RECOVERY_CURSOR_IDENTITY_CONFLICT`, `AUTO_RECOVERY_CHECKPOINT_IDENTITY_CONFLICT`,
archive refusals from the member (`RECOVERY_ARCHIVE_GENESIS_MISSING`, discontinuity,
`RECOVERY_ARCHIVE_BEHIND_BOUNDARY`, `RECOVERY_CURSOR_AHEAD_OF_SOURCE`,
`RECOVERY_COMMAND_EXCEEDS_PAGE`) and `RUN_SCOPE_NOT_WRITABLE` beyond a frozen boundary.
No row is deleted, relabelled or overwritten to force convergence.

## Limits

- Each non-idle page replays the archive from genesis to B on the member (the shadow engine has
  no checkpoint). An idle check (cursor == applied) costs no replay.
- Each worker transaction sets `idle_transaction_timeout` to the lease TTL (seconds). A paused
  owner inside a page transaction is aborted by MariaDB after that long and rolled back, which
  releases the lease row and the global write lock; it cannot commit afterwards. Without this,
  one live run showed no takeover within 180 s and live booking on the other instance timing out.
  The pooled connection keeps that session value for later use by other writers.
- The worker verifies each retained row once, when its cursor passes it. Corruption introduced
  later below the verified cursor is caught by the explicit catch-up or transition VERIFY, not
  by this worker.
- Measured locally (single-member runs, one laptop, history up to ~1,000 consensus sequences,
  pages of at most 49 events): median 135-200 ms per page including the genesis replay. This is
  proof context, not a deployment figure.
- The global write lock serializes page application with live booking.
- Notifications stay best-effort; readers and the archive establish state.
- Found during the proof, pre-existing: transition VERIFY stores its whole witness in one SQL
  row and exceeded the default `max_allowed_packet` at about 1,000 trades. The proof raised it on
  its disposable database only; the transition service is unchanged.
- No HA, throughput, latency, financial valuation or cloud claim.

## Executable proof

From the generated runtime root with Java 21 and Docker, after reserving containers
`traderx-apr-live-sql`, `traderx-apr-live-nats`, `traderx-apr-controls-sql` and ports
28200-28399: `RI06_AUTO_RECOVERY_PROOF=1 bash recovery-identity/test-live-automatic-recovery.sh`.
It refuses pre-existing named containers and runs the live proof, the automatic SQL controls
and the retained explicit-recovery SQL controls in sequence.
