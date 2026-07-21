# Contract Delta: YU14-listed-equity-options (vs YU13-limit-order-book)

## Wire contracts

- `SymbolRegisterMessage` (SBE template 7): ticker field widens `char[16]` → `char[32]`
  (blockLength 24 → 40). Template id, requestId field, and registration semantics unchanged.
  The YU14 tree is schema-consistent end-to-end; the widened field is not wire-compatible with
  a YU13 peer, which is the inherited single-version cluster rule (every member and gateway run
  the same generated tree).
- Egress ack: unchanged (24 bytes, kinds, class bytes 21/22/23 exactly as YU13).
- `InputEventMessage` and every other SBE template: unchanged.

## Snapshot contract

- Header format identifier: 2 → 3. A loader presented with any other format fails closed.
- `T_SECURITY` record: 5 → 6 columns —
  `{securityId, enabled, restricted, lastPrice, lastPriceTime, contractMultiplier}`.
  Restore rejects multiplier < 1 (recovery aborts; fail closed).
- All other record types (header geometry fields, T_ORDER, T_POSITION, T_PRICE, T_BOOK,
  T_SYMBOL, T_IDEMPOTENCY, T_POLICY, T_ACCOUNT, T_END): byte-identical to YU13.

## REST contracts (gateway — inherited, no code change)

- `/seed`, `/orders`, `/orders/batch`, `/trades`, `/ready`, `/orders/{ref}`: request/response
  shapes unchanged. Option contracts use their OCC symbol wherever a ticker is accepted.
- Risk rejections surface the same RiskReason vocabulary; what changes is when the
  notional-driven reasons fire (at multiplied exposure) — the multiplied notional of an option
  order can now trigger ORDER_NOTIONAL / CREDIT_LIMIT / CONCENTRATION_LIMIT at premium prices.

## Reference-data contracts (new)

- `reference-data/instruments.csv`:
  `ticker,type,underlying,expiry,callPut,strike,multiplier,currency` — one row per seeded
  instrument; derivable columns are pure functions of the ticker.
- `reference-data/counterparties.csv`: `accountId,counterpartyId,nettingSetId,currency` — one
  row per seeded account; positions join by accountId at extract time.
- Derived notional: `position quantity x last price x contract multiplier`.
