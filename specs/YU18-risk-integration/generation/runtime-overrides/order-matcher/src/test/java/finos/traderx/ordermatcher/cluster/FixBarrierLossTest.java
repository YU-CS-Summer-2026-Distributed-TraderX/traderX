package finos.traderx.ordermatcher.cluster;

import org.apache.mina.core.filterchain.IoFilter.NextFilter;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quickfix.MessageStore;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.mina.SessionConnector;

import java.io.IOException;
import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-099 (FR-GFS-18), codeX review 2026-10-30 §7: a message group commit has taken but not committed is never sent
 * once the lease or the store is gone. It was: between the loss (lease revision 0) and the disconnect, the barrier
 * read "not pending" and passed it on. The session's state is set at that boundary directly; no NATS.
 */
class FixBarrierLossTest {
    private static final SessionID SID = new SessionID("FIX.4.4", "TRADERX", "CLIENT1");
    private FixSessionStore store;
    private MessageStore session;
    private FixWriteBarrier barrier;
    private IoSession io;
    private NextFilter next;

    @BeforeEach
    void setUp() throws Exception {
        store = new FixSessionStore("nats://127.0.0.1:1", "gw-test");   // never connects: state is set below
        set(store, "groupCommit", true);
        session = store.create(SID);
        barrier = new FixWriteBarrier(store);
        final Session qf = mock(Session.class);
        when(qf.getSessionID()).thenReturn(SID);
        io = mock(IoSession.class);
        when(io.getAttribute(SessionConnector.QF_SESSION)).thenReturn(qf);
        next = mock(NextFilter.class);
        // Message 1 taken by group commit (claim 2), nothing committed yet (persisted sender 1), lease held.
        set(session, "persistedSender", 1);
        set(session, "claim", 2);
        set(session, "leaseRevision", 7L);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static void set(final Object o, final String name, final Object value) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, value);
                return;
            } catch (final NoSuchFieldException e) {
                // superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static WriteRequest write(final int seq) {
        final WriteRequest wr = mock(WriteRequest.class);
        when(wr.getMessage()).thenReturn("8=FIX.4.4\u00019=50\u000135=8\u000134=" + seq + "\u000149=TRADERX\u000110=000\u0001");
        when(wr.getFuture()).thenReturn(mock(WriteFuture.class));
        return wr;
    }

    /** The review's boundary: lease revision 0, message at or above the persisted sender. */
    @Test
    void anUncommittedMessageIsNotSentOnceTheLeaseIsGone() throws Exception {
        set(session, "leaseRevision", 0L);
        final WriteRequest wr = write(1);
        barrier.filterWrite(next, io, wr);
        verify(next, never()).filterWrite(any(), any());
        verify(wr.getFuture()).setException(any());
        verify(io).closeNow();

        final WriteRequest resend = write(0);   // even a committed message: the connection is over
        barrier.filterWrite(next, io, resend);
        verify(next, never()).filterWrite(any(), any());
    }

    @Test
    void anUncommittedMessageIsNotSentOnceItsCommitFailed() throws Exception {
        set(session, "failed", new IOException("commit timed out"));
        barrier.filterWrite(next, io, write(1));
        verify(next, never()).filterWrite(any(), any());
    }

    /** Control: with the lease held the message waits for its commit, then goes. */
    @Test
    void withTheLeaseHeldItIsHeldUntilCommitted() throws Exception {
        final WriteRequest wr = write(1);
        barrier.filterWrite(next, io, wr);
        verify(next, never()).filterWrite(any(), any());
        set(session, "persistedSender", 2);
        barrier.durable(SID, 2);
        verify(next, times(1)).filterWrite(io, wr);
        verify(io, never()).closeNow();
    }

    /** Controls: what group commit never took (a refused Logon's transient Logout), and what is committed, go out. */
    @Test
    void untakenAndCommittedMessagesPassEvenWithoutTheLease() throws Exception {
        set(session, "leaseRevision", 0L);
        final WriteRequest transientLogout = write(2);   // at the claim: never taken
        barrier.filterWrite(next, io, transientLogout);
        verify(next).filterWrite(io, transientLogout);
        final WriteRequest committed = write(0);
        barrier.filterWrite(next, io, committed);
        verify(next).filterWrite(io, committed);
    }
}
