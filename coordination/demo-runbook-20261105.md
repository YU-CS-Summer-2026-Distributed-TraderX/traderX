# TraderX live demo — runbook (Thursday 2026-11-05, 15 minutes)

Checkout `~/dev/lmax/traderX-risk-integration` at `eb2e8a1c` or later. Every command below runs from there with:

```bash
cd ~/dev/lmax/traderX-risk-integration
export CLOUDSDK_ACTIVE_CONFIG_NAME=hxb-bench     # the default gcloud config's auth has expired
```

Sites: Demo console https://demo.yaakovseif.dev/ · Desk https://desk.yaakovseif.dev/ (both take the operator password).
Times are US Eastern.

## 0. Before Thursday (once, needs your go-ahead)

| What | How | Time |
|---|---|---|
| ~~Fresh epoch~~ **DONE 2026-11-01 4:17–4:37 pm ET**: e1101 archived (snapshots `traderx-e1101b-m{0,1,2}`, read model in `arc_e1101b_*`), now on clean epoch e1101b. **Run no load on it before the demo** — any scale-out load leaves a read-model backlog that the next `up`'s G3 refuses | — | — |
| **Allowlist your network** — the HTTPS front door (demo/desk) and the FIX entry admit listed /32s only; 2026-11-01 your IP had changed and both answered 403 / unreachable | `curl -4 ifconfig.me`, then your access renders (`render-frontend-access.py`, `render-fix-tls-access.py --address 35.196.31.22 --source <ip>`) | 5 min |
| Re-project cost | ≈ $5–7/h at 3 gateways + 2 loadgen nodes; each extra gateway node ≈ +$0.40/h. A 1 h rehearsal + 1 h demo ≈ $15 | — |
| ~~Admit the new images~~ **DONE 2026-11-02 12:45 pm ET**: `tip-60544a6e-j21` admitted (12/12 identical, 20 controls): Desk Kdb tab (FR-CUI-27), Level 1/2 order book on Markets (FR-CUI-25), priced-only toggle (FR-CUI-26), Desk OTC contracts, Kdb on `traderx-kdb`. Console + Desk rolled live; **at the next scale-down apply `/private/tmp/claude-501/roll1101/cand7/no-sts.yaml` + `set image sts` from pushed7.tsv so members/gateways match** (label-only difference) | — | — |
| ~~KDB-X for step 4~~ **DONE**: VM `traderx-kdb` runs `demo-hdb.q` as `traderx-kdb.service` (5,000,000 trades); `rig.sh up`/`down` start and stop it; console `KDB_Q_URL` = its internal name. Check at bring-up: Kdb › How many trades answers | — | — |
| Rehearse once end to end | this runbook | 1 h |

## 1. Bring up the rig — start at T−45 min (~25–30 min)

```bash
GATEWAYS=3 AUTOSCALE=on HPA_CPU_TARGET=30 LOADGEN=2 scripts/gcp-compsci/rig/rig.sh up
```

In order: nodes (default 3, engine 3, nats 1, gateway 3; loadgen 2 starts with them) → support workloads → members
and the restore gate → 3 gateways → internal LB → (fresh epoch only) accounts and reference data → **business day =
today's ET date** (`BUSINESS-DAY 200 … APPLIED`, or `STALE` on a rerun; DAY orders are admitted from here) → HPA
3..18 on CPU at a 30% target (FR-PRG-33) and the gateway-pool autoscaler 3..18 → FIX smoke → **8 idle load pods Ready**.

Done when it prints `rig up: done`. Check with `scripts/gcp-compsci/rig/rig.sh status`: HPA `cluster-gateway 3/18`.

## 2. Two desks trade and match (~4 min)

1. Window A, Desk: sign in **alice** with the operator password, `+ Add account` → "Alice equities" (created and admitted).
   Window B, a private window: **bob**, `+ Add account` → "Bob equities". Both show `Run: Active legacy-unknown`.
2. A › Orders: **MSFT, Sell 100, Limit, DAY**, Price at market → `Order accepted · N`, working.
   B › Orders: **MSFT, Buy 100, Market, IOC** → filled against A. Both blotters show the trade at once.
