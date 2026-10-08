# Current orphan-sweep memory diagnostic

Run from the repository root with an installed Java 21 JDK and existing Gradle cache:

```sh
python3 scripts/diagnostics/reconciliation-memory/reconciliation_memory.py > /tmp/recon-memory.json
python3 scripts/diagnostics/reconciliation-memory/reconciliation_memory.py --history 128 --orphans 0
python3 scripts/diagnostics/reconciliation-memory/reconciliation_memory.py --history 512 --orphans 1200
python3 scripts/diagnostics/reconciliation-memory/test_reconciliation_memory.py
```

This diagnoses current `ReconciliationService.runOrphanSweep` with a read-only mocked
TradeRepository and an owned synthetic HTTP server bound only to 127.0.0.1 on an ephemeral
port. It does not contact a running matcher, create a database or start Spring. The
`/recon/full-history/reindex` POST is served entirely by that synthetic fixture; it never
triggers a real reindex. A sandbox must permit this loopback listener. `--jdk` selects an
installed JDK; otherwise JAVA_HOME or macOS java_home-v21 is used. `--cache` selects an
existing modules cache. Missing/ambiguous exact dependency versions refuse without downloads.

The runner compiles ten operative current units, including the complete YU18 service,
repository/model/RunRegistry and inherited auth/enums, with seventeen cached dependencies.
No methods are extracted. To observe local collections before they leave scope, the runner
adds one observer call to a temporary source copy immediately before result construction.
The operative service must match SHA-256
`fbeeb9d27d398cc267468d570fba52c49a545abce40c11de20d4e19e79166288`;
SHA drift or an ambiguous anchor refuses. Removing the hook reconstructs original bytes.
Both original and instrumented hashes/classes are recorded. No checkout production source
is modified. This is an observational source-copy proof, not byte-identical production
execution or generated-module validation. The observer can affect allocation traffic;
that traffic is not measured.

The actual complete method fetches every synthetic history page, constructs its HashSet,
reads all local IDs from the proxy repository, collects all orphans, creates its current
result and stores `lastOrphanSweep`. HTTP page/reindex counts, repository-read count,
actual result counts and last-result identity are required witnesses. History rows can
repeat an ID with increasing trade sequences; local ID references can also repeat. The
report separates rows, distinct IDs and identity-distinct String objects.

The temporary observer saves only scalar/category snapshots of fullHistoryIds, localIds,
allOrphans and their identity-deduplicated union. It does not save those input roots.
Instrumentation measures selected shallow objects. Closed Java 21 reference inventories
follow HashSet/HashMap nodes, ArrayList/SubList storage, String backing arrays, the result
record and its Instant. Unknown layouts/types refuse. The shared HashSet static PRESENT
sentinel is excluded explicitly. Array base offsets include header/length/pre-element
padding; payload and trailing padding are separate. Shared empty arrays and repository
String ownership mean these graph sums do not establish exclusive retention.

After the method returns, the diagnostic walks the result actually held by
`service.lastOrphanSweep()`. It reports view class/visible IDs, root list size, backing
array capacity/non-null slots, references beyond the visible view and selected graph
bytes/String counts. A throwaway result with an ArrayList copy of the identical reported
IDs/count metadata is measured separately. This is a discriminator, not a production fix
or a claim that a particular number of bytes would be freed by GC.

The default fixture has 200 distinct history IDs, two HTTP rows per history ID and 700
orphans. Current source copies the reported prefix into an independent mutable ArrayList
when truncating; counts/order/repeats and the <=cap path are unchanged. The actual reporting cap is 500, read from the compiled service. On Microsoft
Java 21.0.12/macOS/aarch64 with compressed references and 8-byte alignment, the fixed last
result reports 500 IDs and reaches only those 500 Strings through a 500-slot backing array.
Its selected graph measures 26096 shallow bytes, matching the bounded-copy graph.

Immutable baseline evidence in coordination review-evidence/ri24-projector-memory-20261008
records the earlier SubList path: 700 Strings/capacity 823/200 hidden slots, 37024 selected bytes.
The updated suite retains a separate disposable original-style source control with identical
counts/visible output; it reaches the full parent and must fail current bounded-backing checks.
Neither profile measures exclusive/dominator-retained heap or production impact.

Managed RunRegistry/JDBC Trade-row materialization is compiled but not exercised. The
selected unmanaged method requires only the repository ID query. Managed source behavior,
full service/HTTP/Jackson/metrics/native graphs, all temporary/page/parser/JIT allocation
traffic, and full heap/dominator analysis remain explicitly unavailable. No production
sizing, storage/replay/liveness/streaming fix or financial/performance/HA claim is made.

Bounds: 0..2000 distinct history and orphan IDs, combined <=3000; repetitions 1..2 and page
size 16..256. Maximum local rows 6000/history rows 4000. Compiler/archive/fixture JVM heaps
are capped at 128/64/128 MiB and 45/20/35-second timeouts. Selected graphs refuse above 25000
objects or 16 MiB shallow bytes. Source/dependency/class/probe/JVM hashes/layout/config are
recorded and source/dependency bytes checked again after execution. Ambient JVM/proxy
options are removed from owned children. The executor/listener/HttpClient/meter registry
are closed; parent timeout kills/reaps the child and temporary files are removed on normal
Python exit. Parent SIGKILL cannot guarantee Python cleanup. Only requested JSON/evidence
persists; there is no retained fixture service.

The fifteen checks exercise empty/no-orphan/small/700/1200-orphan/repeated-ID profiles,
actual paging/populations, selected graph growth, hidden backing references, shared-root
identity dedup, source-pin drift, missing categories and unexecuted reports. A disposable
original-style source control reintroduces the parent view and must fail current bounded
retention checks. A wrong observer pointed at allOrphans instead of lastOrphanSweep must
also refuse.
Set `RECON_MEMORY_TEST_EVIDENCE=/tmp/recon-memory-tests` to save raw profiles and the negative
source/control output. No negative source change is applied to the checkout.


Focused uninstrumented service regressions live in the operative YU18 test layer at
src/test/java/finos/traderx/tradeprocessor/service/ReconciliationOrphanRetentionTest.java.
They cover above-cap storage and mutable prefix, repeated IDs, empty/below/at-cap behavior
and replacement of lastResult by another sweep. Tests use owned HTTP/read-only repository,
close the HttpClient/listener/meters and require no database. The fix does not bound full
history/local/orphan materialization while the sweep runs.
