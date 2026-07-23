# Recap — 2026-07-23: brief 04 CLOSED — YU15 GKE tier + gs:// delivery, E2E-proven

> Worktree `traderX-YU15-eod-risk-extract`, branch `YU15-eod-risk-extract`, commits
> 06f3d245 → e4cbad51 (plus propagation 309b4a37 on YU13, c150297f on YU14). **Not pushed.**
> The consumer deliverable (Rich & Alex's engine, ~2026-08-21) now has its real transport proven:
> a write-once `gs://` object, byte-identical to the cut, produced by the full chain on GKE.

## What was proven (the acceptance, at the effect end)

`scripts/bench/yu15-risk-extract.sh` (now gs://-aware), full PASS on GKE, 2026-07-23:

- Real chain end to end: session close → 44 instruments published (24 option contracts, 0
  flagged) → P&L (4 accounts, 0 halted) → `eod.pnl.done` → extract fired.
- Delivered `gs://traderx-501015-risk-extracts/2026-07-23/v2/seq-396.csv` (+ its `.cut` beside
  it), announced on `risk.extract.ready` with the correct URI/stamp.
- Byte-identical cut across all 3 members at consensus sequence 396; quiescence witnessed at 397.
- `--rebuild` from the stored cut byte-matches the **downloaded gs:// object** (fetched
  out-of-band with gcloud, not the sink's own client).
- Member restart → replayed to 396 → re-rendered the identical cut sha.
- Write-once: proven server-side at the transport level (see below); the fixture includes the
  option row with multiplier-aware marketValue/unrealizedPnl.

## Finding 1 — the shipped gs:// sink could NEVER work (why file://-only proof was a lie)

`x-goog-if-generation-match: 0` is rejected by GCS with **400 ExcessHeaderValues** on any request
that mixes `x-goog` and `x-amz` headers — and SigV4/HMAC signing always adds `x-amz` headers. The
S3-compat path (the established journal-archive HMAC auth) therefore cannot use x-goog headers at
all. Fix (commit 6b452f98):

- Sink sends standard `If-None-Match: *` instead.
- The delivery bucket `gs://traderx-501015-risk-extracts` grants the journal HMAC SA
  (`order-matcher-journal-gcs@`) **objectCreator + objectViewer only — no objects.delete** — so an
  overwrite is refused 403 at IAM level even from a precondition-less client. Both refusals
  verified empirically (curl bisect: plain PUT on existing key → 403 delete-denied;
  `If-None-Match: *` on new key → 200).
- Standing proof: `RiskExtractGcsSinkLiveProofTest` (env-gated on
  `RISK_EXTRACT_GCS_HMAC_KEY_ID/SECRET`, skips in CI). Uploads the real contract sample fixture,
  reads both objects back byte-identical, asserts a tampered redelivery is refused server-side
  with stored bytes untouched. Run:
  `RISK_EXTRACT_GCS_HMAC_KEY_ID=… RISK_EXTRACT_GCS_HMAC_SECRET_ACCESS_KEY=… ./gradlew test --tests RiskExtractGcsSinkLiveProofTest`
  (creds: `kubectl -n traderx get secret order-matcher-journal-gcs-hmac`).

## Finding 2 — the readmodel bundle silently killed the EOD price feed (dead-layer trap, again)

The order-readmodel propagation (84dd28c4) **added** `PubSubConfig.java` to the YU13
runtime-overrides layer, authored from YU13's branch copy — which never had YU05's
`priceTickHandler` bean. That file shadows YU05's at generation, so trade-processor lost its
`pricing.*` subscription on YU13/YU14/YU15: session close published `instruments=0`, and the whole
extract chain starved upstream ("no option contracts priced"). Same shape as the BlpRiskState/YU14
override trap. Fixed by carrying BOTH beans in the YU13-layer copy (e4cbad51), propagated verbatim
+ md5-verified to YU13 (309b4a37) and YU14 (c150297f). trade-processor suite green (11 classes).

**The rule, again:** adding a layer copy of a file means carrying every bean/branch the shadowed
ancestors contributed. Grep `find specs -name <file> -path '*runtime-overrides*'` before authoring.

## Finding 3 — init-SQL ordering vs a reused namespace (VARCHAR(15) came back from the dead)

mariadb runs init SQL **once, at first boot on an empty datadir, against whatever configmap exists
at that moment**. The GKE namespace already carried the YU09-era `database-init-sql`, the start
script applied the kustomization first, and `eod-price-db` initialized with the narrow pre-YU15
schema — `security` VARCHAR(15), the exact OCC blocker this state fixed — so the option fill never
reached SQL. Both start scripts now apply the schema configmap BEFORE the kustomization
(e4cbad51); DB re-initialized; the 19-char OCC row live-verified in `positions` on GKE.

## The GKE tier itself (commit 06f3d245)

`specs/YU15-eod-risk-extract/generation/kubernetes/cluster/gke/` — YU14's full hardened set
(emptyDir members, 4 gateways, C4D pool + hard hostname anti-affinity, Guaranteed QoS through the
restore init, c4d kubelet config outside `resources`) + the YU15 chain (NATS/JetStream,
eod-price-db, trade-processor, EOD chain, risk-extract producer with the gs:// sink env).
Self-contained: ships its own `nats`; members' `TRADE_BRIDGE_NATS_URL`/`RISK_EXTRACT_NATS_URL`
point at it, NOT at the YU09 `nats-broker`. Render-verified (EXIT=0, 25 objects).
`scripts/yu15/start-cluster-gke.sh` is the one-shot deploy (schema configmap → kustomization).

**Image pins** (manifest-pin lesson, deliberately NOT `:yu13-idempfix`): the whole cluster tier
runs `cluster-node:yu15-idempfix` — one image, built from THIS branch (which carries the
idempotency fix via d14414b9); a YU13-branch image has no risk-extract marker handling and the
extract would ack-timeout. Spring services run `<service>:yu15-extract`. All four pushed to GAR
(amd64): cluster-node `a270e7c9…`, trade-processor `81347e5c…` (with the PubSubConfig fix),
position-service `373e91b3…`, price-publisher `8b99ea00…`.

**Build note:** container-gradle amd64 builds took 37+ min to reach `compileJava` on this Mac
(emulation). Host `./gradlew bootJar` + the plain jar-copy `Dockerfile` is ~2 min/service. The
compose Dockerfiles are for CI, not this host.

## Cluster state as left (teardown is yaakov's — pool scaling is classifier-blocked)

- GKE `traderx` namespace: YU15 stack UP and healthy — 3 members (`yu15-idempfix`, fresh epoch,
  seq ~400), 4 gateways, nats, eod-price-db (wide schema), trade-processor/position-service/
  price-publisher (`yu15-extract`), risk-extract producer. 7 nodes (3 c4d + 4 c2d).
- The YU13-era `order-matcher-cluster` sts + `cluster-gateway` deploy were replaced clean-epoch
  (deterministic-core change — never rolled through a mixed-version window). The old YU11
  `order-matcher` sts (0/2 Pending) and YU09 stack (scaled 0) untouched; `reference-data` was
  scaled up for account seeding and restored to 0; both cronjobs remain suspended; `database` +
  `nats-broker` (YU13 read-model leftovers) still running, now orphaned by this stack.
- Bucket: `gs://traderx-501015-risk-extracts` — proof objects under `proof/` and the delivered
  `2026-07-23/v2/seq-396.{cut,csv}`. Write-once: the HMAC SA cannot delete or overwrite.
- Positions in the book: small proof portfolio (accounts 10031/11413 crossing AAPL/JPM/MSFT +
  AAPL260918C00220000×2 rounds; earlier seed positions on 22214/52355 et al).

## Still open (not brief-04 scope)

- Task 3 debt items are disclosure-only (TD-RXT01 payload bound, TD-RXT02 modelled option closes,
  TD-RXT04 OCC predicate ×3) — unchanged.
- Task 4 consumer questions (counterparty entity, P&L methodology confirmation with ORE,
  `risk.analytics.*` results-return) — still need Rich & Alex.
- `risk.entitlement.enforced` remains false, per the standing rule.
