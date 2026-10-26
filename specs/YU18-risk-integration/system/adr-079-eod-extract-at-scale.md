# ADR-079: The EOD extract at production scale

Status: Accepted, 2026-10-25. Amends ADR-055 (YU15). Each section is one gap found by the
2026-10-25 hxb campaign and closed on `claude/eod-risk-gaps`. Evidence and numbers are in
`independent-baseline/results-20261025-open/EOD-RISK.md`.

## 1. The cut travels in parts (item 2)

**Context.** ADR-055 sent the cut as one NATS message and noted the 1 MB default `max_payload` as a
ceiling for later. It is reached at about 30K positions (34 bytes a row). Past it, jnats refuses
the publish (`Message payload size exceed server configuration`), and the leader's bridge counts a
drop. The extract job then times out on every JetStream redelivery, so the EOD extract never
completes.

**Decision.** The leader sends the cut as parts sized from the server's own `max_payload`. Each part
is a `#cutpart seq= part= parts= bytes= sha256=` header line followed by a slice of the cut. The
consumer (`RiskExtractCutPublisher.Assembly`) yields the cut only once every part is present and the
concatenation has the declared length and SHA-256. A missing part therefore times out, and a
corrupt, truncated or contradictory part is refused. The cut's `rows=` self-count is unchanged and
still checked downstream. A pre-YU18 single `#cut` message is still accepted.

**Rejected:** raising `max_payload`. The NATS server caps it at 64 MB and recommends 8 MB at most,
so it only moves the ceiling (a 1M-position cut is 36 MB), and it would have to be raised on every
NATS deployment. The cut subject has one consumer, `RiskExtractMain`, so parts change no external
contract.

## 2. The cut renders on the apply thread, in bytes (item 3)

**Context.** The concern was that a long render on the deterministic apply thread would elect at
`CLUSTER_HEARTBEAT_TIMEOUT_MS=200`. Measured locally (laptop): the StringBuilder render took 34 /
104 / 530 ms cold at 10K / 100K / 1M positions, and allocated ~188 MB at 1M. In the member, which
also splices the run-identity header and re-encodes the cut for NATS, that is ~300 MB.

**Finding 1, the premise is false for the thread.** `ApplyStallElectionTest` runs three members at
the rig's timings (heartbeat 50 ms, timeout 200 ms, election 100 ms). A 600 ms sleep, and a real
~600–800 ms 1M-position render, on every member's apply thread leave the leadership-term count
unchanged. A positive control (leader killed) proves that the probe sees elections. The consensus
module is its own agent, and heartbeats and commit do not wait on the service. Copying state to
render it on another thread would buy nothing.

**Finding 2, what can elect is a JVM-wide pause, and allocation is what causes one.** With a 200 MB
live set under the members' `-Xmx512m`, the old render's allocation ends in an `OutOfMemoryError`.
Every member renders, so that would take down all three at once.

**Decision.** `RiskExtractCut.renderBytes` writes the cut once, into one exactly-sized US-ASCII array
(measure pass, then write pass). It sorts primitive packed keys, takes the run-identity fields as a
header suffix instead of splicing a copy, and hashes the bytes without re-encoding. It is byte for
byte the old renderer: `RiskExtractCutBytesTest` keeps the old code as its oracle across 200 random
books plus the `long` extremes, because one changed byte would break FR-RXT10 for every past cut.
At 1M positions it allocates 50 MB (output + sort index) instead of ~188 MB, and the test fails if
allocation grows past output + 16 B/row.

**Rollout (items 2 and 3).** The cut bytes and the replicated state are unchanged, so this needs no
fresh epoch, and members can be rolled in any order. The wire format did change: an old
`risk-extract` job ignores `#cutpart` messages and would time out. A new job still reads an old
member's single message. So roll the `risk-extract` Deployment first, or with the members (both
ship in the cluster-node image).

## 3. Purged log is offloaded before it is deleted (item 4)

**Context.** `ArchivePurgeMain` bounds a member's archive by deleting log behind the second-latest
snapshot. That silently withdrew two promises: ADR-055 / FR-RXT10 (a past cut is re-created by
replaying to its sequence), and every full-history replay (ENGINE-REVIEW F8; recon now refuses
rather than mislead). An empty-disk member also cannot rejoin a purged cluster.

