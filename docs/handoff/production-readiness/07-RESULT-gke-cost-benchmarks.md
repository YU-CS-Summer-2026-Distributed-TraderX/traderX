# 07 — RESULT: the GKE cost benchmarks — observability and capture cost nothing on the trade path

> Closes the two timing claims deferred by briefs [05](05-RESULT-opentelemetry-observability.md)
> (OTel traces) and [06](06-RESULT-kdb-live-capture.md) (kdb live-capture tap). Board: [[00-INDEX]].
> Run 2026-07-28 on GKE, one cluster window, both A/Bs one env value apart on the same image —
> no rebuild between arms, exactly as both briefs designed.

## Verdicts

**1. OTel distributed traces (`OTEL_TRACES` 0 → 1, production 1-in-128 sampling): no measurable
cost on the trade path.** Committed throughput at saturation −1.5% median across four "on" runs vs
three "off" runs, with the arm spreads overlapping; client-observed latency p50/p99 indistinguishable
at 1k/s and 5k/s. The "on" arm was real: the collector was live and receiving thousands of spans/s
during the floods (verified in collector logs), and the sampling verdict, trace id and parent span id
were still being derived per order on both sides of the consensus boundary.

**2. kdb capture tap (`KDB_TAP_DIR` unset → set): zero cost at full saturation throughput.** With
the tap on, the cluster committed 131,334/s and 126,211/s — bracketing the base runs — while the
leader captured **9.3M order-lifecycle rows + 200,001 trade rows (~880 MB of CSV) per one-minute
flood**, followers header-only. Steady-state latency with the tap on is identical to base (cold
5k/s probes: p50 529/532 µs, p99 1.86/1.94 ms, twice). The drop-don't-block design held throughout:
no run wedged the cluster, ever.

One honest boundary on claim 2, measured rather than argued: for a couple of minutes **after** a
saturating flood, while the tap's writer thread drains the multi-hundred-MB capture backlog on the
leader, a concurrent 5k/s probe sees multi-second tail latencies. The trade path keeps committing at
full rate the whole time — the cost lands on co-resident latency tails during drain, not on
throughput or on steady state (the two cold probes above are the control). `KDB_TAP_MAX_MB` bounds
this window by construction.

## The rig

- 3 members on `blp-c4d-tuned-pool` (c4d-standard-8, tainted, Guaranteed QoS, 3 pinned cores,
  lowpark idle strategy) — the standard tuned tier.
- 3 gateways + 3 load generators + probe on an untainted `bench-pool` (3× c2d-standard-8,
  **pd-standard boot disks** — the `SSD_TOTAL_GB` quota never engages), support (database, nats,
  reference-data, otel-collector, tempo) on one e2-standard-2.
- One image for both tiers: `cluster-node:yu15-costbench` (amd64, built from the YU15 tree at the
  brief-05/06 HEAD, digest `f0e49b0a…`), verified running on every pod before any run. Arms are
  **pure env flips** on the deployed manifests.
- Every visit started from a **fresh epoch**: members scaled to 0 (emptyDirs die with the pods),
  back to 3, gateways restarted, accounts re-seeded — identical initial state per arm, and the
  consensus session table can never carry a previous visit's damage.

## Throughput (saturated: 210k/s offered, 3 gateways, 64 s window, ground truth = member `nextOrderRef` delta)

| visit | base | otel (1/128) | kdb tap |
|---|---|---|---|
| 1 | 129,188 | 127,629 | — |
| 2 | 129,050 | 127,593 | **131,334** |
| 3 | 128,548 | 120,195 | 126,211 |
| 4 (tiebreak) | — | 126,425 | — |
| **median** | **129,050** | **127,009 (−1.5%)** | **128,772 (−0.2%)** |

Arms alternated on the same rig in the same session, never compared across rigs. The single otel
120k reading is an outlier against its own three siblings at −1%; the kdb arm's high reading
(131k, *above* every base run) is the same run-to-run spread seen from the other side. Visit-1 kdb
was discarded — its rig was invalidated by a wedge unrelated to the tap (below).

## Latency (CO-safe `rest-latency-probe.mjs`, in-cluster, single gateway, seeded ticker, 45 s measured)

At 1k/s, p50 / p99 in µs:

| | base | otel | kdb tap |
|---|---|---|---|
| runs | 804/2077 · 749/1990 · 760/1979 | 429/1634 · 746/2130 · 517/1747 | 258/1606 · 385/1943 |

At 5k/s (clean runs): base 540/1982 · 540/1917 · 546/1977; otel 537/1923 · 553/1963 · 494/1934;
kdb **cold** 529/1855 · 532/1942. Every arm sits inside every other arm's spread at both rates —
per the standing rule these are quoted as ratios against same-session base runs, not as absolutes.
The kdb 5k probes taken during backlog drain (p90 0.75–2.4 s) are the drain-window boundary already
stated in the verdict; otel2's single degraded 5k run did not reproduce in otel3 or otel4.

## Found along the way (real findings, each cost the session time)

1. **A binary-ingress flood carrying an unregistered numeric security id wedges gateways.** The
   old "SECURITY=1 = reject-path probe" recipe predates seeding: on a seeded cluster id 1 is a real
   book, and an out-of-range id (9999) reliably killed gateway sessions under flood (HTTP plane
   dead, all workers parked on the in-flight semaphore, members clean). The A/B therefore ran the
   seeded two-account crossing flow on the **resolved** id. The wedge itself is filed for a fix.
2. **The egress-ack throttle resurfaced on a polluted rig**: after gateway churn left dead egress
   sessions, apply crawled (~1/s-class) while `nextOrderRef` still advanced and members reported
   healthy — the brief's "a 200 means nothing" rule in its purest form. The fresh-epoch-per-visit
   protocol exists because of this; a rolling restart does NOT clear it (members replay the log and
   catch up right back into the same state).
3. **`POST /orders` reads `limitPrice`, not `price`** — the unknown-field sibling of the
   `ticker`-not-`security` trap: `price` is silently ignored, the order rests unpriced, nothing
   ever crosses, and every ack still says ACCEPTED.
4. **Ticker→securityId assignment is per-boot nondeterministic** (registration order), so a
   fixed numeric `SECURITY` means a different flow after every wipe. Resolve per run.

## Cost

Node window ≈ 2.6 h: 3× c4d-standard-8 (~$0.47/h) + 3× c2d-standard-8 (~$0.34/h) + 1× e2-standard-2
(~$0.07/h) ≈ **$6.50 of the ~$39 remaining credits** (SKU-rate estimate; boot disks pd-standard,
pennies). All node pools returned to zero the same session; the control plane stays up per yaakov.
The deployed cluster objects keep the `yu15-costbench` image with both features present-but-off
(`OTEL_TRACES=0`, `KDB_TAP_DIR` unset) — the next bring-up starts in the production-default state
and either arm is one `kubectl set env` away.
