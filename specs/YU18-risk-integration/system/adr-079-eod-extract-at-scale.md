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