**Decision.** A detached segment is uploaded write-once before it is unlinked, to
`ARCHIVE_PURGE_OFFLOAD_URI`, under `member-<id>/`. The URI is `gs://bucket/prefix`, through the same
GCS-over-S3/HMAC transport as `RiskExtractGcsSink`, with Content-MD5 and `If-None-Match: *`; or
`file:///dir`. A failed upload deletes nothing, so the backpressure is the disk filling, never lost
history (yaakov's offload decision, 2026-10-23). Purging without an offload requires the explicit
`ARCHIVE_PURGE_DISCARD_HISTORY=true`. With neither set, the agent refuses to start
(`PURGE_REFUSED`, exit 2).

To bring history back: copy the objects into the member's `/data/archive`, then run
`ArchivePurgeMain --attach <clusterDir> <aeronDir>`. That re-attaches them to the recordings, so a
replay from 0, ClusterRecon's strict full-history replay, and an empty-disk join all work again.

**Rejected:** "refuse to purge past the last EOD marker's snapshot". It keeps only the latest EOD
re-creatable, and it holds a whole trading day of log on the member (~110 GB/h at 250K/s). The
agent also has no way to learn the marker's log position without a new member surface.

**Consequences.** Every member uploads its own copy of the same log, so storage is 3x; deduplicating
needs cross-member delete coordination. The bucket wants a lifecycle rule. Not wired into any
manifest yet, exactly as before: enabling the purge is a deployment decision, and now so is the
offload target.

## 4. Cluster ingress is authenticated (item 5)

**Context.** ENGINE-REVIEW S1–S6 put credentials on every HTTP and FIX route. The Aeron ingress
behind them stayed open: any process that reached a member's client-facing UDP port could connect a
session and sequence commands the gateway's checks never see (orders, extract markers, symbol
registrations). It could also spend the `CLUSTER_MAX_SESSIONS` budget the gateways need. The kind
tier had a member NetworkPolicy. The GKE tier, which is what runs, had none.

**Decision.**
- Members install a shared-secret Aeron `Authenticator` (`ClusterClientAuth`) when
  `CLUSTER_CLIENT_SECRET` is set. A connect request is admitted only on a constant-time match of its
  credentials; anything else is rejected, counted (`traderx_cluster_ingress_rejected`) and logged
  (`CLUSTER-INGRESS-REJECTED`).
- As with S5, a member refuses to start when the secret is unset or a dev value, unless
  `ALLOW_DEV_SECRETS=true`. It then logs `CLUSTER-INGRESS-UNAUTHENTICATED`; the kind tier runs this
  way.
- Every cluster client presents the secret: `ClusterGatewayMain`, `RiskExtractMain`,
  `FeedAdapterMain`, and `ClusterProofClient`, whose YU12 class gets a YU18 copy for this one line.
- On GKE the secret comes from `auth-secrets` key `cluster-client-secret`, generated from the
  git-ignored env file like the S5 secrets. The tier also gains the member NetworkPolicy as defence
  in depth. It is enforced only on a Dataplane V2 or network-policy cluster, so the secret, not the
  policy, is what closes the ingress.

**Rejected:** NetworkPolicy alone. It is inert on a GKE Standard cluster without enforcement, and
nothing here shows that ours have it. It also trusts any pod that can carry an allowed label.

**Session budget (64 by default).** One session per client: the GKE tier holds 3 gateways, the
risk-extract job (connected only for the duration of one batch), and the proof client when applied,
so 5. The hxb rig holds 18 gateways plus the extract job and a feed adapter, so about 20. A gateway that
reconnects after a failover can briefly hold its old session until the session timeout, so budget
2x the gateway count. `ClusterIngressAuthTest` (3 members, `maxConcurrentSessions=2`) shows that
refused connects do not spend the budget: after ten refusals, two good clients both get in and have
their commands applied, and a third finds the budget full.

**Rollout.** No engine, wire, snapshot or epoch change. But it is a startup contract change on every
tier, as S5 was: a member without the secret, or without the explicit dev opt-in, no longer starts.
A client without the secret is refused by a member that has one. Set the secret on all members and
clients in one rollout. The hxb harness has to supply it, or `ALLOW_DEV_SECRETS=true`. `sandbox.yaml`
is outside the kustomization, and its gateway already lacks the S5 secrets.

## 5. The extract no longer waits on the SQL projection (item 1)

**Context.** The concern was that the EOD marks depend on the SQL read models that trade-processor
feeds, measured at ~950 orders/s and lossy under lag (DOWNSTREAM.md, 2026-10-25). Traced:
- **Positions:** the cut at N, from cluster state.
- **Closes:** `eod_price_snapshot` is produced from trade-processor's price history, which is the
  `pricing.*` market-data feed sampled at the session-close instant (YU06), not from the
  trade/position projection. It is immutable once published.
- **Fallback mark:** a security without a published close is marked at the cut's own last trade at N.
- **The fixture's P&L columns:** cut × marks.

The one link through the projection was the trigger. The job waited on `eod.pnl.done`, which
position-service emits only after marking the SQL `positions` table. So a lagging projection delayed
the extract, a dead one blocked it, and the SQL P&L could disagree with the fixture it preceded. The
consumer (`eod-risk-bundles`) reads only the ready message's `uri` / `contractsUri` / `cutUri` and those
three files, never the SQL P&L.

**Decision: source the EOD path from the cluster.** The job triggers on `eod.prices.ready` (the
closes are published and frozen), through a new durable `risk-extract-prices` created with
`DeliverPolicy.New`. The YU15 durable filters the old subject and cannot be refiltered in place. A
fresh durable would otherwise replay the stream's history and re-extract every past session. Marks,
`markSource` values and the ready payload are unchanged.

