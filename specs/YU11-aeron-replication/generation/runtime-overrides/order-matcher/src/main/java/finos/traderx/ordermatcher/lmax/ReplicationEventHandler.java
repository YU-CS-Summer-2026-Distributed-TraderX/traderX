package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;

/** Common hot-path seam for the selected NATS or Aeron replication delegate. */
public interface ReplicationEventHandler extends EventHandler<InputEvent>, AutoCloseable {
    long replicatedSeq();
    boolean degraded();

    @Override
    void close();
}
