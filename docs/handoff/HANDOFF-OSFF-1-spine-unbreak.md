# HANDOFF-OSFF-1 — Un-break the end-to-end spine (finish YU12)

> One of the OSFF-NY direction handoffs (OSFF-1..4), created 2026-07-20 after recalibrating the
> project around a 30-min OSFF NY talk (Nov 4–5) + the risk engine attaching in ~a month.
> **This is work 1 — do it first.** It is the demoable spine AND the data feed everything else
> (real book, risk engine) sits on. Self-contained for a fresh chat.
> **Home:** `traderX-YU12-aeron-cluster` worktree, `docs/handoff/` — beside the YU12 recaps; the
> `HANDOFF-issue-yu12-*.md` docs it references are in that worktree's `issues/`. Untracked working note.

## STATUS — DIAGNOSED & FIXED in-repo (2026-07-20); GKE apply pending

Committed **`01478e4`** on `YU12-aeron-cluster` — **manifest/specs-layer only, no image rebuild**.
Apply on GKE via **`RUNBOOK-OSFF-1-gke-verify.md`** (yaakov runs — GCP is his).

**Root cause was NOT the wiring** (the wiring theory in "The gap"/"Open questions" below was wrong —
kept for history). The engine risk gate (`BlpRiskState.java:227`) rejects any market trade whose
account isn't control-enabled, whose security isn't enabled, or whose security has no price tick —
and **only the bench `/seed` ever set those.** Because `/trades` is **fire-and-forget**, the gateway
returns 200 for the sequenced offer and the rejection surfaces **nowhere** → every UI create-order was
guaranteed to book nothing regardless of wiring. Rest of the chain verified sound in code: payload
contract matches (`Buy`/`Sell`, `security`, `accountId`, `quantity`), both `ClusterGatewayMain` copies
carry the `/trades` route, and the NATS bridge fires on `KIND_TRADE_BOOKED` (which the market-trade
path emits) — so once the gate passes, booked trades reach MariaDB + the UI feeds.

**Fixes (specs layer + generated mirrors, kustomize-build verified):**
- **`seed-real-accounts` Job** (GKE kustomization) — enables exactly the 7 real SQL accounts + the
  20-ticker reference universe @ price 150 (arbitrary accounts FK-poison trade-processor). Control
  events live in the consensus log, so one run survives failover.
- **Gateway Service split** — `order-matcher-gw` is now REST-only, no sessionAffinity; new
  `order-matcher-gw-fix` carries FIX with ClientIP stickiness. Unpins REST (the 41k→64k gap).
- **`trade-service` `ORDER_MATCHER_URL` → `order-matcher-gw`** as a YU12 runtime-override, so
  redeploys no longer revert to the dead `order-matcher-primary`.
- **Trade-processor reconciliation sweep → daily** (was throwing `ConnectException` every 10s against
  the endpoint-less Service).

**One remaining unknown (runbook step 2):** in-cluster `wget /trades` with account 22214 → if it
returns **404**, the running gateway image predates `35fd903` and needs an **amd64** rebuild. Can't be
checked from outside the cluster. Steps 3–5 are the three acceptance artifacts below.

**Residual to watch — demo-safety landmine:** because `/trades` is fire-and-forget and rejections are
silent (200 + vanish), a mis-seeded account/security on stage = orders silently book nothing with no
error. See the residual note in "Open questions". This also matters for OSFF-4 (the risk feed rides
the same booked path).

## The gap

YU12 has consensus, failover, throughput, snapshots, DR, and Grafana all proven — but the one path
a live audience actually clicks is broken: **create an order in the UI → nothing books.** Per
`RECAP-yu12-full-arc.md` §5 and `RECAP-2026-07-20-yu12-bridge-bench-session.md` Open Issue A, the
web `trade-service` was pointed at the endpoint-less `order-matcher-primary` Service, the gateway
had no `/trades` route (added `35fd903`), and the UI create-order **still fails after repoint +
redeploy**. Until this works there is no live end-to-end demo and no clean position feed for the
risk engine.

Two smaller scale-out gaps ride along in the same area and should be closed together:
- **`sessionAffinity: ClientIP` on the combined gateway Service pins all load to ONE gateway pod.**
  It's there for FIX session stickiness, but it also makes REST non-scalable (2 of 3 gateways sat
  idle all session; Service-path 41k vs direct 3-gateway 64k submit). See
  `HANDOFF-issue-yu12-gateway-sessionaffinity-split.md`.

## What "done" looks like

- UI create-order (trade-service → gateway `/trades` → cluster → book → NATS bridge → trade-processor
  → MariaDB → `/accounts/{id}/trades` + `/positions` websocket → UI) works end to end, live on GKE.
