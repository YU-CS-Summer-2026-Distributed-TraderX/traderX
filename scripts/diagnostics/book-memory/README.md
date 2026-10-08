# Current book-memory accounting

Run from the repository root with Python 3 and an already installed HotSpot JDK:

```sh
python3 scripts/diagnostics/book-memory/book_memory.py > /tmp/book-memory.json
python3 scripts/diagnostics/book-memory/book_memory.py --levels 64 --books 1 --orders 4 --references uncompressed
python3 scripts/diagnostics/book-memory/test_book_memory.py
```

The default is two books at the `DEFAULT_BOOK_LEVELS` read from the current YU18
MatchingEngine source, each measured empty and then with four preallocated orders.
`--jdk /path/to/jdk` selects an installed runtime. Otherwise `JAVA_HOME`, then the
system JDK, is used. Optional `--references compressed|uncompressed|vm-default` and
`--alignment 8|16` exercise VM layouts. These settings apply only to the child JVM.

The runner copies the exact authoritative LimitBook, RestingOrder, InputEvent,
OrderTypes, Px and ReservationHolder units into a temporary directory, compiles
them with the selected JDK, and loads a small instrumentation agent. MatchingEngine
is read for its default and ownership context; it is not compiled or instantiated.
The source owners are explicit and later YU18-lineage shadows cause refusal pending
review. This directly tests those source units, without a generated-tree or full
service composition claim. No dependency downloads or Gradle build are needed.

`Instrumentation.getObjectSize` supplies shallow object sizes. HotSpot array base
offsets and element scales supply payload and layout accounting. Each book's
reference fields are enumerated; an unknown non-array reference or a reached object
outside the fixture pool causes refusal. Pooled-order links are traversed with
identity deduplication, so head, tail and FIFO aliases do not multiply order bytes.
Orders are attached directly through LimitBook.append; no engine admission, matching,
risk, snapshot or market-data path is exercised.

The schema-1 JSON uses bytes throughout:

| Category | Meaning |
|---|---|
| `array_payload_bytes` | Array element slots, including reference slots; excludes array overhead and referenced objects. |
| `array_base_offset_bytes` | Sum of array base offsets: object header, array length and any padding before elements. This is not a pure header measurement. |
| `array_alignment_padding_bytes` | Sum of trailing shallow-size padding after the element slots. |
| `array_shallow_bytes` | Instrumentation sizes of every book-owned array. |
| `book_object_shallow_bytes` | Book instance including header, fields and padding; no separate pure-header/field decomposition is claimed. |
| `book_owned_reachable_shallow_bytes` | Book instance plus its uniquely owned arrays. |
| `shared_pool_reachable_order_shallow_bytes` | Unique orders reached from the book, already allocated by an external fixture owner. Excluded from book-owned bytes. |
| `book_and_shared_reachable_union_shallow_bytes` | Book-owned bytes plus reachable shared orders, once each. Not retained heap. |
| `pool_owned_shallow_bytes` | Entire external fixture reservoir array and entries, including unused entries. Not the production engine pool. |
| `books_plus_entire_fixture_pool_union_shallow_bytes` | All books plus the complete fixture reservoir, counting pooled entries once. Excludes the separately reported book-holder array and diagnostic scaffolding. |

For the standalone book profile, `unavailable` explicitly records that constructor allocation traffic, GC-root/dominator
retained heap, and production pool/output/engine memory were not measured. Reachability
and shallow sums do not establish exclusive retention: removing a book need not free
an order still held by its owner. The fixture preallocates entries before constructing
books and retains its reservoir throughout both measurements.

Provenance records source paths/SHA-256/shadow candidates, checkout revision, the
compiled-class/agent hashes, compiler version, runtime/vendor/architecture, JVM command,
heap settings, compressed reference/class-pointer flags, alignment, compact-header
flag when supported, garbage collectors, and diagnostics. Source bytes are checked
again after execution. The recorded disposable paths have been removed when the
runner returns; use the documented command to recreate a run. An agent JAR hash
identifies that run's archive, whose ZIP timestamps can differ between builds.

Bounds are 1..4 books, 64..131072 power-of-two levels, 0..32 orders per book and
1..1000000 tick ticks. The JVM always has a 32 MiB initial/128 MiB maximum heap and
heap-OOM process exit. Compilation, archive creation and execution have separate
30/30/20 second timeouts; subprocess timeout kills and reaps the child. Temporary
source/classes/JAR are cleaned on normal exit or failure. Abrupt termination of the
parent by SIGKILL is outside Python's cleanup guarantee. Only requested JSON output
persists. This is a small local object-accounting fixture, not a production limit,
full-member heap, throughput, latency, HA or financial validation.

Focused tests vary levels, book count, occupancy, pointer width and alignment. The
expected eight fields are drawn independently from the current constructor. A negative
control rebuilds the observer with one array omitted while its own totals remain
internally consistent; validation must reject that actual JVM report. Other controls
reject absent/duplicate reports, omitted book/shared categories, invalid bounds and
changed default-source syntax. Set `BOOK_MEMORY_TEST_EVIDENCE=/tmp/book-memory-tests`
to save the six raw report profiles used by the suite.


## Separate real-engine component profile

