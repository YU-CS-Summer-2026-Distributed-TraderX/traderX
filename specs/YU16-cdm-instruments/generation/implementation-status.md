# Implementation Status: YU16-cdm-instruments

Status: implemented and verified on kind.

## What was built

| Area | Artifact |
|---|---|
| Spec pack | spec, plan, research (with per-id source-pack traceability), data model, contracts, subject map, topology, ADR-057 … ADR-060 |
| Reference data | `instruments/` module (CDM model, catalog with baked FIGIs + Treasury seeds + load-time assertions, service, controller) beside the retained stocks module; `/instruments/control-snapshot` over the same store and watermark |
| Pricing | `treasury-pricing.js` (term-profiled correlated walk, mean reversion, band clamp, YTM, maturity) and the `main.js` hooks; fraction-of-par emission at six-decimal tick precision |
| Schema | `database-init-configmap.yaml` at this layer: `Rejected` state + rejection columns, price columns widened to `DECIMAL(18,6)`, account-17017 seeds at fraction prices, explicit `MODIFY`s in `900-migrations.sql` |
| Post-trade | metadata client (fail closed, bounded timeouts), face-weighted Treasury average cost, `Rejected` landing with no position message, 6-dp price discipline through `Trade`/`TradeOrder`/`Position` |
| Order entry | gateway `UST-` face validation pre-consensus; trade-service `fetchInstrument` + Treasury order validation with `{detail}` errors |
| Engine | ADR-060 derived per-security book grid for `UST-` tickers (registration + `T_SYMBOL` restore); nothing stored, `SNAPSHOT_FORMAT` unchanged |
| Extract | schema 3 — `TREASURY` by static join, `coupon`/`maturityDate` columns (ADR-059), `lastCouponDate`/`accruedInterestFraction` by derivation (ADR-061), `risk.extract.ready` schema field, consumer guide updated |
| Frontend | asset-class filter, Treasury tickets (face/clean-price labels, client-side validation, clean value, coupon/maturity/YTM), percent display off the stored fraction, rejection column |
| Proofs | `yu16-treasury-pricing.sh`, `yu16-bond-position.sh`; the YU04 pair migrated to `/instruments/control-snapshot`; suite runner owns the price-publisher forward |

## Verification

| Proof | Result |
|---|---|
| order-matcher suite | **344 / 0** (YU15 carried 341; +3 YU16) |
| trade-processor suite | **80 / 0** (new `TreasuryBookingTest`, re-pinned booking/idempotency tests) |
| position-service suite | **11 / 0** |
| trade-service suite | **13 / 0** (five new Treasury validation cases) |
| reference-data (jest) | **16 / 0** across 4 suites (new `cdm-catalog.spec.ts`) |
| price-publisher (`node --test`) | **19 / 0** (new `treasury-pricing.test.js`) |
| Hot-path gates | `noGcTest`, `allocationGateTest`, `clusterAllocationGateTest`, `aeronAllocationGateTest` — all green |
| Angular | production build clean; karma A/B against a regenerated YU15 tree — **16 FAILED / 15 SUCCESS on both, identical failing sets** (pre-existing composed-tree debt, zero introduced) |
| Clean generation | `bash pipeline/generate-state.sh YU16-cdm-instruments` → **EXIT=0** |

### Live kind proof (`scripts/proofs/yu16-bond-position.sh`, all 5 steps)

| Step | Result |
|---|---|
| 0. preflight | price columns at scale 6/6 on the live database; no `UST-20310630` rows to start |
| 1. boundary rejection | face 50 → 422, face 150 → 422, and the members' applied sequence never moved (5004) — rejected pre-consensus |
| 2. the cross | 100,000 face at `0.996650` accepted on both sides through the unchanged engine |
| 3. SQL | 2 trade rows; price stored as `0.996650` — the fraction, asserted numerically against the percentage form |
| 4. position | buyer `100000 @ 0.996650 = $99,665.000000`, seller `-100000` — multiplier 1, no divisor |
| 5. negative control | the same position priced as a percentage is `$9,966,500`, which the step-4 assertion rejects |

### Live kind proof (`scripts/proofs/yu16-treasury-pricing.sh`, all 5 steps)

| Step | Result |
|---|---|
| 1. CDM record | `UST-20280630`: Debt / US_TREASURY, coupon 4.125%, matures 2028-06-30, no `BBGTICKER` |
| 2. fraction semantics | price `0.99861` inside the seed's ±0.15 fraction band, `CLEAN_FRACTION_OF_PAR`, `cleanPrice == price` |
| 3. precision | `0.99861` → `998610` ticks at 1e6, decimals intact (a 3dp-rounded bond would end in `000`) |
| 4. YTM | `4.202%`, publisher-computed, plausible for a 4.125% note near par |
| 5. no fabrication | unknown `UST-` → 404 **and** unknown equity → 200, so the 404 is the bond rule rather than a dead endpoint |

