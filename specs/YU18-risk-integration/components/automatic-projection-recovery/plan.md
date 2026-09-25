# Automatic projection recovery plan

Operative last-wins layers (YU18 is the tip; each file below exists only at YU18 or YU18 owns
the full-file override): order-matcher `ClusterRecon`, `ClusterNodeMain`; trade-processor
`TradeService`, `EventRecoveryService`, `LocalRunPeerClient`, `RunPeerClient`, `TradeRepository`,
`OrderRepository`; new `AutomaticProjectionRecovery`, `ProjectionCompletenessController`,
`application-auto-recovery-local.properties`; migration `ri06-automatic-recovery.sql`;
launcher `recovery-identity/test-live-automatic-recovery.sh`. The inherited
`NatsJSONSubscriber` is not overridden; reconnect edges are observed through its public status.

1. Member: bounded whole-command page with chain digest over the strict archive replay.
2. Consumer: cursor, fenced lease, one-transaction page apply, scheduling, status.
3. Additive migration, disposable opt-in profile.
4. Real-MariaDB fixture controls; live proof with child-JVM consumers (SIGKILL/SIGSTOP and
   exact-point process halts) under continuous trading; archive comparison.
5. Regenerate, parity, docs, RI06/backlog, evidence manifest, commit, READY_FOR_REVIEW.
