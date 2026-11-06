# Every instrument class, and large books: results, November 6

Engine: Alex's upstream `17ac757e8ffe9b180fa6e98ca6114c474ab0e6ad` (main, October 29), unmodified. It was archived from
the clean checkout into a private venv (Python 3.14.6, JAX 0.10.2, engine `constraints.txt`) and run as
`uvicorn engine.api.app:app` on local CPU (macOS arm64, 11 cores, 18 GiB, shared with three other sessions'
builds). All prices are on the proof's synthetic flat market. This is `financial_validation=false`: it
proves the path and the engine's agreement with ORE, not market numbers.
Specs: FR-RP-VARES-03..05 and FR-RP-BOOK-01..04 in
[risk-pipeline/spec.md](../../specs/YU18-risk-integration/components/risk-pipeline/spec.md).
Engine requests: [alex-engine-requests-20261106.md](../../coordination/alex-engine-requests-20261106.md).

## 1. VaR/ES end to end on every class

`scripts/proofs/yu18-var-es-e2e.sh`: three members, a child-JVM gateway, real ingress for every class, the
sequenced cut, the production exporters, and one `POST /runs` with `npv` + `market_risk` (1,024 Monte Carlo
scenarios, 10-day horizon).

| Class | Rows | Result | Owner |
|---|---|---|---|
| Treasury bill | 2 | priced: VaR99 374 / 372, ES97.5 388 / 373 | |
| Coupon Treasury note (new) | 2 | priced: NPV ±1,024,776 dirty, VaR99 16,322 / 16,614 | TraderX fix: extract's own schedule |
| GBP-SONIA swap | 1 | priced: VaR99 109,925 | |
| GBP swaptions, European / Bermudan / American (new) | 3 | priced: NPV 120,449 / 156,485 / 156,443, VaR99 76,248 / 73,535 / 73,554 | TraderX fix: booked and mapped |
| Listed equity | 2 | refused | engine R1 |
| Listed equity option | 2 | refused | engine R2 |
| Corporate bond (new) | 2 | refused | engine R4 |
| USD-SOFR swap and swaption | 2 | refused | engine R3 (ACT/360, I-05) |

- **Portfolio.** VaR99 152,408 and ES97.5 151,556, finite and non-zero. The VaR recomputed from the inline
  P&L columns equals the engine's.
- **Negative control.** The market without the swap's index curve is still refused with a 400 before any run
  exists.
- **Verdict: FAILED, by design.** All eight remaining refusals are engine gaps with a named request. No refusal
  is TraderX's own.
- **Parity.** `ore_parity.py` checked every priced trade against an independent reference:
  - bonds against the engine's own ORE oracle;
  - the swap and the European against QuantLib on the engine's own schedule;
  - the largest difference is 1.5e-8 absolute (1.6e-13 relative).
  - European ≤ Bermudan holds, but the American comes out 41.66 below the Bermudan on identical terms
    (0.027%, R5, an observation).

TraderX-side fixes in this change:
- coupon-note schedule;
- swaption booking and mapping;
- Bermudan/American calibration on the trade's own 12M index (the engine defaults to 6M);
- the base currency is always in the market (a GBP-only book was refused for a `GBPUSD` spot);
- the idempotency key hashes the whole request (a config change got a 409);
- the proof's test-count check (the class now has two tests).

## 2. Large books

1. `book_gen.py --seed 20261106 --accounts 64 --crosses 6000 --otc 4000` writes a deterministic plan. Its
   `planSha256` is `43e9da9f…3d3237`. It holds 40 equities, 10 ETFs, 79 OCC options, 30 Treasuries,
   16 corporates and 4,000 OTC contracts, spread over all five conventions and the three styles, across
   64 accounts on 8 desks.
2. `VarEsEndToEndTest.largeBookThroughTheRealIngress` booked it through the real gateway:
   - 6,000 binary crosses in 136 s and 4,000 REST OTC bookings in 62 s, on a loaded machine;
   - cut at seq 16,706, identical on three members, 548 KB;
   - 7,364 position rows and 4,000 contracts.
3. `large_book.py` priced it as one run.

| Run (256 scenarios unless noted) | Priced trades | Engine s | Peak RSS (API + worker) |
|---|---|---|---|
| Genuine book, npv only | 2,864 | 58.1 | (not sampled) |
| Genuine book, npv + market_risk | 2,864 | **1,496** | 2.8 GiB |
| Bonds only | 100 / 1,000 | 10.9 / 8.6 | 0.6 / 0.3 GiB |
| Swaps only | 100 / 1,000 | 13.6 / 5.8 | 0.4 / 0.2 GiB |
| Europeans only | 100 | 8.1 | 0.7 GiB |
| Bermudans only | 10 / 40 | 64 / 90 | 2.1 / 1.8 GiB |
| Americans only | 10 | 362 | 1.8 GiB |
| Linear (bonds, swaps, Europeans) tiled ×10 | 26,220 | did not finish: still compiling at 22 min, cancelled | ~0.1 GiB |

- **Genuine book, market_risk.** VaR99 36.18mm, ES99 43.38mm, VaR97.5 32.89mm, ES97.5 37.34mm, every
  trade NPV finite. Refused by owner:
  - equity R1: 2,113;
  - option R2: 3,330;
  - corporate R4: 687;
  - ACT/360 R3: 2,370 (1,530 swaps, 840 swaptions).
