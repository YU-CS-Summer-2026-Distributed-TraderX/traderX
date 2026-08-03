package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hot-swappable Disruptor EventHandler that wraps either a {@link NatsJournalReplicator} (when
 * PRIMARY) or a {@link ReplicatorStub} (when FOLLOWER or single-node). The Disruptor is wired
 * once at startup; this wrapper lets the active delegate change at runtime when the BLP's role
 * transitions without restarting the ring.
 *
 * <p>{@link #swapDelegate} is called from {@code LmaxEngine.onRoleChange}, which runs on the
 * leader-election thread. The {@code volatile} on {@code delegate} ensures subsequent
 * {@code onEvent} calls (on the Disruptor event-processor thread) see the updated handler.
 * The brief window of events processed under the old delegate during a swap is safe: a swap
 * to {@link ReplicatorStub} (loopback) causes those events to not be replicated — which is
 * correct because the pod is transitioning away from PRIMARY and is no longer the authority.
 * A swap to {@link NatsJournalReplicator} causes subsequent events to be published to JetStream,
 * and the ACK listener is started in tandem.
 */
public final class DelegatingReplicator implements EventHandler<InputEvent> {
    private static final Logger log = LoggerFactory.getLogger(DelegatingReplicator.class);

    private volatile EventHandler<InputEvent> delegate;

    public DelegatingReplicator(EventHandler<InputEvent> initial) {
        this.delegate = initial;
    }

    @Override
    public void onEvent(InputEvent e, long sequence, boolean endOfBatch) throws Exception {
        delegate.onEvent(e, sequence, endOfBatch);
    }

    /**
     * Returns the replicated sequence from the active delegate.
     * Used by {@code LmaxEngine.replicatedSeq()} to compute the input barrier.
     */
    public long replicatedSeq() {
        EventHandler<InputEvent> d = delegate;
        if (d instanceof NatsJournalReplicator r) return r.replicatedSeq();
        if (d instanceof ReplicatorStub s)         return s.replicatedSeq();
        return -1;
    }

    /**
     * Atomically replaces the delegate. Safe: the {@code volatile} write is immediately visible
     * to the Disruptor event-processor thread on the next {@code onEvent} call.
     */
    public void swapDelegate(EventHandler<InputEvent> newDelegate) {
        log.info("Swapping replicator delegate: {} → {}",
            delegate.getClass().getSimpleName(), newDelegate.getClass().getSimpleName());
        delegate = newDelegate;
    }

    public EventHandler<InputEvent> delegate() {
        return delegate;
    }
}
