# Exactly-once client plan

The venue already had the hard part: a replicated idempotency table keyed by the client's own id, consulted before
risk, that survives gateway restarts and leader elections because it is engine state. A client gets exactly-once
by keeping every un-acked order and resending it with the same key until it is answered, on any gateway. What was
missing was the client (the hxb loadgen treated a lost connection as fatal and an ambiguous ack as final), a FIX
answer for a retry whose original aged out (it read as a reject of an order that executed), and a written window:
the table is bounded by count, so how long a resend may take depends on the rate. See ADR-091.
