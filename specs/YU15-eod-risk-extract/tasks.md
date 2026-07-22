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
- [x] T-RXT13 — Full regression: `test`, `noGcTest`, `riskNoGcTest`, all four allocation gates.
- [x] T-RXT14 — `generation/implementation-status.md` written with verification evidence; state
      docs synced.
