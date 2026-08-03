# Research: YU14-listed-equity-options

## Why options need no matching-engine change

Listed options trade on order books exactly as equities do: an option contract is a security
identifier with a two-sided book and price-time priority. `LimitBook` matches orders on a
`securityId` at a price and carries no instrument semantics, so the whole YU13 matching surface
— grid/band admission, crossing, partial fills, market-cancel, cancel/force-fill, snapshot of
the book — applies to an option contract unchanged. The state's engine delta is confined to the
risk gate's notional arithmetic.

## Why the risk engine needs this state

The downstream risk engine analyses quantization error across PV, Greeks, and VaR as numeric
precision drops. On a cash-equity-only book the Greeks are degenerate — delta is exactly 1 and
gamma/vega/theta/rho are zero — so nothing non-linear exists for reduced precision to compound.
Option positions give the portfolio genuine optionality to price. The extract additionally needs
counterparty ID, currency, and notional, which live on the same instrument/account surface and
are added here as reference data.

## Instrument identity: the OCC symbol

The listed-options world already solves instrument identity: the OCC option symbol encodes
root, expiry (yymmdd), call/put, and strike (x 1000, 8 digits) in the identifier itself. Using
its unpadded form as the security ticker means:

- one identifier names the contract end-to-end — seed, order entry, book, trade record, extract;
- underlying, strike, expiry, and call/put are *derivable from the identifier* and therefore
  need no storage anywhere, in or out of the cluster;
- the multiplier decision (option → 100, other → 1) is a deterministic pure function of the
  ticker string, computable identically on every member from the committed registration message.

The padded OCC form embeds spaces inside a comma-separated ticker list; the unpadded form is
used everywhere here. The fixed-width 15-character tail (`yymmdd` + `C|P` + 8-digit strike)
makes the parse unambiguous from the right; the remaining prefix is the underlying root.

## What enters the consensus log

The cluster needs only what matching and risk consume:

| Attribute | In cluster state | Basis |
|---|---|---|
| securityId / ticker | yes (inherited `SymbolRegister`) | matching identity |
| contract multiplier | yes — derived at registration, snapshotted | the notional math is in-cluster |
| underlying, strike, expiry, call/put | no | encoded in the identifier; nothing in-cluster reads them |
| currency | no | constant USD across the whole traded universe; a constant is not state |
| counterparty / netting set | no | keyed by accountId; joined to positions at extract time |

Everything that does enter cluster state round-trips in the snapshot and fails closed on
invalid values, per the inherited ADR-046 completeness discipline.

## Multiplier placement

`BlpRiskState` already holds dense per-security control state (`securityEnabled`,
`securityRestricted`, `lastPrice`, `lastPriceTime`) indexed by securityId. The multiplier is one
more dense array beside them, written on the cold registration path and read once per decision.
Reservation and executed-exposure records store multiplied notional, so `consume`/`release`
accounting and snapshot order rows carry the multiplier's effect without any format change to
order records; only the security snapshot record gains the multiplier column (format 3).

## The seeding path

The inherited gateway `/seed` endpoint registers, enables, and price-seeds arbitrary tickers
through sequenced ingress. Option seeding is therefore data, not code: POST the chain's OCC
tickers with their premium-scale prices. The single obstacle was the SBE ticker field's 16-byte
width (OCC symbols run ~19 characters); the field is widened to 32 bytes in this state's schema.
The engine's silent-reject admission gate (unknown security / missing price tick books nothing)
is exactly why the chain seeding script and the one-cross smoke proof exist as the state's first
acceptance step.

## Price scale

Premiums quote on the inherited 0.001 price grid (Px unit = 1e-6 dollars, grid = 1000 Px).
Standard US option increments ($0.01 / $0.05) are on-grid. Each securityId anchors its own price
band on its first limit order, so an option book anchored at premium scale (~$2.50) and its
underlying anchored at share scale (~$240) coexist without interaction.
