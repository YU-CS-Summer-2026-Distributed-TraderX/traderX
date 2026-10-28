# FIX gateway memory tasks

- [x] Local load on the real gateway JVM with heap histograms: unread answers queue in MINA without limit (~690 B
      each); the inbound queue holds 10,000 parsed messages per session (~2.5 KB each).
- [x] Slow-consumer limit (FR-FGM-01) and a bounded inbound queue (FR-FGM-02); `FixGatewayMemoryBoundTest` fails on
      the tip acceptor and passes with them. First landed as watermarks (`892bd37f`); replaced the same day after
      they wedged a session (MINA 2.1.4 interestOps race).
- [x] Fill-feed retry with backoff, one WARN and a counter (FR-FGM-05); measured as noise, not load.
- [x] Streaming adoption keeps only open orders (FR-FGM-03).
- [x] Same local load on the fixed gateway (4 sessions that never read, real 3-member cluster): live heap 30 → 54 MB at
      the limit, back to 39 MB once the sessions were dropped and MINA's 60 s timeout released them, flat after.
      Before the fix, 16 such sessions grew linearly (+55 MB over 112K answers) until MINA's timeout.
- [ ] f15ho hour re-run on the fixed image (FIX-tail lane): heap after GC flat; slow-consumer drops, if any, counted.
- [ ] Why 4 of 18 gateways fell behind at +24 min: f15hx histograms and thread samples (FIX-tail lane).
- [ ] ADR-090's per-FIX-day order index (`FIX_EXACTLY_ONCE=jetstream`) bounded by its owner before that mode runs at rate.
