# Exactly-once client tasks

- [x] Venue contract written down (FR-XOC-01, 02, 04, 05); retained-restatement test pins FR-XOC-02.
- [x] FIX `DUPLICATE` answered as a restatement naming `ord-<ref>` (FR-XOC-03).
- [x] Suspect 17 (2026-11-06): the order bridge honours `publishNats`, so a restatement never rewrites the original in the
      read model (FR-XOC-02); a cancel/replace by `OrigClOrdID` of a mapped order the engine aged out names `ord-<ref>`,
      `39=3` (FR-XOC-03). Live: yu18-fix-duplicate PASS on kind.
- [x] Binary loadgen `plan.exactlyOnce`: reconnect, resend, DUPLICATE receipts; harness test; replay skips
      restatements; arm `xo`; local triad proof (patch handed to the harness owner).
- [x] FIX loadgen: global CompIDs, ADR-086 resume (no reset), ResendRequest/GapFill, PossResend resend held until the
      acceptor's ResendRequest is answered, skipped-order and silence resends; arm `xof`; local triad proof.
- [x] FR-XOC-07: restatements and status requests read by client key; listings bounded in number and rows
      (`ReadModelOrders`); unit tests with a negative control.
- [ ] FR-XOC-07 on a rig: a leader kill at load with gateways surviving (kind, then GKE stage 3).
- [ ] GKE `xo` arm at ≤ 5K/s (CLOUD-QUEUE proposal).
- [ ] Exactly-once at 400K/s: proposed as ADR-090 (`exactly-once-dedupe`, per-stream sequences), awaiting yaakov.
