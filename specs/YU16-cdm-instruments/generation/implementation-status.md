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
| Extract | schema 2 — `TREASURY` by static join, `coupon`/`maturityDate` columns, `risk.extract.ready` schema field, consumer guide updated |
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

### The roll

Images rebuilt from the YU16 tree and rolled onto the standing rig: `eod-price-db`,
`reference-data`, `price-publisher`, `trade-processor`, `position-service`, `cluster-gateway`,
then `order-matcher-cluster` (3/3, partitioned rollout). **PVCs intact, epoch unchanged, no
scale-to-zero** — the members came back carrying their prior state (`applied=5004`, 8 trades,
snapshots restored). `/stocks` returned 200 throughout; `/instruments` serves 519 records
including the five Treasuries.

## Notes for the next lane

- **The book grid was the trap, not the multiplier.** The fraction-of-par convention solves the
  risk gate's `long` multiplier, but the YU13 book *rejects off-grid limits* and its 0.001 grid
  cannot hold a six-decimal fraction. Every bond order was refused before matching until ADR-060.
  If another asset class arrives with sub-0.001 price granularity, this is the first place to look.
- **`Position.setAverageCostBasis` silently rounded to 3dp.** The entity setter, not the column,
  was the last 100×-class rounding on the path. When widening a price anywhere, grep the *setters*
  as well as the DDL.
- **`eod-price-db` has no PVC** — its datadir is ephemeral and only the init ConfigMap is mounted,
  so restarting it rebuilds the read model from `001-initialSchema.sql`. The `900-migrations.sql`
  populated-volume path is therefore *not* exercised by an ordinary roll on this rig;
  `yu15-option-persistence` is the proof that does exercise it.
- **The member health endpoint is port 8080, not 18110.** 18110 is the gateway's REST port; a
  proof asking it for `applied` gets a JSON body with no such field. The first version of
  `yu16-bond-position` did exactly that and refused loudly rather than passing — which is the
  behaviour the vacuous-pass rules are for.
- **The YU04 pair still SKIPs on this tier** (exit 2) because `CONTROL_FEED_SUBSCRIBER` is off by
  default; that is inherited, not caused by the route migration. What YU16 changes is *which*
  route they probe when they do run, and the suite readiness gate deliberately still probes
  `/stocks/control-snapshot` so retention is a standing check.
