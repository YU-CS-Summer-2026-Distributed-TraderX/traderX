# Implementation Plan: EOD Risk Extract

## Goal

Produce the end-of-day portfolio fixture an external pricing and risk engine consumes, with the
consistency and reproducibility properties that engine's mathematics depend on: every account
frozen at one consensus sequence, our official marks and P&L attached, rows un-netted with
counterparty attributes, delivered as one immutable object announced on NATS — and byte-identical
for a given sequence forever.

## Workstreams

### 1. The cut

A sequenced risk-extract marker (SBE template 8) carrying only the extract's stamp, routed by
template id in `onSessionMessage` ahead of the order-flow branch. On apply every member advances
its consensus position, renders the cut through `RiskExtractCut`, and records the SHA-256; the
leader offers it to `RiskExtractCutPublisher`, which publishes it on a daemon thread so the apply
thread never blocks. Determinism comes from explicit sorting, fixed columns, integer ticks, and the
absence of any clock — not from the incidental stability of hash iteration order.

### 2. The fixture

`RiskExtractCsv` renders the delivered artifact as a pure function of the cut, the published
closing-price version, and the counterparty reference data. Marks resolve to the published close
where one exists and the cut's own last trade otherwise, stamped per row. Market value and P&L are
multiplier-aware and computed in exact decimal arithmetic. The conventions travel in the file's own
header so a tie-out discrepancy has a starting point.

### 3. The producer

`RiskExtractMain` — a standalone main in the order-matcher module, deployed from the same image as
the node and the gateway. Durable JetStream consumer on `eod.pnl.done`; a fresh cluster session per
batch; marker, cut, join, second marker as the quiescence witness, write-once delivery, and the
`risk.extract.ready` announcement. Any failure leaves the trigger unacked and nothing partial
behind.

### 4. Delivery

A write-once object keyed by `(sessionDate, priceVersion, consensusSequence)`, with the cut stored
beside it so the fixture can be rebuilt and byte-compared with no cluster involved. `file://` on
kind; `gs://` through the S3-over-GCS transport YU09's journal archiver already uses, where GCS's
own `if-generation-match: 0` enforces write-once server-side.

### 5. Proof

Unit proofs of cut determinism across a snapshot-restored member, fixture byte-identity across
rebuilds, multiplier-aware valuation, mark sourcing, and the fail-closed paths. Then the live kind
proof: trigger, cross-member SHA agreement, quiescence witness, rebuild comparison, and a member
deleted mid-window that replays and re-renders the identical cut.

## Key decisions

- **ADR-055** — a sequenced marker names the cut, rather than parsing the Archive snapshot.
- **ADR-056** — published close where one exists, cluster last trade otherwise, stamped per row.

## Exit Criteria

- Publishing `eod.pnl.done` and nothing else yields a delivered object and its announcement.
- All three members log the identical cut hash for the stamped sequence, and the announcement's
  witness sequence is exactly one past it.
- The fixture rebuilds byte-identically from its stored cut.
- A restarted member replays to the stamped sequence, re-renders the identical cut, and rejoins the
  Service.
- The order-matcher suite, both epsilon-GC gates, and all four allocation gates pass.
