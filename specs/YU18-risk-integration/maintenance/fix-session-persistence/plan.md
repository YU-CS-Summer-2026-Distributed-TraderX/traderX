# FIX session persistence plan

QuickFIX/J already knows how to resume a session from a persistent `MessageStore` (`RefreshOnLogon`); what the
gateway tier lacked was a store every replica can reach and a rule that only one replica uses it at a time.
JetStream KV gives both with what the tier already runs: a compare-and-set record per session (the fencing
token), a body key per message, and a second bucket whose max age is the lease TTL. The acceptor stays the
owner of FIX semantics; the store refuses any write it cannot prove is its own, and QuickFIX/J's
store-before-send makes that refusal keep the message off the wire. Failures (lease lost, NATS gone) end the
session by TCP close, so a counterparty never waits on an answer that cannot be stored. See ADR-086.
