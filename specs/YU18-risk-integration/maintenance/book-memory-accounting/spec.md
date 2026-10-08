# Book-memory accounting specification

Status: standalone book tool integrated; bounded real-engine extension implemented/source-tested, review pending. Owner: Codex RI24 book-memory lane.

## Requirements

- **FR-BMA01:** Instantiate exact authoritative current LimitBook units and report
  nonzero instrumentation shallow bytes with exact source/class/JVM provenance.
- **FR-BMA02:** Inventory every book-owned array, separate element payload, base-offset
  overhead, trailing padding and book-instance shallow size, all in bytes.
- **FR-BMA03:** Compare empty and modest nonempty books. Deduplicate reached orders by
  identity and attribute them to their preallocated external fixture owner; exclude
  their bytes from book-owned totals. Report the entire fixture pool separately.
- **FR-BMA04:** Record constructor allocation traffic, retained heap and full-engine
  shared memory as unavailable unless actually measured; shallow/reachable sums must
  never imply a safe maximum venue breadth or a production capacity recommendation.
- **FR-BMA05:** Provide level/count/occupancy/reference-width/alignment controls and
  refuse incomplete object inventories or an unexecuted/malformed report.
- **NFR-BMA01:** Use only installed local tools, temporary child JVM fixtures with
  fixed 128 MiB maximum heap, explicit input bounds/timeouts and cleanup. No rig,
  cloud, dependency install, service or production source changes.

## Acceptance scenarios

1. Default run creates two 131072-level books using the current source default,
   measures them empty and after four fixture orders each, and emits valid schema-1 JSON.
2. Level 64 versus 128 changes payload by the independent constructor arithmetic;
   one versus three books changes total book-owned bytes by book count.
3. Compressed versus uncompressed references changes the measured reference slots;
   16-byte alignment is recorded and shallow objects satisfy that alignment.
4. Four resting orders reached through head/tail/FIFO aliases are counted four times
   in total, and adding them leaves the book-owned object sum unchanged.
5. Rebuild a faulty observer that omits askBits and recomputes its own totals. The
   independently sourced eight-array inventory rejects the actual JVM report.
6. Invalid bounds and absent/duplicate/malformed reports exit unsuccessfully;
   unavailable measurement categories remain explicit.

Tests: `python3 scripts/diagnostics/book-memory/test_book_memory.py`, including installed
JDK selection through JAVA_HOME. These are direct source/JVM object fixtures; no full
engine, generated composition, production retention, performance or recovery proof.


## Separate engine-component extension

- **FR-BMA06:** Instantiate current actual MatchingEngine/risk/output collaborators with
  bounded fixture capacities. Measure empty, real onEvent resting and crossed states;
  record constructor/default versus fixture geometry and actual capacities separately.
- **FR-BMA07:** Deduplicate explicit roots and engine order entries across free list,
  index, book and terminal transitions. Separate engine-owned and supplied-component
  categories; every reference is followed, explicitly excluded with reason, or refused.
- **FR-BMA08:** Record source/dependency/class/JVM hashes and qualified first-constructor
  thread-allocation window independently of shallow/reachable sums. Retained/full-member,
  metrics-descendant and ring-infrastructure-descendant bytes remain unavailable.
- **NFR-BMA02:** Bound all engine JVM inputs/heap/object counts/timeouts; sanitize ambient
  JVM/geometry options in owned children, use installed Java/cached offline dependencies,
  and preserve standalone book code/profile/tests unchanged.

Acceptance: run `python3 scripts/diagnostics/book-memory/test_engine_memory.py`. Pool 8/32
must change the actual order-entry category while pool-to-book/cross transitions keep it
constant. Level/count/capacity controls must change their real arrays/books. Account and
security controls must precede orders, and actual crossing must yield positions +1/-1,
filled states and terminal retention. Independent inventories include stagedExt and all
supplied risk arrays; rebuilt omitted-category/shared-root/unknown-object observers must
refuse. Original 16 book checks continue to pass. Only direct-source/local-JVM component
accounting is claimed, with explicit graph boundaries and no capacity recommendation.
