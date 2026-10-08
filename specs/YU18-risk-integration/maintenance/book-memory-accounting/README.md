# Book-memory accounting

Status: standalone book tool integrated locally on 2026-10-07; separate real-engine
component extension implemented/source-tested, pending coordinator review/integration. Owner: Codex book-memory lane `01a11848-bde9-7d53-8230-133c1aee23c2`.
Parent: YU18-risk-integration, inheriting YU17-otc-rates.

The [diagnostic tool](../../../../scripts/diagnostics/book-memory/README.md) measures
current LimitBook objects in bounded disposable JVM fixtures and reports array payload,
layout overhead, shallow object sums and separate external pool ownership. It leaves
dominator-retained heap and production/full-member memory explicitly unavailable. The
separate engine profile records a qualified first-constructor allocation window and a
partial component union with explicit supplied-root/metrics/ring exclusions. This supplies a repeatable local accounting step for RI-24;
deployment-profile capacity and production sizing remain open.

Source: `scripts/diagnostics/book-memory/{book_memory.py,BookMemoryProbe.java,test_book_memory.py}`
plus separate `{engine_memory.py,EngineMemoryProbe.java,test_engine_memory.py}`.
Standalone book profile units: current YU18 LimitBook/RestingOrder/InputEvent/OrderTypes, inherited
YU02 Px and YU03 ReservationHolder. That profile reads MatchingEngine for metadata/default
only. The engine extension compiles sixteen current units and instantiates the actual
MatchingEngine, BlpRiskState, OutputPublisher and metrics with cached offline dependencies.
Dependencies: Python 3, installed HotSpot JDK with java/javac/jar; engine profile uses
explicitly pinned existing cached JARs, without downloads.
Interface: read-only source inputs, schema-1 JSON in bytes, nonzero exit on failure.
No service, generation input, core parameter, wire format, rig or production limit changes.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), the parent
[quickstart](../../quickstart.md), and [RI-24](../../../../issues/risk-integration/24-bounded-runtime-memory.md).

October 7 standalone book integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
