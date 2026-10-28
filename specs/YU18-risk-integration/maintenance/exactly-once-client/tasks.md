# Exactly-once client tasks

- [x] Venue contract written down (FR-XOC-01, 02, 04, 05); retained-restatement test pins FR-XOC-02.
- [x] FIX `DUPLICATE` answered as a restatement naming `ord-<ref>` (FR-XOC-03).
- [x] Binary loadgen `plan.exactlyOnce`: reconnect, resend, DUPLICATE receipts; harness test; replay skips
      restatements; arm `xo`; local triad proof (patch handed to the harness owner).
- [x] FIX loadgen: global CompIDs, ADR-086 resume (no reset), ResendRequest/GapFill, PossResend resend held until the
      acceptor's ResendRequest is answered, skipped-order and silence resends; arm `xof`; local triad proof.
- [ ] GKE `xo` arm at ≤ 5K/s (CLOUD-QUEUE proposal).
- [ ] Exactly-once at 400K/s: proposed as ADR-090 (`exactly-once-dedupe`, per-stream sequences), awaiting yaakov.
