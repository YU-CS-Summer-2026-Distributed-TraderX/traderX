# YU12 — Rewire Frontend + TraderX Services to the Cluster/Gateway/Bridge Topology

**Purpose:** the legacy TraderX services were wired for the old single `order-matcher`. YU12 replaced
that with a 3-member cluster fronted by `order-matcher-gw` and a leader→NATS trade bridge, but the
surrounding services were never fully re-pointed. This is the workstream to make the whole app
(frontend create-order, positions, trades, reconciliation) consistent with YU12.
**Status:** ROOT CAUSE FOUND + FIXES COMMITTED 2026-07-20, pending GKE apply/verify. The residual
break is hypothesis 3: `BlpRiskState.decideMarketTrade` rejects any TRADE_NEW whose account is not
control-enabled, security not enabled, or security without a price tick (UNKNOWN_ACCOUNT /
ACCOUNT_DISABLED / UNKNOWN_SECURITY / PRICE_MISSING) — and `/trades` is fire-and-forget, so the
gateway acks 200 and nothing books. No seed step existed on the UI path. Fixed: `seed-real-accounts`
Job (gke kustomization) enables the 7 real SQL accounts + the reference-data ticker universe +
price ticks; `trade-service-deployment.yaml` repointed to `order-matcher-gw` in-repo (was live-only);
recon sweep backed off to daily (`RECON_POLL_INTERVALMS`). Contract verified in code: TradeSide
serializes `Buy`/`Sell`, gateway parses `security`/`accountId`/`quantity` — match; bridge publishes
on KIND_TRADE_BOOKED which `emitMarketTrade` writes, so booked market trades do reach NATS→DB→UI.
Silent-reject landmine CLOSED in `3394186`: `/trades` is now synchronous — 200 `{"booked":true}` /
422 `{"booked":false,"reason":"<RiskReason>"}` / 504 ambiguous (was fire-and-forget green-200 that
hid every reject). Needs an amd64 gateway rebuild to deploy; fold into the step-2 rebuild.

**VERIFIED LIVE ON GKE 2026-07-21** (01478e4 + 2f5a813 applied; 3394186 still needs its rebuild):
the whole create-order path works end to end through trade-service — validated → gateway `/trades`
→ cluster booked → bridge → DB → position feed; confirmed for 10031/22214/44044 and across a leader
kill (20/20 booked, one promotion, no reuse). GOTCHA that bit us and is now in the runbook: the
`kubernetes-runtime/manifests/base` deployments carry LOCAL image tags, so a raw `kubectl apply -f`
of them on GKE ImagePullBackOffs and strands the corrected env on a Pending pod — patch env with
`kubectl set env` (or the YU02 gke prepare script) instead.
Created 2026-07-20. Untracked working note.
**Parent:** `RECAP-2026-07-20-yu12-bridge-bench-session.md`.

---

## Symptom

The web UI **"Create Order" button fails**. Repointing + a gateway `/trades` route were applied this
session and it *still* fails — so at least one more break remains in the chain.

## Debris found this session (the concrete list)

| Thing | State found | Correct target |
|---|---|---|
| `trade-service` `ORDER_MATCHER_URL` | `http://order-matcher-primary:18110` (**0 endpoints**, conn refused) | `http://order-matcher-gw:18110` |
| gateway `/trades` route | **missing** — UI POSTs `MarketTradeRequest` there → 404 | added this session, commit `35fd903` (local) |
| `trade-processor` ReconciliationService | sweeps `order-matcher` (**0 endpoints**) → `ConnectException` every 10s | point at gw, or disable sweep for YU12 |
| `order-matcher-0` pod | stuck `Pending` forever (node affinity matches 0/6 nodes — wants old pool) | delete the old StatefulSet/Deployment |
| services `order-matcher`, `order-matcher-primary`, `order-matcher-headless` | legacy, no endpoints | delete or leave as inert aliases |

## Why it STILL fails after the repoint (hypotheses to check, in order)

1. **The `/trades` fix isn't actually running.** The route was added to source + committed locally,
   but confirm the *pushed* image the members/gateway run contains it: `kubectl exec` the gateway and
   curl `localhost:18110/trades` (POST) — a 404 means the running image predates the fix.
2. **`trade-service` didn't pick up the new env** — confirm the rollout completed and the pod's env
   actually shows `order-matcher-gw` (a `set env` needs the rollout to finish).
3. **Account/security not seeded in the cluster.** `/trades` books at the last market price; if the
   account isn't control-enabled or the security has no price tick, the engine rejects or no-ops.
   The UI has no `/seed` step — real accounts need enabling some other way (see Open Question below).
4. **Contract mismatch** — verify `trade-service` sends a body the `/trades` handler parses
   (`accountId`, `security`, `side` as `Buy`/`Sell`, `quantity`). `OrderSide` enum serialization
   (`Buy`/`Sell` vs `BUY`/`SELL`) is a likely snag.

## Action plan

1. Verify the running gateway image serves `POST /trades` (hypothesis 1). If not, rebuild+push+roll.
2. Trace one UI create-order with `trade-service` logs at DEBUG — see exactly what URL it hits, what
   body it sends, and the response code. This single trace resolves most of the above.
3. Decide the **account-enablement story** (Open Question) — the UI path can't call `/seed`.
4. Clean up dead services/pods so reconciliation stops erroring and the topology is legible.
5. Re-verify: create-order in UI → trade in DB → position + trade appear in the UI feeds.

## Open question — account enablement for real (non-bench) flow

`/seed` (control-enable account + security + price tick) is a bench convenience. Real order flow
through the UI has no seed step. Options: (a) a startup seeding job that enables the 7 real SQL
accounts + all reference securities from a live price feed; (b) fold enablement into the
reference-data / account-service startup; (c) auto-enable on first order (weakens the risk gate).
This is a design decision, not just wiring — needs a call.

## Scope note

This is also where the broader **"does the frontend fit this new state"** question lives: positions
and trades feeds now originate from the bridge, not the old projector. Confirm the UI's data sources
(`/accounts/*/positions`, `/accounts/*/trades`) are fed by the bridge path end-to-end, not a stale
projector that no longer runs.
