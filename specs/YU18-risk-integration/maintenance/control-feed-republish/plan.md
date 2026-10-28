# Control feed republish plan

The outbox's `published` flag is a local claim about remote state. The audit reads the remote state the claim is
about (message count and last version, from JetStream stream info and the last message by subject) and resets the
claim when the remote state cannot hold it. Republishing everything, rather than computing the missing versions,
keeps the poller's single ordered path and is idempotent for the consumer. See ADR-085 for the check chosen and
the alternatives.
