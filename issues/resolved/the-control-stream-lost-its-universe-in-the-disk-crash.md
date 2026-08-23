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

---

## RESOLVED 2026-08-23 — the repair ran and the universe is back

yaakov ran the `UPDATE stocks_control_outbox SET published=0;` above. Verified end to end by the
coordinator, not relayed:

| Layer | Before | After |
|---|---|---|
| gateway `/risk/control/snapshot` | count **10** | count **542** |
| JetStream `TRADERX_CONTROL_SECURITY` | truncated | 1113 msgs, consumer acked all, 0 pending, 0 redelivered |
| gateway log | — | **1122** `CONTROL-FEED applied` lines, through version 1223 |
| `run-proofs.sh yu04` | 2 skipped | **2 passed, 0 skipped, 0 failed** |

The gateway's replica reaching 542 matches the 542 **distinct tickers** in the outbox (1073 rows are
versioned updates of those 542), so the replica is complete, not merely larger.

### The measurement trap this hid behind, worth keeping

**The outbox table alone cannot tell you whether the repair worked.** Before: 1073 rows, all
`published=1`. After: 1073 rows, all `published=1` — byte-identical readings for "never ran" and
"ran and fully republished", because `markPublished` runs on a successful publish and there is no
`updated_at` column. The discriminators that do work are all *downstream*: the stream's message
count, the consumer's ack floor, the gateway's replica count, the `CONTROL-FEED applied` line count.

A second trap sat behind the first: **`deploy/order-matcher` does not exist on this rig** —
`svc/order-matcher` fronts `cluster-gateway`. `kubectl logs deploy/order-matcher` returns empty and
exits 0, which reads exactly like "the gateway logged nothing about securities." It is already in
`CLAUDE.md`; it still cost a step here.

The republish was *not* swallowed by JetStream dedupe, which was the live risk — the publisher sends
`Nats-Msg-Id: security:<version>` and the stream's `duplicate_window` is 120s, far shorter than the
age of the original publishes, so the replays were accepted rather than discarded. Had the window
been longer than the gap, the rows would still have flipped to `published=1` with nothing delivered.
