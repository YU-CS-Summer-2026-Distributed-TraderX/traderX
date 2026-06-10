package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;

/**
 * Replicator handler (FR-09B30..FR-09B32). The demo/`C2` profile runs a single replica, so
 * this is the loopback realization: it acknowledges every sequence immediately, exercising
 * the replication seam in the input topology (journaler + replicator gate the BLP) without
 * a network peer. The perf profile substitutes an Aeron-based replicator shipping the
 * sequenced stream to a follower BLP and DR site.
 */
public final class ReplicatorStub implements EventHandler<InputEvent> {
    private volatile long replicatedSeq = -1;

    @Override
    public void onEvent(InputEvent e, long sequence, boolean endOfBatch) {
        replicatedSeq = sequence; // loopback acknowledgement
    }

    public long replicatedSeq() {
        return replicatedSeq;
    }
}
