# Tasks: YU15-eod-risk-extract

- [x] T-RXT01 — Scaffold the state: spec pack, pipeline hook/render pair, catalog entry,
      runtime-harness + CI-assets registration, runtime wrapper scripts. The render script also
      renders the reference data into order-matcher resources so the producer reads it from the
      image.
- [x] T-RXT02 — SBE `RiskExtractMessage` (template 8) plus codec encode/decode, mirroring the
      symbol-registration precedent; the marker carries only the extract's stamp.
- [x] T-RXT03 — `RiskExtractCut`: canonical cut rendering — explicit `(accountId, securityId)`
      sort, fixed columns, integer ticks, no clock — and its SHA-256.
- [x] T-RXT04 — Marker branch in `MatchingEngineClusteredService`: routed by template id ahead of
      the order-flow branch, advances the consensus position, renders and hashes on every member,
      stamps the hash to stdout, leader-only publish.
- [x] T-RXT05 — `RiskExtractCutPublisher`: leader-side SPSC queue plus daemon thread on the
      ADR-048 shape, so the apply thread never blocks on NATS.
- [x] T-RXT06 — `RiskExtractCsv`: the fixture as a pure function of the cut, published marks, and
      counterparty reference data; multiplier-aware exact decimal valuation; conventions in the
      preamble; fail-closed on an unmarkable row, an unmapped account, a truncated cut, and a cut
      from the wrong sequence.
- [x] T-RXT07 — Readiness reports the consensus-log position rather than the engine's `blpSeq`, so
      a member restored from a snapshot into an idle cluster rejoins the Service. Found by this
      state's own proof; an EOD window is exactly when the cluster is idle.
- [x] T-RXT08 — `RiskExtractMain`: durable trigger on `eod.pnl.done`, fresh cluster session per
      batch, marker → cut → join → witness marker → write-once delivery → announcement, plus the
      `--rebuild` path that reconstructs a fixture from a stored cut alone.
- [x] T-RXT09 — `RiskExtractGcsSink`: immutable object delivery over the S3-over-GCS transport
      YU09's journal archiver established, with server-side write-once.
- [x] T-RXT10 — kind tier: NATS with JetStream, a database holding the two YU06 price tables, the
      producer Deployment, and the NetworkPolicy allowlist entry without which the producer cannot
      reach the members.
- [x] T-RXT11 — Unit proofs: cut identity across a snapshot-restored member, fixture byte-identity
      across rebuilds and across members, marker mutates nothing and advances by exactly one, mark
      sourcing, multiplier-aware valuation, un-netted counterparty attributes, and every
      fail-closed path.
- [x] T-RXT12 — Live acceptance proof `scripts/bench/yu15-risk-extract.sh`: trigger, cross-member
      hash agreement, quiescence witness, byte-identical rebuild, immutable object, and a deleted
      member replaying to the stamped sequence and re-rendering the identical cut.
- [x] T-RXT13 — Widen every instrument-identifier column to `VARCHAR(32)` in both the fresh-volume
      and migrations blocks, with explicit `ALTER TABLE ... MODIFY COLUMN` statements — the
      migrations block's `CREATE TABLE IF NOT EXISTS` cannot widen a table that already exists, the
      same reason YU05's `settlementdate` needed an explicit `ADD COLUMN`. The JPA entities already
      declared `@Column(length = 50)`, so only the schema was ever the constraint.
- [x] T-RXT14 — Prove it on the real chain: `scripts/bench/yu15-option-persistence.sh` narrows the
      columns back to an older state's widths, shows the option fill rejected with `Data too long`
      while the cluster books it regardless, applies the shipped migration to the populated volume,
      and shows the next cross persisting with the symbol intact.
- [x] T-RXT17 — Quote the listed option chain in price-publisher: OCC parsing and Black-Scholes at
      a flat implied vol in `src/option-quotes.js`, derived from the underlying's tick on every
      publish (never walked independently), with the model inputs reported on `/health` and eleven
      `node:test` cases covering parsing, put-call parity, intrinsic floors, and monotonicity.
- [x] T-RXT18 — Instrument-aware spike threshold in `EodQualityChecker`, so an option's ordinary
      day-over-day move does not flag and block publication of the whole session. Staleness and
      missing checks unchanged.
- [x] T-RXT19 — Multiplier-aware market value in `EodPnlConsumer`, so an option row in
      `eod_position_pnl` states its real exposure and agrees with the extract exactly.
- [x] T-RXT20 — Align the feed's bootstrap spots for AAPL/MSFT with the strikes the seeded chain
      and `instruments.csv` were designed around, so the listed contracts are not all deep out of
      the money the moment they are quoted.
- [x] T-RXT21 — Rewrite the acceptance proof to drive the real chain end to end: session close →
      published prices → P&L → `eod.pnl.done` → extract. Nothing hand-seeded, nothing
      hand-published.
- [x] T-RXT15 — Full regression: `test`, `noGcTest`, `riskNoGcTest`, all four allocation gates.
- [x] T-RXT16 — `generation/implementation-status.md` written with verification evidence; state
      docs synced.
