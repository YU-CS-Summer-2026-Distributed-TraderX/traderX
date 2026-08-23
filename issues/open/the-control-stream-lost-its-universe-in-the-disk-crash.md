# The durable control stream lost its universe in the disk crash, and nothing refills it

**Raised 2026-08-24** while running the proof suite after the 2026-08-23 disk-full crash. Filed as
its own issue because the yu04 proofs' SKIP text misattributes it ("CONTROL_FEED_SUBSCRIBER=0 by
default" — the env is 1 and the subscriber is consuming).

## What was measured

The YU04 capability gate needs the gateway's control replica to hold >64 securities. It holds ~10:

```
reference-data /instruments/control-snapshot   count 541   watermark 1213   (the DB — intact)
gateway        /risk/control/snapshot          count 10    watermark 1213   (the replica)
```

The gateway is AT the stream tail (watermarks agree), so this is not lag and not a dead subscriber.
The JetStream stream itself no longer contains the 510-security history: NATS lost its state in the
disk crash, the re-created stream holds only the deltas published since, and the subscriber's
"replay the durable stream from the start on every connect" bootstrap (yu04-offline-catchup's own
documented mechanism) faithfully replays a stream that now starts near the end.

Restarting reference-data does not help — the outbox relay publishes rows `WHERE published = false`,
and all 1073 outbox rows are marked published in the DB (which survived). Restarting the gateway
does not help — it replays the same truncated stream. Both were tried.

## The one-line repair, not yet run

The outbox table IS the durable history, and it is intact. Resetting the publish cursor makes the
relay republish everything in version order, which restores the stream's contents:

```sql
UPDATE stocks_control_outbox SET published=0;
```

(via `kubectl --context kind-traderx-yu12-cluster -n traderx exec deploy/eod-price-db -c mariadb -- mariadb -utraderx -ptraderx traderx`.)

Versioned control updates are last-wins on the consumer side, so a replayed history is idempotent —
this is exactly what a fresh gateway replay already does. **Not run because the permission
classifier refused the bulk UPDATE in the running session** (correctly cautious about a bulk DB
mutation); per project rule the command goes to the coordinator instead of being worked around.

After it runs: watch `/risk/control/snapshot` count climb toward 541, then
`bash scripts/yu15/run-proofs.sh yu04` — both proofs should stop SKIPping.

## Same family

`issues/open/a-nats-restart-silently-kills-every-eod-durable.md` — the broker's state is a single
point whose loss is silent; this is the control-feed instance of it.
