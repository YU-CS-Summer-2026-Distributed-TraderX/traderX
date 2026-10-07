# Book-memory accounting

Status: integrated locally, 2026-10-07; see the evidence and limits below.
integration pending. Owner: Codex book-memory lane `01a11848-bde9-7d53-8230-133c1aee23c2`.
Parent: YU18-risk-integration, inheriting YU17-otc-rates.

The [diagnostic tool](../../../../scripts/diagnostics/book-memory/README.md) measures
current LimitBook objects in bounded disposable JVM fixtures and reports array payload,
layout overhead, shallow object sums and separate external pool ownership. It leaves
constructor allocation traffic, dominator-retained heap and production engine memory
explicitly unavailable. This supplies a repeatable local accounting step for RI-24;
deployment-profile capacity and production sizing remain open.

Source: `scripts/diagnostics/book-memory/{book_memory.py,BookMemoryProbe.java,test_book_memory.py}`.
Measured units: current YU18 LimitBook/RestingOrder/InputEvent/OrderTypes, inherited
YU02 Px and YU03 ReservationHolder. MatchingEngine supplies metadata/default only.
Dependencies: Python 3, installed HotSpot JDK with java/javac/jar, no downloaded libraries.
Interface: read-only source inputs, schema-1 JSON in bytes, nonzero exit on failure.
No service, generation input, core parameter, wire format, rig or production limit changes.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), the parent
[quickstart](../../quickstart.md), and [RI-24](../../../../issues/risk-integration/24-bounded-runtime-memory.md).

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
