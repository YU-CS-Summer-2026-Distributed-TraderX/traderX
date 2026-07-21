# TAQ trade-print replay on the YU13 crossing book — GKE, 2026-07-21

Real NYSE TAQ order flow (YU07 tick store) crossing on the Raft-consensus limit-order book.
Method, harness, and the traps are in `specs/YU13-limit-order-book/generation/implementation-status.md`
("Real TAQ order flow on the crossing engine"). Harness: `scripts/bench/taq-replay.mjs`;
curation: `scripts/bench/taq-curate.py` (run on the tick-store pod; slice CSV is gitignored,
reproducible from GCS).

## Slice

- 7 symbols (odd count): AAPL, MSFT, NVDA, TSLA, AMZN, META, JPM — all in the seeded universe
- 2025-03-03 09:30:00–10:00:00 ET, `event_type='trade'` prints
- **1,138,793 prints, $18.2B notional**, tick-rule aggressor side (47.3% buy)
- Prices grid-rounded to 0.001; max print 2,111,072 shares (NVDA opening block)

## Cluster

GKE `blp-c3-pool`, 3 members (3 CPU / 2Gi, one per node) + 3 gateways, image
`cluster-node@sha256:242ddd30…` (the replication-port-pinned build), fresh epoch, 7 real
accounts + 20-ticker universe seeded first.

## Runs

| Run | Config | Result |
|---|---|---|
| Paced (flagship) | speed 5×, one gateway pod, batch 100 prints | 1,138,793 prints in **360.2 s — pacing held exactly**. Orders accepted 2,277,155/2,277,586 (**431 policy rejects = 0.019%**, incl. both sides of the two >1M-share NVDA blocks vs the order-size cap). 0 HTTP failures. 2,276,892 trade records booked. |
| Max-rate | conc 8 via the Service, batch 100 | 1,138,793 prints in **78.1 s = 14,589 prints/s, 29,172 accepted orders/s**. Client-bound (one bench pod); the 75k booked/s pipelined ceiling (T-LOB15) stands. |
| Single ordered stream | conc 1, one gateway, batch 100 | ~7,700 prints/s (15.3k orders/s). batch 200 stalls on ack-window loss — see the trap note. |

## Post-run state (the falsifiable part)

- `traderx_book_open_orders` = **0 on all three members**; book order hash 0 (empty) — the
  trade-print pair method self-cleans.
- Position hash, `nextOrderRef`, applied position **byte-identical on all three members** after
  ~2.4M booked trade records.
- Booked-rate timeline traced the real session shape: ~20k booked/s during the compressed 09:30
  burst decaying to ~4k/s by 10:00 (sampler: leader `/health` trades delta @8 s).

## Failover spot-check on this build (20 ms probe, client gap, rotated kills)

| Killed | Failed reqs | Success | Client gap |
|---|---|---|---|
| m1 | 3/1962 | 99.85% | **124 ms** |
| m0 | 2/1968 | 99.90% | **348 ms** |
| m1 | 3/1960 | 99.85% | **223 ms** |

Consistent with the recorded median ~200 ms / worst ~450 ms; the replication-port pinning did
not regress failover.