### Live kind proof (`scripts/proofs/yu15-risk-extract.sh` with a bond held, schema 2)

The delivered fixture's bond row, verbatim from the rig:

```
22214,UST-20360515,TREASURY,100000,1,0.992570,0.992620,EOD_SNAPSHOT,OK,99262.000000,5.000000,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01,4.375,2036-05-15
```

`TREASURY` by static join, 100,000 USD face, multiplier 1, cost and mark as fractions of par at
six decimals, `marketValue = face x fraction = 99,262.00` exactly, coupon and maturity joined,
and `markSource=EOD_SNAPSHOT` — the bond received a published close, which is the payload fix
working end to end. The header reads `# traderx-risk-extract schema=3` and carries the bond
convention lines. The proof's own byte-reproducibility and post-recovery re-render steps pass
with the row present.

### The roll

Images rebuilt from the YU16 tree and rolled onto the standing rig: `eod-price-db`,
`reference-data`, `price-publisher`, `trade-processor`, `position-service`, `cluster-gateway`,
then `order-matcher-cluster` (3/3, partitioned rollout). **PVCs intact, epoch unchanged, no
scale-to-zero** — the members came back carrying their prior state (`applied=5004`, 8 trades,
snapshots restored). `/stocks` returned 200 throughout; `/instruments` serves 519 records
including the five Treasuries.

### Proof suite

`scripts/yu15/run-proofs.sh`: **21 passed, 0 skipped, 0 failed** on a final untouched run,
including the two new YU16 proofs and the migrated YU04 pair.

Four runs, each failure understood before it was addressed:

| Run | Result | What it found |
|---|---|---|
| 1 | 19 / 2 | `yu15-risk-extract` — the additive-payload bug (typed consumer dropping every Treasury tick); `yu05-recon` — inherited lifetime counters |
| 2 | 21 / 0 | both fixes confirmed, but **not a valid artifact**: `run-proofs.sh` was edited while bash was executing it |
| 3 | 20 / 1 | `yu05-recon` again — traced to trade-id reuse across the fresh epochs the suite itself mints |
| 4 | **21 / 0** | clean, untouched, with the recon counter hygiene in place |

## Independent re-verification, 2026-08-13 (kind + GKE)

Re-run from a clean session rather than trusted from the record above. Three things the first pass
could not have seen, and a second rig.

### The rig was a build behind its own tree

The images the suite above was green against were built 2026-08-10 20:53. The extract's schema-3
accrual commit (`216a6b78`) landed 2026-08-12 20:43 and had **never been built or deployed**. Read
straight out of the running image's `RiskExtractCsv.class`, the header string ended at

```
...,counterpartyId,nettingSetId,coupon,maturityDate
```

— sixteen columns, schema 2. `yu15-risk-extract` passed anyway, exactly as this document's own note
predicts: it asserts reproducibility, not a version.

Rebuilt `cluster-node` from the tip (the only commit since touching buildable source), rolled it on
a **fresh epoch**, and re-ran everything. The extract now renders `schema=3` with the four bond
columns and the accrual convention header.

| run | build | result |
|---|---|---|
| 1 | 2026-08-10 images | 20 passed, 0 skipped, **1 failed** |
| 2 | tip, fresh epoch | **21 passed, 0 skipped, 0 failed** |
| 2 + new proofs | tip | 23 of 23 |

### The one failure was a proof reporting on the wrong cluster

`yu10-fix-session` failed with `ECONNREFUSED 127.0.0.1:18130` and printed *"0 completed"* and *"no
projection growth"* — verdicts about FIX ingress — against a kind rig that was fine. Its
`kubectl port-forward` was the last one in the repo with no `--context`, so it followed the
operator's ambient kubectl context, which was the GKE cluster (no `svc/order-matcher` there; it is
`order-matcher-gw`). Fixed, plus a TCP gate that refuses on a dead tunnel instead of blaming the
system. Same rig, same session, after the fix: **1147 completed lifecycles, 0 rejected, projection
grew by 1176**.

This is the same fault `db_orders()` in that file already carried a comment about — a kubectl call
without `--context` returning empty and the script reporting "no projection growth" regardless. The
port-forward was missed when that one was fixed.

### ETF and Treasury, end to end

Five ETFs quoted live (SPY 532.099, QQQ 444.884, IWM 204.829, VTI 264.184, GLD 217.957). SPY
crossed 50 at 532.100 through the engine into positions, and lands in the extract as `EQUITY` with
`EOD_SNAPSHOT` and four empty bond columns beside a `TREASURY` row carrying
`4.125, 2031-06-30, 2026-06-30, 0.004959`.

