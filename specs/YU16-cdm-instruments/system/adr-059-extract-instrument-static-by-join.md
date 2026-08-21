# ADR-059: Instrument static reaches the risk extract by join, and the CSV schema bumps to 2

Status: Accepted

## Context

The risk-engine consumer needs to know a row is a Treasury and needs its coupon and maturity to
price it. The extract's `.cut` is the replicated state machine's own state at a consensus
sequence — quantities, cost ticks, multipliers, last trades — and coupon/maturity are not
replicated state. Carrying them in the cut would mean feeding reference data through consensus
so the engine could hand it back. The extract already solved this shape for `counterpartyId`:
the producer joins the cut with immutable reference data at render time, and byte-reproducibility
holds because the fixture is a pure function of cut + static (YU15 FR-RXT10).

## Decision

`instrumentType` gains `TREASURY`, derived by joining the cut's `security` against the state's
instrument static (`reference-data/instruments.csv`, which gains `securityType`, `figi`,
`couponRatePercent` and `maturityDate` columns) — never by prefix-parsing inside the cut render.
Treasury rows carry `coupon` and `maturityDate` from the same join, appended after
`nettingSetId`. The delivered CSV header becomes `# traderx-risk-extract schema=2` and
`risk.extract.ready` announces `schema: 2`. The `.cut` sidecar format does not change
(`#cut schema=1`): the cut is engine output and the engine is unchanged.

## Consequences

- Reproducibility is preserved: rebuild-from-stored-cut still yields identical bytes, since the
  join input is the same immutable static that already feeds counterparty attribution.
- The consumer sees one schema identifier change and two appended columns; every schema-1
  column keeps its name, position and meaning. `docs/engineering/risk-extract-consumer-guide.md`
  is updated in the same change — it currently tells the consumer `schema=1`.
- Option identity remains derived from OCC symbol shape; Treasury identity comes from the
  static, so a malformed static row aborts the extract (the inherited unmappable-row rule)
  rather than misclassifying.
- Bond `costBasis`/`closingMark`/`marketValue` render as fractions of par at scale 6 (ADR-057);
  the guide states the convention so the consumer's ×100 is deliberate.