- A create-order placed during a **leader kill** still books (proves the demo survives failover).
- REST scales out across all 3 gateway pods; FIX keeps its stickiness. Per-port Service split
  (REST port non-sticky, FIX port `ClientIP`) — the config is already scoped in the issue above.
- Positions land only for the **7 real SQL accounts** (10031, 11413, 22214, 42422, 44044, 52355,
  62654) — arbitrary `/seed` accounts trade in-cluster but FK-fail on persist and poison Hibernate
  batch flushes. Demo + bench must use a real account.

## How

- Trace the create-order path first: `trade-service` request shape is a `MarketTradeRequest` to
  `{url}/trades`; the gateway route added at `ClusterGatewayMain.java`/`ClusterGatewayMain.java:110`
  maps to `TYPE_TRADE_NEW`. Find why it still fails post-repoint — likely the service URL/env still
  resolves to the dead `order-matcher-primary`, or a payload/response-shape mismatch. This is a
  wiring bug, not an engine bug (matching itself is healthy: 15–16k submit/s, `failed=0`).
- Split the Service by port (REST non-sticky, FIX sticky) per the sessionaffinity issue.
- Bridge health is read from the **leader NATS `in_msgs`** (publishes), NOT `out_msgs` (the bridge
  is publish-only, `subs 0`, so `out_msgs` is always 0 — misreading this cost a whole session).

## Proof / acceptance

- Screencap or recorded run: click create-order in the UI → the trade appears in the blotter +
  position updates, live on GKE.
- Repeat during a `kubectl delete pod` of the leader → order still books, one promotion, no reuse.
- Bench: REST load across 3 gateways shows all three pods' accepted-order counters incrementing.

## Dependencies & sequence

- **Blocks OSFF-2/3** in practice: you want a working spine as insurance before cutting into the
  matcher, and OSFF-3's numbers need the sessionAffinity split done here.
- Independent of the risk engine (OSFF-4) — but this is exactly the feed OSFF-4 will consume, so
  keep the position/read-model path generic, not create-order-specific.

## Open questions

- **RESIDUAL — silent rejection. DECIDED (2026-07-20): make `/trades` synchronous.** Await the
  trade-decision ack and return **422 + reason on business reject** (mirrors the existing
  `submitOrder`/`lastOrderAck` await, ~15 lines). Chosen over a side-channel counter because `/trades`
  is the UI path only (one click at a time; bench uses `/orders[/batch]`), so the committed-ack
  round-trip costs no throughput here. Confirmed worse than first framed: gateway `/metrics` doesn't
  count the market-trade path at all — `KIND_TRADE_BOOKED`(6)/`KIND_TRADE_REJECTED`(10) fall through
  `onEgress` untracked → there is currently NO positive reject signal anywhere.
  **Two implementation notes:** (a) don't conflate a *transport/consensus* failure (await timeout,
  leader-kill mid-click → return **503, retryable**) with a *business* reject (**422, terminal**) — a
  failover timeout is not a risk rejection, and mislabeling it is a confident false negative on stage;
  (b) mirror `lastOrderAck`'s timeout + native leader-follow so the UI doesn't hang, and re-verify the
  "create-order during a leader kill" acceptance still passes on the new synchronous contract.
  **Sequencing:** gateway code change → needs an amd64 rebuild, so it breaks `01478e4`'s manifest-only
  property. Step 2 = 404 (route missing) → fold into that rebuild for free; step 2 = 200 → standalone
  rebuild purely for reject-visibility (recommended before a live audience, yaakov's call). (Pays off
  for OSFF-4 too — the risk feed rides the same booked path.)
- *(historical — root cause now known, see STATUS)* Is the residual create-order failure in
  `trade-service` config (still hitting the dead Service),
  in the gateway `/trades` handler payload mapping, or in the trade-processor consume path? Bisect
  by curling the gateway `/trades` route directly with a `MarketTradeRequest` before blaming the UI.
- Does the FIX-stickiness requirement actually need `ClientIP` at the Service, or can it move to the
  gateway's own session layer (which would let the whole Service go non-sticky)?

## First steps for the chat that picks this up

1. Read `RECAP-yu12-full-arc.md` + `RECAP-2026-07-20-yu12-bridge-bench-session.md` (measurement traps).
2. `curl` the gateway `/trades` route directly on GKE with a real-account `MarketTradeRequest` —
   confirms whether the break is gateway-side or trade-service-side.
3. Fix the wiring; apply the per-port Service split; re-run the UI path + a leader-kill during order.
