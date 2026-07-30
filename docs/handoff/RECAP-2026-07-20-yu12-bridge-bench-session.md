# YU12 Session Recap — Trade Bridge, UI Order Path, Throughput & Failover (2026-07-20)

**Purpose:** capture what this session actually established about YU12 running on GKE — what works,
what's genuinely open, and the measurement traps that cost time — so the next chat starts from
verified ground instead of re-deriving it.
**Status:** working note, created 2026-07-20. Untracked — do not commit.
**Parent:** `HANDOFF-ha-throughput-improvements.md`, `project_yu12_aeron_cluster_state.md` (memory).
**Cluster under test:** `traderx-lmax` (GKE, us-east1-b), 3-member Aeron Cluster on `blp-c3-pool`,
3-replica gateway on `std-pool`, image `cluster-node:yu12`.

---

## 1. What was PROVEN working this session

- **The leader-side trade-egress → NATS bridge works end to end.** A booked trade on the leader
  publishes `NatsEnvelope<TradeOrder>` to `/trades`; the trade-processor consumes it, writes the
  SQL DB, and republishes `/accounts/{id}/trades` + `/positions`. Verified by: leader NATS
  **publish** count (`in_msgs`) tracking fills exactly (7 fills → 7 publishes), a live `/trades`
  subscriber (cid 96), and per-account republish counters incrementing only after the DB write.
- **Matching itself is healthy.** On a fresh account the gateway does **15–16k submit/s with
  `failed=0`**; members sit at **5–10% node CPU** under load. The cluster is nowhere near its limit.
- **The matching MODEL is now understood** (it was the source of much confusion):
  - Price-triggered, not a crossing book: an order auto-fills against the security's **last market
    price** (`lastPxBySecurity`) when `isInTheMoney` (`MatchingEngine.java:613`) — BUY fills at
    `mark ≤ limit`, SELL at `mark ≥ limit`. No opposing resting order is needed.
  - `FILL_FULL_THRESHOLD = 100` (`MatchingEngineClusteredService.java:50`): an order with
    `remaining ≥ 100` fills **half** (`(remaining+1)/2`) and leaves the rest resting. With the bench
    default `QTY=500`, every order leaves a resting remainder forever → **the book grows unbounded
    and throughput decays**. `QTY < 100` fills fully at entry and keeps the book flat.
  - `CREDIT_LIMIT_TICKS = Long.MAX_VALUE/4` — effectively unlimited; credit was NOT the limiter.

## 2. Clean measured numbers (this session, fresh cluster, zero failures)

| Path | submit/s | booked/s | restarts |
|---|---|---|---|
| via `order-matcher-gw` Service (pinned to 1 gateway) | 40,937 | 27,064 | 0 |
| 3 generators, one per gateway pod (bypass Service) | **62,190** | — | 0 |
| 6 generators across 3 gateways | **63,761** | **44,916** | 0 |

**Sustained ceiling as measured today: ~62–64k submit/s, ~45k booked/s, 0 restarts.** This clears
the 25,149 NFR-AC02 bar comfortably and sustainably.

## 3. The two measurement traps that wasted time (write these down)

1. **NATS `out_msgs` vs `in_msgs`.** On a NATS connection, `in_msgs` = messages the server received
   *from* the client (what it **published**); `out_msgs` = messages delivered *to* its subscriptions.
   The bridge is publish-only (`subs 0`), so `out_msgs` is always 0 — I misread it as "published
   nothing" and chased a non-existent matching bug for many turns. **Bridge health = leader
   `in_msgs`.** Also: the `/subsz` JSON key is `subscriptions_list`, not `subscriptions`.
2. **`sessionAffinity: ClientIP` silently disabled the scale-out.** The combined gateway Service has
   `sessionAffinity: ClientIP` (10800s) for FIX stickiness. It pins every request from a single
   client IP to **one** gateway pod — so with load coming from one `bench-runner` pod, **2 of 3
   gateways showed literally 0 accepted orders all session** (counters: 0 / 0 / 5,276,250). This is
   why "4 parallel generators" gave no gain and why the Service-path number (41k) is ~1.5× below the
   real ceiling (64k). The scale-out works; the Service config hid it.

## 4. Changes made this session

- **Added `POST /trades` route + handler to the gateway** (`ClusterGatewayMain.java:110`, `:387`) so
  the web UI create-order path (trade-service → `{url}/trades`, a `MarketTradeRequest`) has a route.
  Maps to `TYPE_TRADE_NEW`; the engine books at the last tick and the bridge carries it out.
  Compiles clean (`BUILD SUCCESSFUL`). Committed **`35fd903`** (local only, not pushed).
  **NOTE: user reports the UI create-order still fails after build+push+repoint — see Open Issue A.**

## 5. What is NOT resolved (honest status)

- **134k was NOT reproduced today.** `batch=1000` as the very first load on a clean cluster gave only
  **2,158 booked / 4,073 submit** — nowhere near last night's validated 134,755 burst. Root cause
  unknown; leading suspect is that today's image rebuild (for the `/trades` fix) changed the member
  binary, and `imagePullPolicy: Always` + the clean restart put that new build on all three members.
  **Unverified.** Do not present 134k as currently reproducible; last night's burst peak is valid
  *as a burst on the prior build* (its own table cross-checks `booked=applied`, `sub/appl 0.99`).
- **Failover was not cleanly measured.** A probe caught a *spontaneous* leader move
  (cluster-1 → cluster-0) under bench load costing 4 of 654 requests (99.4% success), but the planned
  leader-kill test did not run (pod was never deleted). No clean node-clock failover number this
  session.
- **UI create-order still broken** post-fix (Open Issue A).

---

## Open issues (for discussion → `issues/`)

See the companion discussion; candidates are:
- **A. Frontend + TraderX-services rewire** to the cluster/gateway/bridge topology (create-order path).
- **B. Consistent high throughput (135k+)** — reproduce sustainably, not as a fragile burst.
- **C. `sessionAffinity` per-port split** so REST scales out (concrete, ready to implement).
- **D. Clean failover measurement** + explain spontaneous election under load.
- **E. Bench harness** that can actually saturate the cluster (distributed load gen, booked-cascade).
- **F. At-least-once bridge gap** — no published-offset checkpoint across failover.
