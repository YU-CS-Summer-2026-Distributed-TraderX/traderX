# RUNBOOK OSFF-1 — apply + verify the spine on GKE (run by yaakov)

> Companion to `HANDOFF-OSFF-1-spine-unbreak.md`. Committed on `YU12-aeron-cluster`:
> - `01478e4` — the wiring + enablement fix, **manifest-only, no image rebuild** (the `/trades`
>   route from `35fd903` is already in `cluster-node:yu12` if the image was pushed after that
>   commit — step 2 verifies this before anything else).
> - `3394186` — makes `/trades` synchronous (422 + RiskReason on reject, kills the silent-green-200
>   demo hazard) + gateway market-trade metrics. **Needs an amd64 gateway image rebuild** — fold it
>   into the step-2 rebuild if that fires, or rebuild once for it before the demo.
>
> Untracked working note, 2026-07-20.

Context: `gke_traderx-501015_us-east1-b_traderx-lmax`, namespace `traderx`. Run from the
`traderX-YU12-aeron-cluster` worktree root.

## VERIFIED LIVE ON GKE 2026-07-21 (commits 01478e4 + 2f5a813; 3394186 NOT yet built)

- **Spine works end to end through trade-service** (the real UI path): `POST /trade/` 10031 BUY 60
  NVDA → validated → gateway `/trades` → cluster booked (leader `trades` +1) → NATS bridge →
  trade-processor → DB → `position-service` shows `NVDA +60`. Repeated for 22214 (AAPL/MSFT).
- **sessionAffinity split works**: `order-matcher-gw` affinity=None, 3 endpoints; a 30-order burst
  from ONE client IP spread 9/14/7 across the 3 gateways (combined-ClientIP had pinned all to one).
  `order-matcher-gw-fix` keeps FIX on ClientIP (18130).
- **seed job**: Complete 1/1, all 7 real accounts (hardened retry rode a gateway roll).
- **create-order during leader kill**: killed leader cluster-0 → cluster-1 promoted → 20/20 orders
  booked (`trades` 11024833→11024853, +20 exact, no silent rejects), position 44044 GS +500, cluster-0
  rejoined as follower. One promotion, no reuse.
- **Still open**: (a) literal browser screenshot of the Angular UI (backend chain proven; UI reads the
  same feeds); (b) deploy 3394186 for honest `422 + reason` — the 20/20-OK failover run is exactly why
  it matters: through the old fire-and-forget gateway a silent reject also reads as OK, only the
  `trades` counter revealed the truth. Needs the amd64 member+gateway rebuild (§4).

## 1. Apply

```bash
# Service split (REST non-sticky / FIX sticky), gateway replicas=3, seed-real-accounts Job.
# If a previous run of the job exists: kubectl -n traderx delete job seed-real-accounts
kubectl -n traderx apply -k specs/YU12-aeron-cluster/generation/kubernetes/cluster/gke/

# trade-service repoint + trade-processor recon backoff.
# !!! DO NOT `kubectl apply -f` the kubernetes-runtime/base manifests on GKE — they carry the
#     LOCAL dev image tag (traderx/trade-service:state009), which GKE cannot pull → ImagePullBackOff,
#     and the corrected env gets stranded on a Pending pod while the old pod keeps the OLD env.
#     The GKE deploy sets the registry image separately (…:state009-yu09-20260713). Patch env
#     surgically instead (a full clean redeploy goes through the YU02 gke prepare script, which
#     carries both the base-manifest env AND the registry image):
kubectl -n traderx set env deploy/trade-service   ORDER_MATCHER_URL=http://order-matcher-gw:18110
kubectl -n traderx set env deploy/trade-processor RECON_POLL_INTERVAL_MS=86400000
kubectl -n traderx rollout status deploy/trade-service deploy/trade-processor --timeout=180s
kubectl -n traderx set env deploy/trade-service --list | grep ORDER_MATCHER_URL   # expect order-matcher-gw
```

## 2. Seed + gateway-route check (bisects gateway-side vs trade-service-side)

```bash
kubectl -n traderx wait --for=condition=complete job/seed-real-accounts --timeout=180s
kubectl -n traderx logs job/seed-real-accounts   # expect "account <id> seeded" ×7

# Direct gateway /trades with a real account — bypasses the UI + trade-service entirely:
kubectl -n traderx run trades-check --rm -i --image=busybox:1.36 --restart=Never -- \
  wget -qO- --header='Content-Type: application/json' \
  --post-data='{"accountId":22214,"security":"AAPL","side":"Buy","quantity":50}' \
  http://order-matcher-gw:18110/trades
# With 3394186 deployed: expect {"booked":true}. A REJECT is now visible: 422 (wget prints
#   "server returned error") with {"booked":false,"reason":"ACCOUNT_DISABLED"|"PRICE_MISSING"|...}
#   — that means the seed job didn't take; re-check step 2's seed logs.
# Pre-3394186 image: booked answers {"sequenced":true} and a reject STILL lies with 200 — that's
#   the whole reason to deploy 3394186 before the demo.
# 404 here = running gateway image predates 35fd903 → rebuild+push (amd64!
#   docker build --platform linux/amd64), which is also the build that carries 3394186; then
#   rollout restart deploy/cluster-gateway.
```

Booked proof (bridge health = leader NATS **`in_msgs`**, never `out_msgs` — the bridge is
publish-only): re-read the leader's NATS connection `in_msgs` before/after the wget; it should
bump by 1, then the trade row lands in MariaDB and the account 22214 trade/position feeds update.

## 3. UI end to end

Open the web UI → account **22214** (or any of 10031, 11413, 42422, 44044, 52355, 62654 — real
SQL accounts only; anything else FK-fails in trade-processor) → create-order on a supported
ticker (AAPL…FNF) → expect the trade in the blotter and the position update, live. Screencap =
OSFF-1 acceptance artifact #1.

## 4. Create-order during leader kill

```bash
kubectl -n traderx delete pod <current-leader>   # find via the members' :8080/health role field
```

Immediately create-order in the UI. During the election window trade-service may 5xx (gateway
has no leader to sequence to) — retry until it books. Acceptance: order books after one
promotion, no orderRef/tradeSeq reuse. This is acceptance artifact #2.

## 5. REST scale-out across all 3 gateways

```bash
# accepted-order counter per gateway pod, before and after ~30 create-orders/curls:
for p in $(kubectl -n traderx get pods -l app=cluster-gateway -o name); do
  echo "$p: $(kubectl -n traderx exec ${p#pod/} -- wget -qO- localhost:18110/metrics | grep accepted)"
done
```

Drive a short burst through the Service (UI clicks or a curl loop — each new connection
re-balances now that affinity is gone) and confirm **all three** counters move. Bench numbers
through the Service should now approach the 64k direct-path ceiling (was 41k pinned).
Note: a single long-lived HTTP/1.1 keep-alive connection still sticks to one pod — that's
connection semantics, not affinity.

## Rollback

Everything is additive/idempotent: re-`apply` the previous manifests to revert. The FIX Service
move (`order-matcher-gw` no longer exposes 18130; use `order-matcher-gw-fix`) only affects FIX
clients addressing the Service by name — `kubectl port-forward` scripts target pods under the
hood and keep working.
