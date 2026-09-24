# Recovery identity specification

Status: review needed. Owner: Codex RI-06. Dependencies: existing EOD bundle/coordinator contracts. This bounded feature enforces the existing one-epoch-per-receipt-directory rule, not platform identity issuance.

- FR-RI01: A validated receipt directory is bound to its caller-supplied epoch within retained inbox custody. Repeated or restarted imports must preserve the binding; conflicting epochs fail before bundle publication.
- FR-RI02: Validate receipt, artifact hashes, common cut and manifest before adopting identity. Bad input must not reserve an epoch. Source receipt bytes and original bundles remain unchanged.
- FR-RI03: Persist binding transactionally before publication. Interrupted publication retains identity; concurrent conflicting adopters cannot both succeed. Corrupt/unsupported lineage storage fails closed.
- FR-RI04: Fresh independently provisioned receipt directories may reuse numeric counters under different explicit epochs. Preserve historical jobs/results and reject cross-epoch result identity. Late and duplicate records retain original scope.
- NFR-RI01: Single-host local custody, private SQLite store, no distributed authority, financial validation, automatic reset or epoch creation. Existing legacy input/CLI formats remain accepted.
- NFR-RI02: First-use provenance is an operator assertion, not inferred from receipts. Separate inboxes, directory copies and removed binding databases are outside the guarantee. Document migration before use.

Acceptance: SC-RI01 same-directory relabel refusal; SC-RI02 restart/dedup; SC-RI03 distinct fresh directory with historical result preservation; SC-RI04 invalid and stale-boundary refusal; SC-RI05 before/after-commit interrupted binding/publication; SC-RI06 conflicting concurrent first imports; SC-RI07 corrupt/versioned/symlink store refusal; SC-RI08 cross-epoch result refusal. Executable tests live in the shared YU18 runtime override tests directory.
