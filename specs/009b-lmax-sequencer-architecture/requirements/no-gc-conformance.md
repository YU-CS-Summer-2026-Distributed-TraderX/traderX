# No-GC Conformance Profile: 009b-lmax-sequencer-architecture

Cross-cutting allocation discipline for every hot-path stage in this state (Gateway encode, input ring,
BLP, output ring emit), per `LMAX-NO-GC-JAVA.md`. Referenced from `spec.md` NFR-09B02 and enforced by
`pipeline/validate-no-gc-conformance.sh` plus a Gradle `noGcTest` profile.

"No-GC" means **zero steady-state allocation**, not GC tuning. Allocation is permitted only at startup
(pre-allocating rings, pools, buffers) and at the outermost edges (Gateway converting inbound
JSON/`String`/`BigDecimal` to internal binary; output handlers converting back to JSON/SQL). Between
those edges, nothing is `new`-ed per event — and the contract is enforced, not hoped for.

## Requirements

- NGC-01: Hot-path packages (Gateway encode path, input ring, BLP, output ring) SHALL allocate zero
  bytes in the steady state; allocation only at startup and the outermost edges.
- NGC-02: A CI allocation gate SHALL run the hot path under
  `-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC` with a small fixed heap
  (`-Xms == -Xmx`, e.g. 64–128m, `-XX:+AlwaysPreTouch`) and FAIL on any steady-state allocation
  (heap exhaustion fails the test).
- NGC-03: Prices/quantities SHALL be `long` fixed-point (global scale `nogc.px.scale`, default
  ×1,000,000) on the hot path; `BigDecimal`/`String` conversions only at the edges, with rounding
  unit-tested against `009` (penny parity).
- NGC-04: Hot-path collections SHALL be Agrona primitive collections or plain arrays; no
  `HashMap`/`ConcurrentHashMap`, no autoboxing, no stream pipelines on the hot path.
- NGC-05: The hot path SHALL make no `Instant.now()`/clock reads, no RNG/UUID, and no
  `String.format`/SLF4J parameterized logging; time and IDs derive from the event/sequence, and logging
  is binary/async on a separate thread.
- NGC-06: Each hot-path node SHALL run a JIT warm-up replay (`nogc.warmup.events`) before going live
  and SHALL support a nightly bounce (snapshot + replay restart) in a configured window.
- NGC-07: The production/perf run profile SHALL use ZGC or Shenandoah as a sub-ms safety net; the
  demo/`C2` profile MAY use JVM defaults but MUST still pass the allocation gate in CI.
- NGC-08: The allocation contract SHALL be validated by HdrHistogram (latency distributions),
  JFR (`jdk.ObjectAllocationSample`) / async-profiler alloc mode (allocation attribution), and jHiccup
  (pause isolation).

## Technique table (what replaces what)

| Technique | Replaces in `009` |
| --- | --- |
| Pre-allocated ring of mutable holders | new DTO per request/message |
| Flyweight over off-heap (`UnsafeBuffer`) + SBE codec | Jackson `ObjectMapper` parse/serialize |
| `long` fixed-point prices/qty | `BigDecimal` (`roundPrice`, `setScale`, `compareTo`) |
| Symbol -> `int securityId` at the Gateway | `String security` end-to-end |
| Agrona primitive collections / arrays | `ConcurrentHashMap`, `HashMap<Integer,…>`, streams |
| Object pooling for domain state | `new OrderRecord()`, `OrderResponse.from(...)` per publish |
| No locks / no atomics (single writer) | `ReentrantLock`, `AtomicLong/Integer` |
| Binary/async logging off the path | SLF4J parameterized logging (varargs boxing) |
| Time carried in events (`ingressNanos`) | `Instant.now()` per mutation |
| `-Xms==-Xmx`, `-XX:+AlwaysPreTouch` | default heap growth/resize jitter |
| Epsilon GC in tests; ZGC/Shenandoah in prod | G1 defaults |

## JVM flags and run profiles

| Flag | `demo`/`C2` | `perf` (bare metal) | `noGcTest` (CI) |
| --- | --- | --- | --- |
| `-Xms` == `-Xmx` (fixed heap) | yes | yes | yes (small, 64–128m) |
| `-XX:+AlwaysPreTouch` | yes | yes | yes |
| `-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC` | no | no | yes (the gate) |
| `-XX:+UseZGC` (or `+UseShenandoahGC`) | optional | yes | no |
| `-XX:+UseLargePages` / THP | no | yes | no |
| `-XX:+UseNUMA` | no | yes (multi-socket) | no |
| `-XX:StartFlightRecording=settings=profile` | optional | yes | yes |
| Core pinning (`isolcpus` / OpenHFT Affinity) | no | yes | no |

Container caveat (`C2`): large pages, `isolcpus`, NUMA, and core pinning need host privileges a
containerized demo lacks. The `demo`/`C2` profile MUST run correctly without them and MUST still pass
the Epsilon allocation gate in CI.

## Configuration keys

| Key | Default | Purpose |
| --- | --- | --- |
| `runtime.profile` | `demo` | `demo` / `perf` / `noGcTest` selector. |
| `nogc.px.scale` | `1000000` | Global fixed-point scale (6 dp). |
| `nogc.warmup.events` | `100000` | Synthetic warm-up workload before going live. |
| `nogc.bounce.cron` | `0 0 3 * * *` | Nightly bounce window. |
| `nogc.logging.async` | `true` | Route hot-path logs to an off-thread ring. |

## Validation

- SC-NGC-01 (gate): `pipeline/validate-no-gc-conformance.sh` runs each hot-path node under Epsilon and
  fails on any steady-state allocation; passes when allocation-free.
- SC-NGC-02 (penny parity): fixed-point arithmetic matches `009`'s `BigDecimal` outcomes across a
  rounding fixture.
- SC-NGC-03 (latency): HdrHistogram reports meet the stage budgets in
  `requirements/nonfunctional-delta.md`, with no GC-induced tail spikes (jHiccup).
- SC-NGC-04 (banned APIs): static/architectural check asserts hot-path packages contain no
  `BigDecimal`, `Instant.now()`, `HashMap`/`ConcurrentHashMap`, stream pipelines, `String.format`, or
  SLF4J parameterized logging.
- SC-NGC-05 (determinism): journal replay produces identical state/output.
- SC-NGC-06 (profiles): demo/`C2` image runs without perf knobs and still passes the allocation gate;
  perf profile documented for bare-metal benchmarking.
