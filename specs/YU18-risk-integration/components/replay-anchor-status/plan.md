# Replay anchor status plan

Status: implemented locally; coordinator review pending.

1. Reproduce accepted-base missing-prefix, implicit-context and missing-PVC success using offline
   fake commands; retain stdout/status/trace before editing.
2. Preserve fetch helpers, restrict only stamping's prefix, inspect actual member mount and
   claim/PV binding, derive timestamp, and check each configuration/producer operation.
3. Reproduce actual caller propagation. Kind startup and runner fail-hard need no executable
   change. Correct only replay-proof EXIT cleanup that swallowed required-mode failure and
   stale caller comments.
4. Run offline acceptance and defect-injection controls, component checks, required repository
   gates and document generation. Record precise source/generated/live limits.
5. Return a narrow commit and board READY for coordinator-controlled reconciliation. Shared
   component/backlog indexes are proposals only. No push, propagation or self-integration.

Dependencies: accepted base a0d6da0b. Reviewed RI17/RI18 source contracts are read-only references;
no dependency commits are required/stacked. RI20 5787073c caller diff was inspected but remains
unstacked. Coordinator must merge only the replay comment hunk into RI20's evolved runner and
preserve supervision, cleanup and provenance helpers. No NodeMain/core/wire/model/data edits.

The original historical issue suggested pod-start/current-clock fallback. This plan deliberately
implements the assigned storage-derived refusal contract; that historical suggestion is not
accepted engine identity design. Managed GKE/DR and retained migration remain separate queued work.