### On GKE (project traderx-505400, real hardware, one public IP)

All four images rebuilt `--platform linux/amd64` at tag `yu16`, plus **reference-data, which was
never on that tier** — trade-processor's Treasury path fails closed without it, so the tier as it
stood would have rejected every bond at post-trade while the engine accepted it and the gateway
returned 200.

| proof | result |
|---|---|
| `yu16-bond-position` | PASS |
| `yu16-book-grid` | PASS (incl. the T_SYMBOL restore path) |
| `yu16-treasury-pricing` | PASS |
| `yu12-gke-cross-epoch-idreuse` | PASS |
| `yu12-gke-recovery` | PASS |
| `yu13-gke-replace-proof` | PASS |
| `yu12-gke-failover-transparency` | **FAIL — a real defect**, see below |

A six-decimal Treasury limit was accepted through the public load balancer with all three members
agreeing, which is ADR-060 on hardware rather than on a laptop.

The failover failure is `issues/open/HANDOFF-issue-gateway-wedges-after-leader-kill.md`: after a leader
kill the single gateway never recovers its cluster session and returns 504 forever, while `/ready`
and `/health` both report `connected:true`, `restarts=0`, and the log says nothing. Reproduced on
YU15 `:bench` the day before, so it is not YU16's. The proof that found it blamed consensus
transparency, which was fine — right finding, wrong subsystem.

## Notes for the next lane

- **The book grid was the trap, not the multiplier.** The fraction-of-par convention solves the
  risk gate's `long` multiplier, but the YU13 book *rejects off-grid limits* and its 0.001 grid
  cannot hold a six-decimal fraction. Every bond order was refused before matching until ADR-060.
  If another asset class arrives with sub-0.001 price granularity, this is the first place to look.
- **`Position.setAverageCostBasis` silently rounded to 3dp.** The entity setter, not the column,
  was the last 100×-class rounding on the path. When widening a price anywhere, grep the *setters*
  as well as the DDL.
- **`eod-price-db` HAS a PVC as of 2026-08-18** — it did not before, and the difference is the
  whole read model. Its only volume was the init ConfigMap, so the datadir lived in the pod's
  writable layer and every restart re-ran `001-initialSchema.sql`, which opens with
  `DROP TABLE IF EXISTS` on trades, orderbook, positions, accounts and all three `eod_*` tables.
  A restart was a wipe, not a reload. That destroyed evidence mid-investigation, evaporated
  `POST /stocks` repairs, and silently invalidated any before/after measurement spanning it.
  Now: `eod-price-db-data` (10Gi RWO) at `/var/lib/mysql`, `strategy: Recreate`, and a
  `schema-migrate` initContainer — because the entrypoint runs init SQL *only* on an empty
  datadir, which is exactly what makes `001` safe now and exactly what would have stranded
  `900-migrations.sql`. The populated-volume path is therefore exercised by every ordinary roll
  on this rig, not just by `yu15-option-persistence`.
- **The member health endpoint is port 8080, not 18110.** 18110 is the gateway's REST port; a
  proof asking it for `applied` gets a JSON body with no such field. The first version of
  `yu16-bond-position` did exactly that and refused loudly rather than passing — which is the
  behaviour the vacuous-pass rules are for.
- **A proof can pass against the wrong build and never say so.** `yu15-risk-extract` passed
  while `risk-extract` ran a six-day-old image, and the fixture it delivered read `schema=1`
  with the old 14-column header — the proof asserts reproducibility, not a version, so it could
  not tell, and YU16's renderer had never executed on the rig. The runner now pins the producer
  by comparing the pod's start time against the local image's build time (an image-id comparison
  cannot work: kind re-imports under its own digest, so it would fire every run).
- **`yu05-recon` reads LIFETIME counters, not per-run ones.** Its forward-sweep
  matched/missing/field_mismatch are LongAdders counting classification *events* since
  trade-processor started, so its verdict depends on everything that ran before it — and a suite
  that mints fresh epochs restarts trade ids from 1, which collide with projection rows the
  previous epoch left behind. Its authoritative full-history sweep was clean in every run,
  including the failing ones. The runner now restarts trade-processor before it.
- **Do not edit a script while bash is executing it.** Editing `run-proofs.sh` mid-suite made
  bash re-read from a shifted offset and die with a syntax error during teardown, after all 21
  proofs had already reported. Harmless here, but it makes the run a non-artifact.
- **The YU04 pair passes on the general route** (`/instruments/control-snapshot`) on this rig.
  The suite readiness gate deliberately still probes `/stocks/control-snapshot`, which is what
  makes retention a standing regression check rather than a claim in a spec.
