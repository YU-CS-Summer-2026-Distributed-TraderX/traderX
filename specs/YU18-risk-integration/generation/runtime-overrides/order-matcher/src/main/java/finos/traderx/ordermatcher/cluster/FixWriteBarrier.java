package finos.traderx.ordermatcher.cluster;

import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.filterchain.IoFilterChainBuilder;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.mina.SessionConnector;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADR-099: nothing reaches a client before it is stored. With group commit {@link FixSessionStore} returns from
 * {@code set} before the message is durable, so QuickFIX/J's write is held here until the store has committed
 * that MsgSeqNum, then released in order. It sits at the tail of each connection's chain, so it sees QuickFIX/J's
 * message strings ahead of the FIX codec and TLS.
 */
final class FixWriteBarrier extends IoFilterAdapter {
    private final FixSessionStore store;
    private final Map<SessionID, Held> held = new ConcurrentHashMap<>();

    private static final class Held {
        final IoSession io;
        final ArrayDeque<Object[]> queue = new ArrayDeque<>();   // {seq, NextFilter, WriteRequest}
        NextFilter close;   // a close QuickFIX/J asked for while writes were held: done once they are out
        boolean poisoned;   // a write was dropped: nothing after it is sent on this connection

        Held(final IoSession io) {
            this.io = io;
        }
    }

    FixWriteBarrier(final FixSessionStore store) {
        this.store = store;
    }

    /** The builder QuickFIX/J runs first; it adds the barrier at the tail once the connection's chain is built. */
    IoFilterChainBuilder installer() {
        return chain -> chain.addLast("fix-store-barrier-installer", new IoFilterAdapter() {
            @Override
            public void sessionCreated(final NextFilter next, final IoSession io) throws Exception {
                io.getFilterChain().addLast("fix-store-barrier", FixWriteBarrier.this);
                next.sessionCreated(io);
            }
        });
    }

    @Override
    public void filterWrite(final NextFilter next, final IoSession io, final WriteRequest wr) throws Exception {
        final Object qf = io.getAttribute(SessionConnector.QF_SESSION);
        if (!(qf instanceof Session) || !(wr.getMessage() instanceof String msg)) {
            next.filterWrite(io, wr);
            return;
        }
        final SessionID sid = ((Session) qf).getSessionID();
        final Held h = held.compute(sid, (k, v) -> v == null || v.io != io ? new Held(io) : v);
        final int seq = FixSessionStore.seqOf(msg);
        synchronized (h) {
            // Decided under h: a commit landing between the decision and the enqueue would otherwise never release it.
            final FixSessionStore.Release release = store.release(sid, seq);
            if (h.poisoned || release == FixSessionStore.Release.DROP) {
                // Never stored, and never will be: not sent, nor anything after it. The connection ends here.
                h.poisoned = true;
                fail(wr, sid);
                h.queue.forEach(w -> fail((WriteRequest) w[2], sid));
                h.queue.clear();
                io.closeNow();
            } else if (h.queue.isEmpty() && release == FixSessionStore.Release.SEND) {
                next.filterWrite(io, wr);
            } else {
                h.queue.addLast(new Object[] { seq, next, wr });
            }
        }
    }

    private static void fail(final WriteRequest wr, final SessionID sid) {
        wr.getFuture().setException(new java.io.IOException("FIX session " + sid + ": not stored, not sent (ADR-099)"));
    }

    /** The store committed every number below {@code nextSender}: send what was held for them, in order. */
    void durable(final SessionID sid, final int nextSender) {
        final Held h = held.get(sid);
        if (h == null) {
            return;
        }
        synchronized (h) {
            for (Object[] w; (w = h.queue.peekFirst()) != null && (int) w[0] < nextSender; ) {
                h.queue.pollFirst();
                ((NextFilter) w[1]).filterWrite(h.io, (WriteRequest) w[2]);
            }
            closeIfDrained(h);
        }
    }

    private static void closeIfDrained(final Held h) {
        if (h.queue.isEmpty() && h.close != null) {
            final NextFilter close = h.close;
            h.close = null;
            close.filterClose(h.io);
        }
    }

    /**
     * QuickFIX/J ends a session with closeOnFlush, which flushes MINA's queue but not what is held here (a Logout
     * answering the client's, say). The close waits for those, or for the store to fail and drop them.
     */
    @Override
    public void filterClose(final NextFilter next, final IoSession io) throws Exception {
        for (final Held h : held.values()) {
            if (h.io == io) {
                synchronized (h) {
                    if (!h.queue.isEmpty()) {
                        h.close = next;
                        return;
                    }
                }
            }
        }
        next.filterClose(io);
    }

    /** The session lost its store or lease: what was held was never stored, so it is never sent. */
    void drop(final SessionID sid) {
        final Held h = held.get(sid);   // kept, poisoned, until its connection closes
        if (h != null) {
            synchronized (h) {
                h.poisoned = true;
                h.queue.forEach(w -> fail((WriteRequest) w[2], sid));
                h.queue.clear();
                closeIfDrained(h);
            }
        }
    }

    int held(final SessionID sid) {
        final Held h = held.get(sid);
        if (h == null) {
            return 0;
        }
        synchronized (h) {
            return h.queue.size();
        }
    }

    @Override
    public void sessionClosed(final NextFilter next, final IoSession io) throws Exception {
        held.values().removeIf(h -> h.io == io);
        next.sessionClosed(io);
    }
}
