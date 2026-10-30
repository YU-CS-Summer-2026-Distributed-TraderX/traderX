# Cluster test failover race: implementation

1. `awaitLeader` keeps its member-role wait, then (when a client exists) waits for
   `client.leaderMemberId()` to equal the new leader. It polls egress, or reconnects a closed session with
   the existing `connectClientKeepingAcks`.
2. `await` takes a description and puts it in the timeout message.

Why the window existed. A member's service reports LEADER during its election. The NewLeader event reaches
the client after the election closes, about 100 ms later. `offerIngress` polled egress once and offered.
If the event had not arrived, the offer cleared into the old leader's publication. That publication still
reports connected until its liveness timeout. Two kinds of failover were seen. A slow one (about 11 s)
waits out the 10 s heartbeat timeout: by then the old publication is down and the client has the event, so
there is no window. A fast one (under 1 s) happens when the dead leader's end-of-stream reaches the
followers. A loaded box made fast failovers common.