```sh
python3 scripts/diagnostics/book-memory/engine_memory.py > /tmp/engine-memory.json
python3 scripts/diagnostics/book-memory/engine_memory.py --levels 64 --books 1 --orders 2 --pool 8 --securities 4 --terminal 4 --positions 16
python3 scripts/diagnostics/book-memory/engine_memory.py --levels 64 --books 1 --orders 2 --pool 32 --securities 4 --terminal 4 --positions 16
python3 scripts/diagnostics/book-memory/test_engine_memory.py
```

This separate schema-1 `profile=engine-components` runner compiles sixteen exact current
source units, including the real MatchingEngine, BlpRiskState, OutputPublisher,
OutputEvent, PositionBook and metrics collaborators. It uses nine explicitly versioned
JARs already in the local Gradle modules cache; missing or ambiguous dependencies refuse
without downloads. `--cache` selects an existing cache and `--jdk` selects an installed
JDK. On macOS the engine runner prefers installed Java21. Annotation/GatewayReplicaStore
units are needed for compilation, but no Spring context, gateway service or native affinity
initialization is started. No generated tree, production build or Gradle setting changes.

The default fixture has two 128-level books, two orders per book, sixteen preallocated
orders, eight securities, terminal retention 16, position capacity 32, pending/peg limits 8,
eight risk accounts, risk exposure request 64, idempotency request 32 and a fixed 256-slot
real output ring. The JSON exposes requested capacities, actual array lengths, applied
geometry/limits, synthetic risk policy, current engine constructor/default geometry and
default pending capacity. The default book level count 131072 is metadata, distinct from
fixture 128. Existing setters change geometry/order-type limits only in this disposable
instance before any book/order exists. They can discard the constructor's original
trigger queue; current reachability does not measure that earlier allocation.

Three phases measure the same engine: empty, resting orders after sequenced account/
security/price controls, and a cross from a second account. All orders use the actual
MatchingEngine.onEvent path; the first bid and the crossing order must finish filled,
with buyer/seller positions +1/-1 and two terminal entries. No fabricated book is injected.
The output publisher writes actual preallocated ring slots; no consumer/service thread
is started and this bounded command count stays below ring capacity.

`categories` separates engine instance, engine arrays/index/positions, engine order
entries, lazy book objects/arrays, supplied risk objects/arrays, supplied output publisher,
real output slots/typed shapes and shallow metrics/ring wrappers. Identity dedup applies
across all these explicit roots. Engine-owned order entries remain counted once as they
move from free list to index/book and then terminal state. Supplied collaborator bytes are
excluded from `engine_owned_unique_shallow_bytes` and separately included in the measured
union. Per-array payload/base offset/padding/shallow sizes and per-class reference inventories
make category arithmetic and ownership inspectable.

Every reference in the measured project classes/Agrona index must match a named inventory;
unknown types/fields refuse. Metrics wrappers' reference fields each explicitly exclude
HdrHistogram, counters, atomic and concurrent-map descendants. Ring wrapper shallow bytes
and actual event slots are measured, while every ring reference edge explicitly excludes
sequencer/wait-strategy/entry-array infrastructure descendants. Agrona lazy iteration
adapter references are explicitly excluded; core key/value arrays are measured. Snapshot/
backpressure callbacks must remain null. These exclusions have reasons in every phase;
the measured union is a partial component scope, not total reachable or full-member heap.
Static state, process/service/JDK hosting graphs, native/off-heap bytes and dominator-retained
heap remain unavailable. Headers/base offsets have the same limits as the book profile.

A separate `constructor_allocation_sample` records a real per-thread allocated-byte window
around the first engine constructor only, plus a no-op counter-read baseline. Supplied
collaborator construction and later fixture geometry/pending overrides are outside that
window. On-thread class initialization/internal constructor allocations can be included;
there is no baseline subtraction, steady-state interpretation or cross-run performance
claim. This allocation-traffic sample is distinct from each phase's shallow object sum
and from unmeasured retained heap.

Fixture bounds: books 1..4, levels 64..4096 power of two, orders per book 1..8, pool 8..128
and at least total resting orders+1, securities 4..16, terminal 4..128, positions 16..128,
pending/pegs 1..32, risk accounts 2..16, exposures 16..256 and idempotency 8..128. The diagnostic
records rounded table/position capacities rather than equating requests with physical
lengths. The graph refuses beyond 5000 objects or 96 MiB of measured shallow bytes. Compiler,
archive and fixture JVM heaps are capped at 128/64/128 MiB with 45/20/30-second timeouts.
Ambient BOOK_LEVELS/BOOK_TICK_PX and JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS are
removed from the owned children and recorded; they cannot enlarge the fixture heap.
Timeout/failure kills/reaps child and cleans temporary files on ordinary Python exit.
Parent SIGKILL remains outside cleanup guarantees. No production limit recommendation.

The engine suite uses independent current constructor/root inventories, including the
stagedExt field initializer. It varies pool, levels, book count, security/terminal/position/
pending and risk capacities, and reference width. Real rebuilt faulty observers omit risk
arrays or stagedExt, duplicate a shared RiskMetrics root, or add an unknown object; all
must refuse. The original sixteen book checks remain unchanged. Set
`ENGINE_MEMORY_TEST_EVIDENCE=/tmp/engine-memory-tests` to retain raw profiles/negative
reports and faulty observer sources separately from cleaned JVM artifacts.
