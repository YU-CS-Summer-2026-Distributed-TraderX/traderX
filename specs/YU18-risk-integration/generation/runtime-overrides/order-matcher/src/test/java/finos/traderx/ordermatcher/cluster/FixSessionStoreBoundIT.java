package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.KeyValue;
import io.nats.client.Nats;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.StorageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import quickfix.MessageStore;
import quickfix.SessionID;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-086 amendment (FR-GFS-14/15): the FIX session store is bounded by sessions, not traffic, and a full bucket
 * degrades instead of ending every session. f15s (864 sessions, 20K orders/s) filled the 1 GiB bucket in 12
 * minutes with one key per stored message (3,999,940 keys); the next write failed on every gateway at once.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(180)
class FixSessionStoreBoundIT {
    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine")
        .withCommand("-js")
        .withExposedPorts(4222)
        .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    private static final SessionID SID = new SessionID("FIX.4.4", "TRADERX", "CLIENT1");

    private static String url() {
        return "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    }

    /** A stored outbound message as QuickFIX/J hands it over: MsgSeqNum (34) in the header, ~270 bytes. */
    private static String message(final int n) {
        return "8=FIX.4.4\u00019=240\u000135=8\u000134=" + n + "\u000149=TRADERX\u000156=CLIENT1\u000158="
            + "x".repeat(200) + "\u000110=000\u0001";
    }

    /** What QuickFIX/J's Session.persist does for each message it sends. */
    private static void send(final MessageStore ms, final int n) throws Exception {
        ms.set(n, message(n));
        ms.incrNextSenderMsgSeqNum();
    }

    private static FixSessionStore start(final String owner) throws Exception {
        final FixSessionStore store = new FixSessionStore(url(), owner);
        for (int i = 0; i < 100 && !store.available(); i++) {
            Thread.sleep(100);
        }
        assertTrue(store.available(), "store never became available");
        return store;
    }

    private static void freshBucket(final Connection nc, final long maxBytes) throws Exception {
        if (nc.keyValueManagement().getBucketNames().contains(FixSessionStore.BUCKET)) {
            nc.keyValueManagement().delete(FixSessionStore.BUCKET);
        }
        if (maxBytes > 0) {   // as production's was created: discard-new, the KV default
            nc.keyValueManagement().create(KeyValueConfiguration.builder().name(FixSessionStore.BUCKET)
                .maxHistoryPerKey(1).ttl(Duration.ofDays(7)).maxBucketSize(maxBytes).storageType(StorageType.File).build());
        }
    }

    @Test
    void aLongSessionKeepsItsResendWindowNotItsWholeDay() throws Exception {
        final int window = FixSessionStore.RESEND_WINDOW;
        final int sent = 3 * window;
        try (Connection nc = Nats.connect(url())) {
            freshBucket(nc, 0);
            final FixSessionStore store = start("gw-bound");
            try {
                final MessageStore ms = store.create(SID);
                assertTrue(store.acquire(SID), "lease");
                for (int n = 1; n <= sent; n++) {
                    send(ms, n);
                }
                final KeyValue kv = nc.keyValue(FixSessionStore.BUCKET);
                final String prefix = FixSessionStore.keyOf(SID) + ".m.";
                final long stored = kv.keys().stream().filter(k -> k.startsWith(prefix)).count();
                assertTrue(stored <= window, sent + " messages left " + stored + " keys; the bound is " + window);

                final List<String> resent = new ArrayList<>();
                ms.get(1, sent, resent);
                assertEquals(window, resent.size(), "a full ResendRequest returns the window");
                for (int i = 0; i < resent.size(); i++) {
                    assertEquals(sent - window + 1 + i, FixSessionStore.seqOf(resent.get(i)), "in order, exact bodies");
                }
                assertEquals(sent - window, store.gapFilled(), "everything older is gap-filled, and counted");
                assertEquals(sent + 1, ms.getNextSenderMsgSeqNum(), "the sequence is not windowed");
            } finally {
                store.close();
            }
        }
    }

    @Test
    void aFullBucketDegradesInsteadOfEndingTheSession() throws Exception {
        try (Connection nc = Nats.connect(url())) {
            freshBucket(nc, 64 * 1024);   // ~230 messages
            final FixSessionStore store = start("gw-full");
            final AtomicInteger lost = new AtomicInteger();
            store.onLost = sid -> lost.incrementAndGet();
            try {
                assertEquals(DiscardPolicy.Old, nc.jetStreamManagement().getStreamInfo("KV_" + FixSessionStore.BUCKET)
                    .getConfiguration().getDiscardPolicy(), "the existing bucket was not moved to discard-oldest");
                final MessageStore ms = store.create(SID);
                assertTrue(store.acquire(SID), "lease");
                for (int n = 1; n <= 2_000; n++) {
                    send(ms, n);   // threw "maximum bytes exceeded" at ~230 before the amendment
                }
                assertEquals(0, lost.get(), "a full store ended the session");
                assertEquals(2_001, ms.getNextSenderMsgSeqNum());
                final List<String> last = new ArrayList<>();
                ms.get(2_000, 2_000, last);
                assertEquals(1, last.size(), "the newest message is still resendable");
            } finally {
                store.close();
            }
        }
    }
}