**Rejected:** gating on the projection's catch-up. The persisted completeness cursor
(`projection_catchup_cursor.verified_through_seq`) is maintained only by RI06 auto-recovery, which is
local-only by design ("GKE profiles never enable this"). The live feed counters
(`/v2/projection-feed`) are per-process and reset on restart. A gate on either would be a proxy that
can be wrong, protecting a number the risk engine does not read.

**Consequences.** The SQL P&L (`eod_pnl`) is still produced, for the UI. It is still only as good as
the projection, and it is no longer on the risk-engine path. Rollout: one `risk-extract` restart,
with no engine or epoch change. The old durable `risk-extract` is orphaned and can be deleted.
`RISK_EXTRACT_TRIGGER_SUBJECT` / `RISK_EXTRACT_DURABLE` restore the old trigger, but only under a new
durable name, since `DeliverPolicy.New` cannot be applied to the existing one.

## 6. Sizing found by the cloud test (2026-10-26)

**Member heap 12 GB.** Every touched security allocates a `LimitBook` of `DEFAULT_BOOK_LEVELS = 2^17`
levels: four `RestingOrder[]`, two `long[]`, two `int[]` and the bit maps. That is ~5 MB per book on G1
with compressed oops, and ~7 MB under ZGC. On the hxb rig (ZGC, 3 GB heap), 62 accounts × 1,000 symbols
ran **all three members** out of memory on the apply thread within 27 s of the load starting. The
election that followed came back with a dead service on every member. `MAX_SECURITIES = 1024` books need
~5–7 GB, so the GKE members now run `-Xms12g -Xmx12g` (collector unchanged: G1) at 16Gi request = limit.
The 1Gi memory-backed `/dev/shm` counts against the same cgroup.

**Offload uploads run in parallel.** On the rig, one 128 MiB segment took 12.3 s at 0.3 CPU (~11 MB/s
per member), under the ~12.5 MB/s a member writes at 100K orders/s. `ArchivePurgeMain` now uploads
`ARCHIVE_PURGE_UPLOAD_PARALLELISM` (4) segments at a time. It still unlinks them in order, each only
after its own upload returns. The first failure stops the batch: that segment and every later one stay
on disk, even where a later upload succeeded, so the archive never has a hole below a segment it still
holds. Size the purge sidecar at 2.5 CPU. Its pod then needs member CPU + 2.5, so **run members on
c4d-standard-16 when the purge sidecar runs.** An SMT-off c4d-standard-8 shows only 4 cores.

**The purge is still not wired into the GKE tier, deliberately.** That tier's members use `emptyDir`.
Any pod replacement (rollout, node drain, eviction) starts on an empty disk, and once the log is purged
it cannot rejoin until an operator restores the offloaded segments and runs `--attach` (ADR-079 §3;
proven on the rig: 30 segments, 4 GB, back in under a minute per member). Enabling it is the
deployment's decision. Its inputs would be an `archive-offload` Secret (URI + HMAC, never inline) and
c4d-standard-16 members.

**Refuse, don't OOM: `MAX_ACTIVE_BOOKS` (built 2026-10-26, yaakov's decision).** Book allocation was
unbounded by memory, so an overflow of this class killed every member at once. Now a NEW order on a
security with no book yet is **refused** with `BOOK_CAPACITY` (appended to `RiskReason`) once the member
holds `MAX_ACTIVE_BOOKS` books, exactly as `MAX_SECURITIES` and the F7 account-table overflow refuse. One
order fails visibly instead of three JVMs dying. Both admission paths (untyped and typed) check it after
the unknown-security check and before `bookFor` allocates. A security that already has a book keeps
trading at the cap.

- **The cap.** `MAX_ACTIVE_BOOKS=<n>` pins it. Unset or `auto` derives it from the heap: half the max
  heap ÷ book size, where a book is `BOOK_LEVELS × (4 refs + 24.25 B)` (refs 4 B with compressed oops,
  8 B without). It is clamped to `[1, MAX_SECURITIES]`. Examples: 12 GB G1 → 1,024 (all of them);
  12 GB ZGC → 873; 3 GB ZGC (the attempt that ran out of memory at 1,000 symbols) → 218. Each member
  logs `BOOK-CAPACITY maxActiveBooks=… bookLevels=… maxHeapBytes=… compressedOops=…` at start, so a
  mismatch is visible.
- **Replicated behaviour.** The cap changes the engine's state function. Every member must run the same
  value (same env and heap flags), and it changes only by a fresh-epoch, same-image roll. It is
  configuration, not state: it is never snapshotted, and the snapshot format is unchanged.
- **Restore counts.** Books are counted where they are allocated, `bootstrapBook` included, so a
  restored member holds the same count as the member that wrote the snapshot and gives the same
  decision. Restore itself never refuses: the snapshot already holds those books.
- **Tests (`BookCapacityTest`):** no refusal below the cap; `BOOK_CAPACITY` at cap + 1 on both paths with
  no book allocated; an existing book still trades at the cap; the same refusal and acceptance on the
  original and on a member restored from its snapshot; the heap derivation. Negative control: with the
  check disabled, the cap + 1 assertion fails.