- **Correctness.** `ore_parity.py --sample 300` on the npv run checked bonds (oracle), swaps and Europeans
  (QuantLib). All 300 agree, max 2.2e-7 absolute (1.1e-12 relative). The 27 Bermudans and Americans in the
  sample have no ORE reference at 12M and no same-terms European in a generated book.
- **Where the time goes.** Americans cost ~30 s each and Bermudans ~1 s each, plus ~55 s of compile. The 45
  Americans account for most of the 1,496 s. Linear products cost milliseconds per trade.

## 3. Where it breaks

TraderX, one epoch:
- **Epoch caps.** 64 accounts, 1,024 securities, 4,096 contracts (`MatchingEngineClusteredService`).
- **Notional.** Stored as an `int`, refused past 2,147,483,647, so a JPY swap tops out near ¥2.1bn (~$14mm).
- **Order size.** `MAX_ORDER_QUANTITY` is 1,000,000 units an order, and debt books face dollars, so one
  bond order is at most $1mm face.
- **Position limit.** Defaults to the same 1,000,000. The large-book test raises it through the sequenced
  policy control.
- **Book memory.** Each traded security's `LimitBook` preallocates 2^17 levels, about 5 MiB per member, so
  the 1,024-security cap is ~5 GiB of heap per member. The GKE overlay's `-Xmx12g` covers it. The in-process
  test's 2 GiB did not (OOM after 74 crosses), so the large-book run needs `_JAVA_OPTIONS=-Xmx7g`.
- **Booking rate.** ~44 crosses/s on the synchronous binary client under load, and ~65 OTC bookings/s
  over REST.

Engine, local CPU:
- **Calibrated options.** Americans (~30 s each at 256 scenarios) and Bermudans dominate. A book of a few
  hundred calibrated options is a 20–30 minute run on this machine.
- **Compile time grows with portfolio size (R6, measured).** `revaluation.py:74` joins per-trade results
  with an eager `jnp.stack` over every trade. Its XLA CPU compile alone takes 2.5 s at 1,000 operands,
  9.9 s at 2,000, 47 s at 4,000 and 280 s at 8,000, roughly n^2.3 (the cached call is milliseconds).
  The ×10 tile (26,220 linear trades) was still compiling on one core, ~100 MB resident, after 22 minutes,
  so it was cancelled and the engine restarted. Locally the practical ceiling is a few thousand trades per
  run until the stack moves to the host. Above that, size is limited by compile time, not memory.
## 4. Cloud projection (not launched)

Prices are list prices from third-party aggregators, checked November 6:
- c4d-standard-32 is about $1.49–1.62/h on demand and $0.52–0.65/h spot;
- n2d-standard-32 in us-east1 is $1.35/h on demand and $0.30/h spot;
- g2-standard-8 (1× L4) is $0.85/h;
- one H100 is about $11/h, estimated from a3-highgpu-8g at $88.49/h.

Confirm in the GCP calculator before any launch.

| Proposed run | Basis | Hours | Projected |
|---|---|---|---|
| Genuine book, 1,024 scenarios, c4d-standard-32 on demand | 4× the 256-scenario run, assuming no CPU speedup over this Mac | ~1.7 | ~$3 |
| Same on g2-standard-8 (L4), to measure the GPU speedup | same, capped | ≤ 2 | ≤ $2 |
| Tiling ladder ×2 / ×4 / ×10 on both shapes, each run capped at 2 h | the compile growth makes ×10 unbounded, so the cap is the cost | ≤ 16 | ≤ $30 |
| Image build (Cloud Build), disk, egress | | | ≤ $10 |
| **Bounded total** | every VM with `--max-run-duration` and spot where possible | | **≤ ~$45** |

That is under 2% of the ~$1.2K left under the $3,500 cap. I recommend holding even this until Alex answers
R6. While compile time grows with trade count, a bigger machine does not make a 26k-trade book finish: the
compile runs on one CPU core whatever accelerator is attached. Nothing has been launched; a launch needs
yaakov's yes.

## Reproduce

```bash
# engine at 17ac757, private venv, port 8766 (see local-cpu-loop.md for the venv recipe)
RISK_ENGINE_ORIGIN=http://127.0.0.1:8766 scripts/proofs/yu18-var-es-e2e.sh "$(mktemp -d /private/tmp/var-es.XXXXXX)/out"
T=specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools
python3 $T/book_gen.py --seed 20261106 --accounts 64 --crosses 6000 --otc 4000 --out /private/tmp/plan.json
(cd generated/code/target-generated/order-matcher && _JAVA_OPTIONS=-Xmx7g TRADERX_LARGE_BOOK_PLAN=/private/tmp/plan.json \
  TRADERX_LARGE_BOOK_OUTPUT=/private/tmp/book ./gradlew --offline test -x riskAllocationGateTest --tests '*largeBook*')
python3 $T/large_book.py --artifacts /private/tmp/book --origin http://127.0.0.1:8766 --evidence /private/tmp/measure
<engine venv>/bin/python -I $T/ore_parity.py --engine-source <archived engine> --request /private/tmp/measure/request.json \
  --reply /private/tmp/measure/reply.json --sample 300
```
