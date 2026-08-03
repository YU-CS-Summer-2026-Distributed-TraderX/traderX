# Non-Functional Delta: YU15 over YU14-listed-equity-options

Every inherited non-functional requirement is retained.

## Hot path

- NFR-RXT01: The marker SHALL be routed by template id ahead of the order-flow branch and SHALL add
  no allocation to the ordinary apply path. `noGcTest`, `riskNoGcTest`, and all four allocation
  gates stay green.
- NFR-RXT01a: The cut render is O(positions) and allocates freely; it is a cold path taken once per
  EOD batch and is deliberately kept off the order-flow branch.
- NFR-RXT02: Taking an extract SHALL be safe while the cluster is serving traffic. Correctness of
  the emission is enforced by the quiescence witness, never by stopping the engine.
- NFR-RXT02a: The leader SHALL NOT block the apply thread on the network when publishing the cut.

## Determinism

- NFR-RXT05: Decimal values SHALL be exact — integer ticks and `BigDecimal`, never floating point —
  so identical inputs cannot produce differing bytes across JVMs or architectures.
- NFR-RXT05a: Ordering SHALL be explicit rather than relying on hash or probe iteration order,
  even where that order is incidentally stable across members.
- NFR-RXT05b: No wall-clock value SHALL appear in the cut or the fixture body. Operational facts
  about a build belong in the announcement.
- NFR-RXT05c: Text SHALL be US-ASCII with `\n` line endings.

## Failure behavior

- NFR-RXT03: A failed extract SHALL leave no partial object and no announcement, and SHALL be
  retried by durable redelivery.
- NFR-RXT03a: Delivery SHALL be write-once, enforced by the sink rather than by a prior existence
  check, so a redelivered trigger cannot replace a fixture already scored against.
- NFR-RXT04: The producer SHALL tolerate its dependencies being unavailable at start, retrying
  rather than exiting.
- NFR-RXT04a: The producer SHALL open a cluster session per batch rather than hold one idle between
  runs, so a leader change during the day cannot leave it pointed at a former leader.

## Availability

- NFR-RXT06: A member restarting during an EOD window — when the cluster is by definition idle —
  SHALL rejoin the Service without waiting for trading to resume.

## Scale

- NFR-RXT07: The cut is carried in one message, bounded by the broker's maximum payload — roughly
  15k position rows at the 1MB default. The declared row count is what detects an overrun.
