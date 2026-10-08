# Reconciliation memory diagnostic

Status: local current-method diagnostic implemented/tested 2026-10-07; coordinator review
and integration pending. Owner: RI24 chat 01a11848-bde9-7d53-8230-133c1aee23c2.
Parent: YU18-risk-integration, inheriting YU17-otc-rates.

[Diagnostic commands and limits](../../../../scripts/diagnostics/reconciliation-memory/README.md)
measure temporary collection populations and the selected last-orphan-result backing graph
using the complete current service, synthetic loopback HTTP and a mocked read-only repository.
A SHA-pinned temporary observer hook exposes populated locals. The current saved prefix
is an independent bounded mutable copy; a separate original-style SubList source control
remains available to detect retention regressions.

Own source: scripts/diagnostics/reconciliation-memory/{reconciliation_memory.py,
ReconciliationMemoryProbe.java,test_reconciliation_memory.py,README.md}.
Dependencies: installed Java 21/Python 3 and seventeen exact cached JARs, no downloads.
Interface: bounded synthetic inputs -> schema 1 JSON bytes/populations/provenance; refusal
is nonzero. Managed JDBC materialization and full process/retained heap remain excluded. The narrow
reported-prefix copy fix leaves full temporary materialization and sweep semantics unchanged. Existing book/engine candidates remain independent and unchanged.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), parent
[quickstart](../../quickstart.md) and [RI24](../../../../issues/risk-integration/24-bounded-runtime-memory.md).
