# Non-Functional Delta: YU14-listed-equity-options (vs YU13-limit-order-book)

All YU13 non-functional requirements carry forward unchanged (engine-latency artifact,
zero-allocation hot path, throughput baseline discipline, bounded lazy book memory).

## Added

- NFR-LEO01 — zero-allocation steady state holds with option securities in the traffic mix:
  Epsilon no-GC runs and all four allocation gates pass. The multiplier is a preallocated dense
  array; registration-path derivation is cold.
- NFR-LEO02 — determinism: multiplier derivation is a pure function of the committed ticker
  bytes (no clock, locale, or iteration-order dependence); identical logs produce identical
  multipliers, reservations, and executed exposure on every member.
- NFR-LEO03 — no throughput regression against the YU13 baseline under the same bench
  parameters; the decision-path delta is one dense-array read and one multiply.

## Changed

- Nothing tightened or relaxed from YU13.
