# Queued owner-task deadline plan

Status: implemented and locally verified; coordinator review pending. Owner: Codex RI23.

1. Verify accepted new checkout, inherited source ownership and all12 caller sites. Reconstruct the actual old onOwner/add/get/run lifecycle and record late mutations after timed-out/interrupted waiters.
2. Introduce an internal FutureTask subclass with an explicit atomic start-versus-retirement claim. Retire/remove only unstarted tasks on waiter timeout/interruption. Preserve started execution and original exception/value semantics.
3. Exercise forced queue, already-dequeued, started-at-boundary and running interruption scenarios with latches and reaped owned threads. Compare actual JDK cancel(false), and run omission/naive-cancel controls.
4. Generate only this isolated checkout; verify owner/test/doc parity and byte identity for core/NodeMain/ACK/pipeline regions. Run focused submission/encoding/ACK/correlation suites and state gates. Allocation gates are relevant to core changes; this cold slice does not change those sources or claim their performance.
5. Commit exact owned paths as yaakov and deliver hashes/XML/commands on the board. Shared indexes and coordinator integration remain separate. Respect evening cutoff/floor before each bounded operation.

Pipeline follow-up extends reviewed76071dc8 in the same clean checkout. Cover actual PendingOrder acquire/enqueue/get/offer/ACK/reap with mock transport and latches; record default-budget old timeout plus explicit fixture budgets. Keep original handler/wrapper/ACK/reaper code and policies unchanged. New allocation/state is outside the core; do not reuse unrelated core gates to claim unchanged gateway allocation. Shared indices/integration remain coordinator-owned.