3. More types and instruments (both tickets offer all seven: Market, Limit, Stop, Stop limit, Iceberg, Pegged, Trailing stop):
   - **AAPL**: A rests an **Iceberg** sell (qty 100, display 10, GTC); B lifts it with a **Limit IOC** buy for 30.
   - **Stop**: B sends a Stop buy above the last AAPL trade → `PENDING_TRIGGER`; a print through it fires it.
   - **FOK** too large → `Refused: the book could not fill the whole quantity now; nothing traded (FOK_UNFILLABLE)`.
   - **Pegged** needs a bid and an offer on the book; **Trailing stop** needs a last trade (both say so if not).
4. Demo console › Trading: the same orders appear; send one more from its ticket (e.g. the "Equity — buy 100 IBM @ 200" story).
5. **OTC**: Demo console › Trading › Demo preset "Swap — USD SOFR, pay fixed 4.2%, 10mm 5Y" › Submit →
   `SW-n booked at consensus seq n`; it appears under Blotter › OTC contracts (also "Swaption — European into 5Y USD payer").
   On the Desk, sign in to the same account: Positions › Booked swaps and swaptions lists it (read from the engine).

## 3. Positions after the trades (~1 min)

Desk › Positions in each window: alice −100 MSFT, bob +100 MSFT at the fill price, with their fills.
Demo console › Trading › Blotter & positions (pick the account) shows the same.

## 4. Millions of trades, queried live in q (~3 min)

Demo console › **Kdb** › "KDB-X history · live q". Click in order: How many trades (5,000,000 over 261 days) →
Volume and VWAP by symbol, whole year (~0.1 s on 5M rows) → 5-minute AAPL bars → Ten largest trades.
Then type your own (signed in; ⌘↵ runs it):

