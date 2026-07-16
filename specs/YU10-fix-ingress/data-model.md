# Data Model: YU10-fix-ingress

## FIX ↔ internal field mapping

### Inbound `NewOrderSingle (35=D)` → order-new input event

| FIX tag | Field | Internal | Rule |
|---|---|---|---|
| 11 | ClOrdID | ledger key (with session) | Required; duplicate per session → rejected (FR-FIX10) |
| 49 | SenderCompID | session → `accountId` | Resolved at logon via `FIX_SESSION_ACCOUNTS`; `Account(1)` if present must equal the mapped account or the order is rejected |
| 55 | Symbol | `securityId` | Resolved through the existing symbol table; unknown symbol → application rejection (ER Rejected) |
| 54 | Side | `side` | `1`=Buy, `2`=Sell; others → session Reject |
| 38 | OrderQty | `qty` | Positive integer; else session Reject |
| 44 | Price | `limitPx` | Required (`OrdType(40)=2` limit only); fixed-point conversion identical to the REST path |
| 60 | TransactTime | — | Accepted, not carried (event time is assigned at sequencing, as for REST) |

### Inbound `OrderCancelRequest (35=F)` → cancel input event

| FIX tag | Field | Internal | Rule |
|---|---|---|---|
| 41 | OrigClOrdID | ledger lookup → `orderRef` | Unknown → `OrderCancelReject(9)` with `CxlRejReason` |
| 11 | ClOrdID | ledger key for the cancel itself | Duplicate rule applies |
| 49 | SenderCompID | ownership check | Session's account must own the order or `OrderCancelReject` |

### Inbound `OrderStatusRequest (35=H)` → read-model lookup (no ring publish)

| FIX tag | Field | Rule |
|---|---|---|
| 11/41 | (Orig)ClOrdID | ledger lookup → orderRef → in-memory read model → `ExecutionReport` with `ExecType(150)=I` |

### Outbound `ExecutionReport (35=8)` from output events

| Output event | ExecType(150) / OrdStatus(39) | Notes |
|---|---|---|
| ORDER_ACCEPTED | `0` New / `0` New | |
| ORDER_PARTIALLY_FILLED | `F` Trade / `1` Partially filled | `LastQty(32)`, `LastPx(31)` from the fill |
| ORDER_FILLED | `F` Trade / `2` Filled | |
| ORDER_CANCELED | `4` Canceled / `4` Canceled | |
| ORDER_REJECTED | `8` Rejected / `8` Rejected | Risk reason in `Text(58)` (e.g. `POSITION_LIMIT`, `CREDIT_LIMIT`, `PRICE_COLLAR`) |
| ORDER_NOT_FOUND (from cancel) | → `OrderCancelReject (35=9)` | `CxlRejReason(102)=1` unknown order |

Every outbound report carries `ClOrdID(11)`, `OrderID(37)`=internal orderRef, `Symbol(55)`,
`Side(54)`, `LeavesQty(151)`, `CumQty(14)`, `AvgPx(6)`.

## Correlation ledger record (append-only, on PVC)

Fixed-length binary record, little-endian, in `${FIX_DATA_DIR}/clordid-ledger.dat`:

| Offset | Field | Type |
|---:|---|---|
| 0 | sessionKey hash | int64 (FNV-1a of `SenderCompID:TargetCompID`) |
| 8 | inputSeq | int64 |
| 16 | orderRef | int32 |
| 20 | clOrdIdLen | int16 |
| 22 | clOrdId | UTF-8, max 64 bytes, zero-padded |
| 86 | pad | 2 bytes (88-byte record) |

Written before ring publish; forced with the same amortized-batch discipline as the journal;
rehydrated fully at startup into the in-memory maps (`(session, clOrdId) → inputSeq/orderRef`
for duplicates and cancels; `inputSeq → (session, clOrdId)` for the report handler).

## Session configuration

| Env | Meaning |
|---|---|
| `FIX_ACCEPTOR_PORT` | Acceptor TCP port (default 18130) |
| `FIX_SESSION_ACCOUNTS` | `COMPID:accountId[,…]` — the complete set of CompIDs allowed to log on |
| `FIX_DATA_DIR` | FIX store + ledger directory (default `/var/lib/traderx-lmax/fix`) |
| `FIX_TARGET_COMP_ID` | Server CompID (default `TRADERX`) |

QuickFIX/J session settings live in `fix/acceptor.cfg` generated from these envs at startup:
FIX.4.4, file store + file log under `FIX_DATA_DIR`, `PersistMessages=Y`, heartbeat 30s.

## No changes

`InputEvent`, `OutputEvent`, `RestingOrder`, the journal record, the replication record, the
snapshot format, and every database table are unchanged by this state.
