# Quickstart: YU10-fix-ingress

## Local (kind)

1. Generate and start the state (fresh jars are built by the local harness):

   ```bash
   bash pipeline/generate-state.sh YU10-fix-ingress
   bash generated/code/target-generated/scripts/start-state-YU10-fix-ingress-generated.sh \
     --provider kind --without-sail
   ```

2. Wait for the order-matcher to be Ready (`kubectl get pods -n traderx -w`). The FIX acceptor
   listens once readiness passes — logon before that is refused, exactly like HTTP traffic.

3. Mint a JWT for the FIX client (same dev-token infrastructure the REST demos use) and export
   the session identity. The kind manifest ships the demo mapping `BENCH01:11413`:

   ```bash
   FIX_JWT=$(curl -s -X POST http://localhost:8080/order-matcher/auth/dev-token \
     -H "Content-Type: application/json" -d '{"user":"user01","accountId":11413}')
   ```

4. Run the session proof (logon, order → ExecutionReport, cancel, status, duplicate rejection):

   ```bash
   FIX_JWT="$FIX_JWT" bash scripts/bench/yu10-fix-session.sh
   ```

5. Throughput (completed D→8 lifecycles; alternate sides to stay clear of risk caps):

   ```bash
   FIX_JWT="$FIX_JWT" SIDES=alternate node scripts/bench/fix-load.mjs --secs 60
   ```

## Demonstrating this state's behavior

- **Fail-closed logon** — a wrong password or unmapped CompID never gets a session:

  ```bash
  FIX_JWT=not-a-jwt bash scripts/bench/yu10-fix-session.sh          # expect: logon rejected
  FIX_COMP_ID=NOBODY FIX_JWT="$FIX_JWT" bash scripts/bench/yu10-fix-session.sh   # same
  ```

- **Restart reconciliation** — kill the pod mid-session and watch the resend window reconcile:

  ```bash
  kubectl delete pod -n traderx -l app=order-matcher
  # yu10-fix-session.sh --resume reconnects after readiness, verifies sequence recovery,
  # re-requests order state with OrderStatusRequest, and proves a same-ClOrdID retry is
  # answered as a duplicate, not re-executed.
  FIX_JWT="$FIX_JWT" bash scripts/bench/yu10-fix-session.sh --resume
  ```

- **The FIX/REST equivalence** — an order admitted over FIX appears in the same blotter, risk
  state, journal, and DB projection as a REST order; `scripts/bench/yu10-fix-session.sh` checks
  the DB projection row as its final step.

## Stopping

```bash
bash generated/code/target-generated/scripts/stop-state-YU10-fix-ingress-generated.sh --provider kind
```
