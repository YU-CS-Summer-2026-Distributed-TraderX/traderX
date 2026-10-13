# Requirements

- SET-MEM-01: Only due Processing trades transition during automatic settlement. Preserve scheduled settlement dates, economic/provenance fields, and future, NULL, Rejected and already-settled rows. Preserve the existing force override semantics.
- SET-MEM-02: Bound returned rows, SQL parameters and transaction size independently of total history. No full entity materialization or outer sweep transaction. A fixed cutoff applies to one invocation; restart from the earliest remaining due row without OFFSET.
- SET-MEM-03: Acquire the shared projection database write lock before selecting or changing rows, including force settlement. Commit each batch before counting success. A failed batch rolls back without undoing earlier committed batches; retries and process restarts resume safely.
- SET-MEM-04: Supply an idempotent (state,settlementdate,id) index for both new and retained schemas. Apply the migration before deploying the correction. Measure actual MariaDB query access and bounded batch memory/lock duration.
- SET-MEM-05: Bound each invocation by maximum batches and a between-batch elapsed-time budget. The time budget is not an interrupt or a hard transaction/lock-wait deadline. Measure backlog progress and writer contention as well as memory.
- SET-MEM-06: Validate against a fresh copy of the same approved archive, preserve original state, record artifact identity and cleanup to zero nodes. A settlement test does not establish full retained delivery or matcher capacity.
