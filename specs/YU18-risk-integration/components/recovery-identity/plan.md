# Recovery identity plan

Owner: Codex RI-06. Status: review needed; local verification complete.

1. Preserve the audited baseline and failing retained-SQL collision; deliver receipt custody guard (5ba9d584, accepted separately).
2. Introduce conflict-aware transactional booking before new writers (6b1641c1).
3. Add immutable run descriptors, evidenced legacy adoption, snapshot identity and scoped SQL/read interfaces. Preserve legacy IDs and historical data.
4. Bind publishers, reconciliation, derived tap output and receipt identity to the descriptor. Announce versioned APIs/topics; leave managed UI rollout separate.
5. Implement durable prepare/freeze/verify/select/activate transitions with admission barriers and retry after lost replies. Compare a nonempty frozen archive witness before selecting fresh scope.
6. Verify generated composition, real MariaDB/JPA, actual local consensus restart/replay, fault-injected transition interruptions, compatibility suites and allocation gates. Deliver staged commits and hashed evidence for coordinator review.

The YU18 layer owns full-file overrides copied from their preceding operative layers. The renderer now includes recovery-identity and position-service. No ancestor or retained rig is mutated. See the runbook for exact schema, configuration, compatibility and proof limits.
