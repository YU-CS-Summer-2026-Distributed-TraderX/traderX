We reject in two layers: pre-sequencer at the Gateway, and then authoritatively in the BLP after sequencing.

Gateway-side reject conditions:

- `CONTROL_STATE_STALE`: replicas not ready, feed disconnected too long, or control state cannot be trusted
- `KILL_SWITCH`
- `UNKNOWN_ACCOUNT`
- `ACCOUNT_DISABLED`
- `NOT_ENTITLED`
- `UNKNOWN_SECURITY`
- `SECURITY_DISABLED` (includes halted)
- `RESTRICTED`
- `INVALID` (bad/non-positive quantity or price shape)
- `ORDER_SIZE`
- `PRICE_MISSING`
- `PRICE_STALE`
- `PRICE_COLLAR`
- `ORDER_NOTIONAL`

That is the fast local screening layer in [GatewayReplicaStore.java]

BLP-side authoritative reject conditions:

- `KILL_SWITCH`
- `UNKNOWN_ACCOUNT`
- `ACCOUNT_DISABLED`
- `NOT_ENTITLED`
- `UNKNOWN_SECURITY`
- `RESTRICTED`
- `INVALID`
- `ORDER_SIZE`
- `PRICE_MISSING`
- `PRICE_STALE`
- `ORDER_NOTIONAL`
- `CREDIT_LIMIT`
- `POSITION_LIMIT`
- `CONCENTRATION_LIMIT`
- `CAPACITY`
- duplicate/idempotent replay path for prior `clientOrderId` decisions

That logic is in [BlpRiskState.java]

