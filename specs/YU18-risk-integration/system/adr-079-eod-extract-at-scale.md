# ADR-079: The EOD extract at production scale

Status: Accepted, 2026-10-25. Amends ADR-055 (YU15). Each section is one gap found by the
2026-10-25 hxb campaign and closed on `claude/eod-risk-gaps`. Evidence and numbers are in
`independent-baseline/results-20261025-open/EOD-RISK.md`.

## 1. The cut travels in parts (item 2)

**Context.** ADR-055 sent the cut as one NATS message and noted the 1 MB default `max_payload` as a
ceiling for later. It is reached at about 30K positions (34 bytes a row). Past it, jnats refuses
the publish (`Message payload size exceed server configuration`), and the leader's bridge counts a
drop. The extract job then times out on every JetStream redelivery, so the EOD extract never
completes.

**Decision.** The leader sends the cut as parts sized from the server's own `max_payload`. Each part
is a `#cutpart seq= part= parts= bytes= sha256=` header line followed by a slice of the cut. The
consumer (`RiskExtractCutPublisher.Assembly`) yields the cut only once every part is present and the
concatenation has the declared length and SHA-256. A missing part therefore times out, and a
corrupt, truncated or contradictory part is refused. The cut's `rows=` self-count is unchanged and
still checked downstream. A pre-YU18 single `#cut` message is still accepted.

**Rejected:** raising `max_payload`. The NATS server caps it at 64 MB and recommends 8 MB at most,
so it only moves the ceiling (a 1M-position cut is 36 MB), and it would have to be raised on every
NATS deployment. The cut subject has one consumer, `RiskExtractMain`, so parts change no external
contract.

## 2. The cut renders on the apply thread, in bytes (item 3)

**Context.** The concern was that a long render on the deterministic apply thread would elect at
`CLUSTER_HEARTBEAT_TIMEOUT_MS=200`. Measured locally (laptop): the StringBuilder render took 34 /
104 / 530 ms cold at 10K / 100K / 1M positions, and allocated ~188 MB at 1M. In the member, which
also splices the run-identity header and re-encodes the cut for NATS, that is ~300 MB.

**Finding 1, the premise is false for the thread.** `ApplyStallElectionTest` runs three members at
the rig's timings (heartbeat 50 ms, timeout 200 ms, election 100 ms). A 600 ms sleep, and a real
~600–800 ms 1M-position render, on every member's apply thread leave the leadership-term count
unchanged. A positive control (leader killed) proves that the probe sees elections. The consensus
module is its own agent, and heartbeats and commit do not wait on the service. Copying state to
render it on another thread would buy nothing.

**Finding 2, what can elect is a JVM-wide pause, and allocation is what causes one.** With a 200 MB
live set under the members' `-Xmx512m`, the old render's allocation ends in an `OutOfMemoryError`.
Every member renders, so that would take down all three at once.

**Decision.** `RiskExtractCut.renderBytes` writes the cut once, into one exactly-sized US-ASCII array
(measure pass, then write pass). It sorts primitive packed keys, takes the run-identity fields as a
header suffix instead of splicing a copy, and hashes the bytes without re-encoding. It is byte for
byte the old renderer: `RiskExtractCutBytesTest` keeps the old code as its oracle across 200 random
books plus the `long` extremes, because one changed byte would break FR-RXT10 for every past cut.
At 1M positions it allocates 50 MB (output + sort index) instead of ~188 MB, and the test fails if
allocation grows past output + 16 B/row.

**Rollout (items 2 and 3).** The cut bytes and the replicated state are unchanged, so this needs no
fresh epoch, and members can be rolled in any order. The wire format did change: an old
`risk-extract` job ignores `#cutpart` messages and would time out. A new job still reads an old
member's single message. So roll the `risk-extract` Deployment first, or with the members (both
ship in the cluster-node image).
