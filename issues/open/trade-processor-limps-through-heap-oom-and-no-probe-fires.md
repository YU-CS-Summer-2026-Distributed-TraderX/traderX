# trade-processor limps through heap OOM for hours and no probe ever fires

**Filed 2026-08-25** (format-8 proof-set chip; verified independently by the coordinator).
Evidence captured before the restart: log tail with the OOM stacks, `kubectl describe`, and a
JetStream census — session scratchpad `trade-processor-oom-logs.txt` / `trade-processor-describe.txt`.

## What was observed

- `trade-processor` not-ready since ~03:13Z, **~16h**, readiness probe
  (`:18091/actuator/health`, timeout 1s) failing continuously (7 358 events).
- The log is a steady loop of `java.lang.OutOfMemoryError: Java heap space` from
  `scheduling-1` — **80 occurrences in a 400-line tail** — each followed by
  `ReconciliationService: sweep skipped this cycle (order-matcher unreachable):
  java.io.IOException: selector manager closed` (its own HTTP client died of the same OOM).
- Container limit `memory: 1Gi`, request 512Mi. **Restart Count: 0.**

## The guard-interaction observation (the sentence worth keeping)

A JVM that limps through heap OOMs without dying defeats BOTH probes: liveness never fires
because the process is alive and answering nothing is not a liveness criterion, and readiness
only quarantines it — so the pod sits 0/1 forever, wedging everything gated on its health
(run-proofs.sh's `start_forwards` gates the whole suite on 18091 answering 200). The failure
mode is "alive, useless, and permanent"; nothing in the platform ever restarts it.

## Probable shape

The 180s flood left ~3.6M trades this epoch; trade-processor accumulates per-trade in-process
state with process lifetime — the trade-id dedup set ("Duplicate trade delivery ignored") and
`ReconciliationService`'s classification structures — against a 1Gi heap. One refinement to the
bound-the-consumer reading: **there is no JetStream stream for trades** (census: only
TRADERX_CONTROL_SECURITY / TRADERX_EOD / TRADERX_ALGO_ENGINE), so the trade feed is core NATS
and there is no broker-side backlog to redeliver after a restart — the weight is in-process
state grown monotonically over the epoch's trade count, which means a restart comes back light
and the growth resumes at the (much lower) post-flood trade rate. If a restart re-OOMs
promptly, this reading is wrong and something IS replaying the epoch into it.

## Interim taken / to take

- Restarted 2026-08-25 ~01:45Z (rig-only measure). **Outcome: came back 1/1 Ready in ~60s,
  zero OOM lines in the fresh log** — consistent with the in-process-accumulation reading (no
  broker backlog existed to replay). The 2Gi bump was therefore NOT applied; the pod runs the
  committed 1Gi limit and will OOM again if another flood-scale trade count accumulates.
- If it re-OOMs without a flood: the accumulation reading is wrong; bump to 2Gi **as a
  rig-only measure** (the kind rig is disposable; the manifest layer is not being touched for
  this) and re-investigate what replays.

## The durable fix (not this chip's scope)

Bound the per-trade in-process state (the dedup set and recon counters need an eviction
horizon, or SQL-backed dedup), and give the pod a liveness criterion that a heap-OOM-limping
JVM actually fails — e.g. liveness on the same health endpoint with a generous window, so
"cannot answer health for 10 minutes" becomes a restart instead of a permanent 0/1.
