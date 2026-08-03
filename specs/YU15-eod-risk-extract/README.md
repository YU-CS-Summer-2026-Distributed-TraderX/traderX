# Feature Pack YU15: EOD Risk Extract

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: In implementation — see generation/implementation-status.md
Track: `architecture`
Lineage role: `optional`
Previous state: `YU14-listed-equity-options`

This pack produces the end-of-day risk extract: the portfolio fixture an external pricing and risk
engine prices and computes VaR from. It is a snapshot pulled on demand, not a stream. The
overnight P&L chain's completion event triggers it; a sequenced marker through the consensus log
names the sequence it is cut at; every cluster member renders the identical position cut at that
sequence and the leader publishes it; the producer joins that cut with the published closing prices
and the counterparty reference data into one immutable CSV, written write-once and announced on
NATS.

The property the whole state is built around is that the extract for a given consensus sequence is
byte-identical forever — so accuracy work across CPU, GPU, and TPU scores the identical portfolio
and only numerical precision varies.

Primary intent:

- take positions from the replicated state machine at an exact consensus sequence, never from the
  asynchronous SQL read model, so every account is frozen at the same instant,
- mark each row against the published closing-price version where one exists and the engine's last
  trade at the cut sequence otherwise, stamping which on every row,
- emit un-netted `(account, security)` rows carrying counterparty and netting set as attributes, so
  netting and CSA treatment stay with the consumer,
- deliver one immutable object stamped with `(consensus sequence, closing-price snapshot version)`
  and announce it on `risk.extract.ready`,
- prove reproducibility rather than assert it: identical bytes across members, across a rebuild,
  and across a member that crashed and replayed.

Core artifacts:

- `generation/runtime-overrides/order-matcher/` — `RiskExtractCut` (the canonical cut),
  `RiskExtractCsv` (the fixture), `RiskExtractCutPublisher`, `RiskExtractGcsSink`,
  `RiskExtractMain` (the producer), SBE template 8, and the marker branch in
  `MatchingEngineClusteredService`
- `generation/kubernetes/cluster/` — NATS, the published-close database, and the producer
  Deployment alongside the inherited cluster tier
- `reference-data/` — the counterparty/netting-set mapping and instrument universe
- `system/adr-055` … `adr-056` — the sequenced marker, and where marks come from
- `system/architecture.model.json` — generated architecture flow for the extract chain

Target runtime behavior:

- `eod.pnl.done` is the only trigger; the producer holds a durable consumer, so a failed extract is
  redelivered rather than lost,
- all members render the same cut bytes at the same sequence, and a restarted member replaying to
  that sequence renders them again,
- the producer sends a second marker after building and refuses to emit unless it landed at exactly
  one past the first — the consensus log witnessing its own quiescence,
- a row that cannot be defensibly marked stops the whole extract rather than shipping a zero,
- the delivered object is write-once, and the fixture rebuilds byte-identically from the cut stored
  beside it with no cluster involved.
