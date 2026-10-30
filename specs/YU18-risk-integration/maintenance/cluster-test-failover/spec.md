# Cluster test failover race: requirements

- **FR-CTF01 — A failover is over for the test once its client is on the new leader.** After a leader
  kill, `ThreeMemberClusterTest` waits for a surviving member's service to report LEADER, then for the
  test's own `AeronCluster` to report that member as leader. It polls egress, and reconnects if the
  session closed. Nothing is offered before that. A client that never reaches the new leader still fails
  the test within 120 s.
- **FR-CTF02 — A timeout names its wait.** Each 120 s wait carries a label: the leader, the client on
  the new leader, every member's snapshot, catch-up, egress acks. The label is in the exception message,
  because Gradle's short format prints only the message and the `await` line.
- **NFR-CTF01 — Assertions unchanged.** Order references 1–8, the 14-trade lineage, wiped-member state
  equality and the 120 s bound are as before. The fix adds one wait. It does not retry, resend or extend
  a deadline. A cluster that does not elect, catch up or sequence still fails.

Out of scope: consensus timeouts, the production gateway's failover handling, and the in-process tests'
port-lock directory (`TestPorts` locks under `java.io.tmpdir`, so JVMs with different temp directories
do not coordinate).
