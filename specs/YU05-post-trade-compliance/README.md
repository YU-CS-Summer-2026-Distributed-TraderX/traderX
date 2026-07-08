# YU05-post-trade-compliance

Bundled production-realism state combining four back-office/compliance capabilities that all
consume the journal's executed-fill stream as their common source of truth: post-trade settlement
+ reconciliation, regulatory reporting (CAT/TRACE-style), TCA (Transaction Cost Analysis), and real
auth/entitlements (OIDC) as the access-control layer gating the other three.

- **Parent state:** `YU03-in-memory-risk-gateway`
- **Design baseline:** ADR-022 (deterministic trade identity + settlement/reconciliation),
  ADR-023 (journal-sourced regulatory reporting), ADR-024 (pluggable TCA benchmarks),
  ADR-025 (OIDC entitlements gating post-trade APIs).
- **Read first:** `spec.md` (scope + why these four are bundled), `requirements/*.md`
  (per-requirement status), `generation/implementation-status.md` (what is done vs deferred).

Generate:

```bash
bash pipeline/generate-state.sh YU05-post-trade-compliance
(cd generated/code/target-generated/order-matcher && ./gradlew test)
(cd generated/code/target-generated/trade-processor && ./gradlew test)
```

Slice 1 (this commit) implements settlement + reconciliation only: a deterministic trade identity
threaded from the BLP's journal through to the MariaDB projection, a replay-safe in-memory trade
blotter in order-matcher, a settlement state machine in trade-processor, and a forward-looking
reconciliation comparator. Regulatory reporting, TCA, and real auth are specified (requirements,
data model, ADRs) but deferred to later commits of this same state — see
`generation/implementation-status.md`.
