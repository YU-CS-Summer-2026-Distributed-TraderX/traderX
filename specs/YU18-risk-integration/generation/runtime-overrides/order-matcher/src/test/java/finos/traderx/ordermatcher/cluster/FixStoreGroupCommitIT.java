package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.Nats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import quickfix.MessageStore;
import quickfix.SessionID;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-099 (FR-GFS-18): group commit keeps ADR-086's fence, and is used only where the server enforces it. */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(120)
class FixStoreGroupCommitIT {
    @Container
    static final GenericContainer<?> NATS = nats("nats:2.14-alpine");

    private static final SessionID SID = new SessionID("FIX.4.4", "TRADERX", "CLIENT1");

    private static GenericContainer<?> nats(final String image) {
        return new GenericContainer<>(image).withCommand("-js").withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
    }

    private static String url(final GenericContainer<?> nats) {
        return "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
    }

    private static FixSessionStore start(final GenericContainer<?> nats, final String owner) throws Exception {
        final FixSessionStore store = new FixSessionStore(url(nats), owner);
        for (int i = 0; i < 100 && !store.available(); i++) {
            Thread.sleep(100);
        }
        assertTrue(store.available(), "store never became available");
        return store;
    }

    private static void send(final MessageStore ms, final int n, final String text) throws Exception {
        ms.set(n, "8=FIX.4.4\u00019=60\u000135=8\u000134=" + n + "\u000149=TRADERX\u000156=CLIENT1\u000158=" + text
            + "\u000110=000\u0001");
        ms.incrNextSenderMsgSeqNum();
    }

    /** Waits for every commit so far (a resend read quiesces first), and returns that message as stored. */
    private static String stored(final Connection nc, final MessageStore ms, final int n) throws Exception {
        ms.get(n, n, new ArrayList<>());
        return nc.keyValue(FixSessionStore.BUCKET).get(FixSessionStore.messageKey(FixSessionStore.keyOf(SID), n))
            .getValueAsString();
    }

    /**
     * A was replaced while it still believed it held the lease (its last renewal is fresh), and writes message 6
     * after B stored its own 6. A's body is fenced on the K.seq revision A last saw, which B moved when it took over:
     * refused, and A's session is dropped. Falsifiable: without the cross-subject fence header A's body lands and
     * slot 6 then holds A's message, which a resend would deliver in place of what B sent.
     */
    @Test
    void aReplacedOwnersLateWriteNeverOverwritesTheNewOwners() throws Exception {
        try (Connection nc = Nats.connect(url(NATS))) {
            final FixSessionStore a = start(NATS, "gw-a");
            final FixSessionStore b = start(NATS, "gw-b");
            try {
                assertTrue(a.groupCommit() && b.groupCommit());
                final MessageStore ma = a.create(SID);
                final MessageStore mb = b.create(SID);
                assertTrue(a.acquire(SID));
                for (int n = 1; n <= 5; n++) {
                    send(ma, n, "a");
                }
                assertTrue(stored(nc, ma, 5).contains("58=a"));

                a.renewalsStalled = true;                                            // A stops renewing...
                nc.keyValue(FixSessionStore.LEASE_BUCKET).delete(FixSessionStore.keyOf(SID));   // ...and its lease lapses
                assertTrue(b.acquire(SID), "B takes the lapsed lease");
                assertEquals(6, mb.getNextSenderMsgSeqNum(), "B continues after A's committed messages");
                send(mb, 6, "b");
                assertTrue(stored(nc, mb, 6).contains("58=b"));

                final AtomicInteger lost = new AtomicInteger();
                a.onLost = s -> lost.incrementAndGet();
                send(ma, 6, "a-late");                                               // A still thinks it is the owner
                assertThrows(IOException.class, () -> ma.get(6, 6, new ArrayList<>()), "A's late commit is refused");
                assertTrue(stored(nc, mb, 6).contains("58=b\u0001"), "B's message 6 is what is stored");
                for (int i = 0; i < 50 && lost.get() == 0; i++) {
                    Thread.sleep(20);
                }
                assertEquals(1, lost.get(), "A's session is dropped");
            } finally {
                a.close();
                b.close();
            }
        }
    }

    /**
     * A server that ignores the cross-subject header keeps the synchronous store (a claim and a put per message). The
     * bucket already holds messages, as production's does: the first probe passed NATS 2.10 here (codeX review
     * 2026-10-30 §4), because 2.10 refused its stale write by checking the destination subject instead.
     */
    @Test
    void aServerWithoutTheFenceKeepsTheSynchronousStore() throws Exception {
        try (GenericContainer<?> old = nats("nats:2.10-alpine")) {
            old.start();
            try (Connection nc = Nats.connect(url(old))) {
                nc.keyValueManagement().create(io.nats.client.api.KeyValueConfiguration.builder()
                    .name(FixSessionStore.BUCKET).maxHistoryPerKey(1).ttl(java.time.Duration.ofDays(7))
                    .maxBucketSize(1L << 30).storageType(io.nats.client.api.StorageType.File).build());
                for (int i = 0; i < 7; i++) {
                    nc.keyValue(FixSessionStore.BUCKET).put("other.seq" + i, "1".getBytes());   // probe revision r > 1
                }
            }
            final FixSessionStore store = start(old, "gw-old");
            try {
                assertFalse(store.groupCommit(), "NATS 2.10 ignores the cross-subject fence");
                final MessageStore ms = store.create(SID);
                assertTrue(store.acquire(SID));
                send(ms, 1, "sync");
                final ArrayList<String> got = new ArrayList<>();
                ms.get(1, 1, got);
                assertEquals(1, got.size(), "stored synchronously");
            } finally {
                store.close();
            }
        }
    }
}
