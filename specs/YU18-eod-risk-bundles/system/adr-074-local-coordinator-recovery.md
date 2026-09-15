# ADR 074: Local durable EOD coordination

Status: accepted for the local mock adapter on traderX-risk-integration.

SQLite stores logical jobs and attempts. An OS advisory lock spans discovery, execution, recovery
and status so a second local process cannot reclaim a live worker. This deliberately serializes
commands: status refuses while another command holds the lock. It needs no lease timeout or remote
service. Shared filesystems and distributed workers need a different coordination protocol.

Discovery snapshots immutable source bytes before queueing. A job's key includes the bundle identity
and versioned mock calculation profile. Result paths are per attempt, and publication precedes the
SQLite acceptance transaction. If the process exits in that gap, recovery validates the published
artifact and commits acceptance; it does not recompute. An interrupted attempt with no publication is
recorded and replaced. Invalid artifacts fail and remain available for inspection.

This proves process-restart recovery. Power-loss/filesystem durability of directory rename is not
claimed: the inherited publisher fsyncs file contents but does not fsync parent directories. No real
pricing result or Alex API is accepted by the current adapter.
