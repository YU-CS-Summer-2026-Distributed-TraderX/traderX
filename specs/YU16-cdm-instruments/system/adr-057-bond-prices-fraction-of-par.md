# ADR-057: Bond prices are stored as a fraction of par on every internal surface

Status: Accepted

## Context

Bond markets quote clean prices as a percentage of par (99.886), and quantities as face amount;
value is `face × clean ÷ 100`. The engine computes notional as
`Math.multiplyExact(Math.multiplyExact((long) quantity, validationPrice), multiplier)` with a
`long` per-security contract multiplier (YU14, ADR-053) that fails closed below 1 — the `÷100`
cannot be a multiplier. Storing the percentage would make a 100,000-face position price as
9,988,600 rather than 99,886: a 100× error, fixable only by teaching the deterministic core a
divisor — a snapshot field, a format bump, a fresh epoch, and a permanently forked arithmetic
path for one asset class.

## Decision

Bond prices are stored as a **fraction of par** everywhere inside the system: quoted 99.886% is
0.998860, which is 998,860 ticks at the 1e6 scale. The contract multiplier for a bond is 1, so
`quantity(face) × price(fraction ticks) × 1` is the correct notional through the unchanged risk
gate, and a bond is arithmetically identical in shape to an equity. The percentage is display
only — multiply by 100 and append the sign.

The convention binds four surfaces, which must agree: the price publisher (emits fractions, at
six-decimal tick precision — the 3-dp rounding remains the equity contract), the engine (stores
fraction ticks, unchanged code), the read model (persists fractions in six-decimal columns),
and the risk extract (renders fractions at scale 6).

## Consequences

- The deterministic core does not change: no snapshot field, no format bump
  (`SNAPSHOT_FORMAT` stays 4), no fresh epoch, no PVC wipe, no mixed-version divergence window.
- SQL price columns widen from `DECIMAL(18,3)` to `DECIMAL(18,6)` — three decimals on a
  fraction is one decimal of percentage, which would silently round 0.998860 to 0.999.
- The publisher's Treasury tick conversion must bypass the inherited 3-dp HALF_UP path for the
  same reason.
- Every consumer that renders a bond price owns a ×100; a consumer that forgets shows 0.999
  where it means 99.886% — visible and wrong, rather than silently mispriced by 100×.
- Seed and reference data quoted in percent (auction provenance) is converted once at load; the
  provenance fields keep the quoted form so the source PDF remains checkable.
