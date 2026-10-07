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

`unavailable` explicitly records that constructor allocation traffic, GC-root/dominator
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