```q
select vwap:qty wavg px, volume:sum qty by date from trades where sym=`MSFT, date>=last[date]-20
```

A write or a system command is refused by the q process itself (`noupdate`, `sys`).

## 5. Scale the gateways live (~4 min)

1. Demo console › **System**: the "Gateways" card shows `3 ready · steady`.
2. Terminal:

   ```bash
   scripts/gcp-compsci/rig/loadgen.sh start 230000 120
   ```

   Returns in ~15–20 s with `every pod is sending` (8 pods × 16 sessions, MSFT at the members' mark, 2 min).
   **Keep it short and keep this step last**: the read model projects ~1,100 rows/s, so every second of load is a
   backlog the Desk and console positions wait behind (production 2026-11-01: 15 min at ~218K/s overflowed the
   20 GiB feed and the epoch's read model lost the load's trades).
3. `kubectl --context gke_project-compsci-484414_us-east1-b_traderx-demo -n traderx get hpa cluster-gateway -w`:
   at ~230K/s three gateways read ~43% against the 30% target and the HPA asks for 5. Production 2026-11-01:
   3 → 5 gateways in ~65 s (nodes ~30 s); the card shows `+2 starting`, then `5 ready` and logs `3 → 5 scaled out`.
   The load's sessions stay on the first three gateways (long-lived TCP); new sessions land on the new ones.
4. `scripts/gcp-compsci/rig/loadgen.sh results` when it ends (`accepted`, `rejected 0`, `imbalance 0` per pod);
   `loadgen.sh stop` cuts it short. Scale-in is slow on purpose (30 min of low CPU, then one gateway per 10 min).

## 2b. FIX: from the FIX tab, and from your Mac on a fresh account (~3 min)

1. Desk (any window) › `+ Add account` → e.g. "Fresh FIX account" → note its id (`Account 6500N created and admitted`).
2. Demo console › **FIX** tab: Account = that fresh account, MSFT, Sell, 5, Price at market › **Send over FIX** →
   the panel shows Logon, NewOrderSingle and the ExecutionReport (New) on the wire (CLIENT1 → TRADERX).
3. Terminal on your Mac (FIX 4.4 over TLS to `35.196.31.22:18143` as `EXTQFJ`, any account, FR-FTL-07):

   ```bash
   FIX_HOST=35.196.31.22 scripts/fix-tls/fix-order.sh <fresh id> Buy 5 MSFT
   ```

   `[ok] logged on over TLS as EXTQFJ` → `[report] … status=Filled filled=5 lastPx=…` (Market IOC against the FIX-tab
   sell). With a price it is a Limit DAY that rests: `fix-order.sh <id> Buy 10 MSFT 380`.
4. Desk › Positions on that account: the fills, from both orders.

Before Thursday check your public IPv4 (`curl -4 ifconfig.me`) is on the entry's allowlist
(`kubectl -n traderx get svc fix-tls-external -o jsonpath='{.spec.loadBalancerSourceRanges}'`); if not, re-render it
with your address(es) — that is your command to run:

```bash
python3 scripts/gcp-compsci/render-fix-tls-access.py --address 35.196.31.22 --source <your IPv4> | kubectl --context gke_project-compsci-484414_us-east1-b_traderx-demo apply -f -
```

## 6. After the session (mandatory)

```bash
scripts/gcp-compsci/rig/rig.sh down
gcloud compute instances list --project project-compsci-484414
```

`down` removes the load pods, drains the gateways, takes a snapshot, stops the members (each exits 143), sets every
pool to 0 and stops the client VM. The listing must show no rig instance running.

After the demo: the load's trades are not all in the read model, so the next `up` needs another fresh epoch.

## Verified in production (2026-11-01, 12:55 pm – 1:35 pm ET, images tip-fe4949c5-j21)

- `rig.sh up` in 6 min: restore gate G0–G4 incl. G2's shadow replay (FR-ERH-F16), epoch initialized, business day
  APPLIED, HPA 3..18, smoke, 8 idle load pods.
- `loadgen.sh start`: sending in 15–20 s; 196,348,832 orders sent and accepted, 0 rejected, 0 unanswered,
  imbalance 0 (~218K/s for 15 min); the book back to empty.
- HPA at 60%: never scaled (gateways at 38–45%); at 30%: 3 → 5 in ~65 s; System tab showed `5 ready`.
- Mac FIX CLI over TLS as EXTQFJ (via a port-forward; your IP was not on the allowlist): Limit DAY sell New, Market
  IOC buy crossed it (book empty after).
- Not walked in production: desk/console clicks (the front door's allowlist and the operator password are yours),
  Kdb (no KDB-X host yet), positions after trades (the read model was behind the load).

## Rehearsal on the fresh epoch (2026-11-01, 4:25–4:44 pm ET)

- `up` on the empty epoch: read model archived, G0/G3/G4 fresh-epoch rules, initialized, business day APPLIED, HPA at
  30%, smoke, 8 idle load pods; ~12 min (NATS took 8 min to come Ready).
- Mac FIX CLI (EXTQFJ over TLS, via a port-forward): MSFT 42422 Limit DAY sell 10 @ 392.13 ← 22214 Market IOC buy,
  Filled; AAPL 22214 Limit DAY sell 20 @ 245.24 ← 42422 Market buy 15, Filled; a resting AAPL bid. Positions in the
  read model: 22214 MSFT +10 / AAPL −15, 42422 the reverse. No load run (see above).
- `down`: barrier, baseline 2914 applied / 4 trade sides, members exit 143, 0 instances.

## Verified locally (kind, 2026-11-01)

- Business day: DAY order `NO_TRADING_DAY` before, accepted after; `business-day.mjs` APPLIED then STALE.
- All seven order types through the console path: `order-types-live.py` 31/31, exit 0.
- Two desks (alice 65000, bob 65001): DAY sell + Market IOC buy matched; positions −100 / +100.
- OTC swap booked (`SW-6830`); console positions live.
- Kdb: 5,000,000 rows, presets 0.05–90 ms in q; free-form q needs sign-in.
- System card: `1 → 2 scaled out`, `2 → 1 scaled in` as the Deployment changed.
- `loadgen.sh`: idle pods, `start` in 13 s, 63,088 accepted / 0 rejected / imbalance 0; with no binary ingress it
  refuses at 25 s and stops the load.
- Not verified locally: the HPA crossing 60% CPU (kind has no gateway-pool or GKE metrics), so read TARGETS in step 5.
