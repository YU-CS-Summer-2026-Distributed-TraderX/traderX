# Tasks: YU16-cdm-instruments

- [x] T-CDM01 — Author the combined pack: spec folding the two source packs with ids remapped,
      the `/stocks` inversion declared by source id, the four requirement families
      (compatibility, contract supersession, integration, non-regression), traceability table,
      ADR-057/058/059.
- [ ] T-CDM02 — Register the state: pipeline hook + render scripts, catalog entry, runtime
      harness + CI-assets installer cases, start/stop/status/test wrappers; clean generation
      exits 0.
- [ ] T-CDM03 — reference-data: instruments module (model, controller, service, loader with CDM
      assertions and classification map), `instruments.csv` seed with FIGI/securityType and the
      ten new rows, supplemental FIGIs, `/instruments/control-snapshot` beside the retained
      stocks module — all based on the YU04-operative copies.
- [ ] T-CDM04 — price-publisher: `treasury-pricing.js`, snapshot seeds, `main.js` hooks (shared
      roll, maturity suppression, `UST-` 404, fixed clock), fraction-of-par emission at 6-dp
      tick precision — based on the YU15-operative copies; publisher tests.
- [ ] T-CDM05 — Schema: `database-init-configmap.yaml` at this layer from the YU15-operative
      copy — `Rejected` state, rejection columns, `DECIMAL(18,6)` price widening, account-17017
      seeds with fraction prices.
- [ ] T-CDM06 — Post-trade merge: trade-processor (`InstrumentMetadata`, metadata client,
      `TradeState.Rejected`, rejection fields, face-weighted average cost onto the YU05-operative
      `TradeService`, `RuntimeConfig`), position-service (`Trade`, `TradeRepository`),
      trade-service controller validation onto the YU02-operative copy; suites green.
- [ ] T-CDM07 — Gateway: `UST-` face validation (≥100, ×100, exact messages) on the
      YU13-operative `ClusterGatewayMain`, rejecting before submission; engine files untouched.
- [ ] T-CDM08 — Frontend: asset-class filter, grouped selectors, Treasury labels/validation,
      clean-value estimation, coupon/maturity/YTM, percent display off the stored fraction,
      rejected-trade display — rebased onto the operative frontend copies (YU03/014).
- [ ] T-CDM09 — Extract: static-join classification (`TREASURY`), coupon/maturity columns, CSV
      schema 2, `risk.extract.ready` schema field, consumer guide updated.
- [ ] T-CDM10 — Proofs: migrate `yu04-live-delta` + `yu04-offline-catchup` to the general
      snapshot route; add the Treasury pricing proof and the bond position-math proof; full
      suite green on the standing rig (no fresh epoch, no PVC wipe); `SNAPSHOT_FORMAT` still 4.
- [ ] T-CDM11 — implementation-status.md written with verification evidence; state docs synced
      (root CLAUDE.md worktree map, specs/README.md, catalog).
