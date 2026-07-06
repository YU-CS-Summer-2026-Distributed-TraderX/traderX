# Runtime Topology & Startup/Degraded Matrix: YU03

Referenced by `spec.md` FR-IMRG35: no generic fail-open switch is permitted for risk-increasing
commands. This is the explicit behavior matrix for slice 1.

## Deployment topology

Unchanged from `YU02-lmax-kubernetes`: order-matcher is the BLP (StatefulSet on GKE, single-BLP or
HA via k8s Lease + NATS JetStream replication). The risk gateway is **folded into order-matcher**
(same JVM as the BLP) in slice 1 — not a separate service or sidecar. Account-service,
reference-data, price-publisher, trade-service, etc. are inherited unchanged; the only new outbound
calls are `ReplicaBootstrap`'s one-shot startup fetches of `GET /account/` and `GET /stocks`.

## Readiness

- **BLP readiness** (inherited): `ReadinessState.ACCEPTING_TRAFFIC` only after journal/snapshot
  recovery (or follower catch-up) completes.
- **Risk replica readiness**: granted once the seed image is installed and SymbolTable ids are
  aligned (`GatewayReplicaStore.markReady()` in `LmaxEngine.afterPropertiesSet`). Until then,
  screening returns `CONTROL_STATE_STALE`. `ReplicaBootstrap` then enriches the universe in the
  background (PRIMARY only); a FOLLOWER receives control state via replication and, if promoted,
  picks up the bootstrap loop.

## Startup / degraded behavior matrix (fail closed for risk-increasing commands)

| Condition | Gateway (edge) | BLP (authoritative) |
|---|---|---|
| Replica not yet ready (pre-seed / pre-align) | reject `CONTROL_STATE_STALE` → **503** | n/a (edge rejects first) |
| Kill switch armed (sequenced policy control) | reject `KILL_SWITCH` → **422** | reject `KILL_SWITCH` (authoritative) |
| Unknown / disabled account | reject `UNKNOWN_ACCOUNT` / `ACCOUNT_DISABLED` → **422** | same, authoritative |
| Unknown / disabled / halted security | reject `UNKNOWN_SECURITY` / `SECURITY_DISABLED` → **422** | `UNKNOWN_SECURITY` |
| Restricted security | reject `RESTRICTED` → **422**; resting orders cancelled via sequenced CANCEL (FR-IMRG24) | `RESTRICTED` |
| Missing / stale price (market trade) | reject `PRICE_MISSING` / `PRICE_STALE` → **422** | `PRICE_MISSING` / `PRICE_STALE` |
| Price collar breach (limit order) | reject `PRICE_COLLAR` → **422** | (collar is edge-only in slice 1) |
| Over size / notional / credit / position / concentration | reject early when locally evident | authoritative check + reserve; rejects if aggregate exceeds |
| Gateway passes but BLP rejects (lag/disagreement) | request forwarded | BLP wins; `traderx_gateway_blp_mismatch_total`++ → stable 422 |
| Bootstrap fetch failing (account-service/reference-data down) | seeds still serve; names outside seeds reject `UNKNOWN_*` | unaffected (memory-only) |
| Idempotency table full | — | evict oldest key (retention frontier); if unrepresentable, `CAPACITY` |

**No generic fail-open exists**: every risk-increasing command that cannot be positively admitted
against installed local state is rejected. Loss of the control-plane UI/service does not trigger a
synchronous lookup or erase the last proven policy (FR-IMRG31).

## Deferred (later commits)

Durable source control feeds with watermarked subscribe-buffer-snapshot bootstrap and
gap/epoch/staleness detection (ADR-019, FR-IMRG04/05/32/33/34); multi-Gateway concurrency
(FR-IMRG25); entitlement replica (auth roadmap item).
