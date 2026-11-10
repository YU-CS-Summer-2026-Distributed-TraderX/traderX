# RI36: the read model is cut as a slow consumer at the batched wire, and cannot catch up

Opened 2026-11-09 (trade-history-tiers lane, the GKE 400K campaign, RI34 §1b). Status: open; the fetch sizing is in
(FR-DRS-43); the headroom is not.

## What

At 1.2–1.5M records/s (400–520K orders/s with FR-DRS-41's 20–40 records per message) NATS cut trade-processor's
connection as a slow consumer 351 times in five minutes (`Slow Consumer Detected: MaxPending of 67108864 Exceeded`),
history-feed 27 times. After each cut the pull consumers resume from the last delivered sequence and re-read; the
read model (order view, SQL projection) then sits 4–5M messages behind for the rest of the hour and the run's far end
cannot G3 it, while the history store (history-feed) stays current.

Two causes, one fixed:

1. **Fetch size in messages** (fixed, FR-DRS-43): `BATCH_SIZE = 1000` per partition fetch was sized for one-record
   messages; at ~12 KB a message, 16 feeds and two or three fetches in flight per feed exceed 64 MB on one connection.
   The fetch is now sized by bytes (1 MiB aim).
2. **No headroom** (open): trade-processor runs at 7–8 cores of 12 at this rate — the per-record JSON parse and the two
   views — so any lost 100 ms is never regained. history-feed does the same volume on 1.2–1.9 cores with a batch
   INSERT per fetch.

## Fix

- Shard the read model by partition across replicas (the stream is already partitioned; each replica owns a subset),
  or at least run the order view and the SQL projection in separate processes.
- A cheaper per-record path: the compact wire (the options paper's F) or a streaming parser; measure the parse share
  first with a profile at 1M records/s.
- `max_pending` on the NATS server (256 MB) and one connection per partition in trade-processor are cheap and reduce
  the cut frequency; they do not add speed.
- The run's far end should take G3 (snapshot) after the read model drains and not gate the hour on it; the lossless
  claim is the history store's (G3h).

## Evidence

Shared notes `bridge-batching-gke-20261109/`: `hour-take3-memory` (the first hour: read model 4–5M behind from 9:10 pm),
`hour-take5-*` (the second), NATS logs' slow-consumer lines per IP.
