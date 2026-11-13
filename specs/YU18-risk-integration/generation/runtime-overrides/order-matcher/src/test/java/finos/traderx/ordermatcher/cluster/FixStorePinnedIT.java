package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.Nats;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-GFS-24: each bucket is written and read only on a connection to the server that holds it. Two independent NATS
 * servers make that observable: a bucket's stream exists only where its writes went, and a write sent to the other
 * server would find no stream there.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(180)
class FixStorePinnedIT {
    @Container
    static final GenericContainer<?> A = nats();
    @Container
    static final GenericContainer<?> B = nats();

    private static GenericContainer<?> nats() {
        return new GenericContainer<>("nats:2.14-alpine").withCommand("-js").withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
    }

    private static String url(final GenericContainer<?> nats) {
        return "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
    }

    /** Each test starts with no session bucket on either server (the lease bucket stays: its keys are per session). */
    @BeforeEach
    void noBuckets() throws Exception {
        for (final GenericContainer<?> nats : List.of(A, B)) {
            try (Connection nc = Nats.connect(url(nats))) {
                for (final String s : List.of("KV_FIX_SESSIONS", "KV_FIX_SESSIONS_1")) {
                    try {
                        nc.jetStreamManagement().deleteStream(s);
                    } catch (final JetStreamApiException absent) {
                        // not there
                    }
                }
            }
        }
    }

    private static FixSessionStore start(final String owner, final List<String> servers) throws Exception {
        final FixSessionStore store = new FixSessionStore(url(A), owner, 2, servers);
        for (int i = 0; i < 100 && !store.available(); i++) {
            Thread.sleep(100);
        }
        assertTrue(store.available(), "store never became available");
        return store;
    }

    /** A session of {@code prefix} whose key lands in {@code bucket} of 2. */
    private static SessionID inBucket(final String prefix, final int bucket) {
        for (int i = 0; ; i++) {
            final SessionID sid = new SessionID("FIX.4.4", "TRADERX", prefix + i);
            if (FixSessionStore.bucketOf(FixSessionStore.keyOf(sid), 2) == bucket) {
                return sid;
            }
        }
    }

    private static void send(final MessageStore ms, final int n) throws Exception {
        ms.set(n, "8=FIX.4.4\u00019=10\u000135=8\u000134=" + n + "\u000110=000\u0001");
        ms.incrNextSenderMsgSeqNum();
    }

    private static Set<String> streams(final GenericContainer<?> nats) throws Exception {
        try (Connection nc = Nats.connect(url(nats))) {
            return Set.copyOf(nc.jetStreamManagement().getStreamNames());
        }
    }

    private static String seqOn(final GenericContainer<?> nats, final String bucket, final SessionID sid) throws Exception {
        try (Connection nc = Nats.connect(url(nats))) {
            final var e = nc.keyValue(bucket).get(FixSessionStore.keyOf(sid) + ".seq");
            return e == null ? null : e.getValueAsString();
        }
    }

    /**
     * Both sessions' messages are stored, each in its bucket on its own server: bucket 1 exists only on B. Falsifiable:
     * through the one main connection (the next test) bucket 1 is created, and written, on A.
     */
    @Test
    void eachBucketIsWrittenOnlyOnItsOwnServersConnection() throws Exception {
        final SessionID s0 = inBucket("PIN-A", 0);
        final SessionID s1 = inBucket("PIN-B", 1);
        final FixSessionStore store = start("gw-pin", List.of(url(A), url(B)));
        try {
            for (final SessionID sid : List.of(s0, s1)) {
                final MessageStore ms = store.create(sid);
                assertTrue(store.acquire(sid));
                for (int n = 1; n <= 3; n++) {
                    send(ms, n);
                }
                ms.get(1, 3, new ArrayList<>());   // every commit so far has landed
            }
            assertTrue(streams(A).contains("KV_FIX_SESSIONS") && !streams(A).contains("KV_FIX_SESSIONS_1"),
                "A holds bucket 0 only: " + streams(A));
            assertTrue(streams(B).contains("KV_FIX_SESSIONS_1") && !streams(B).contains("KV_FIX_SESSIONS"),
                "B holds bucket 1 only: " + streams(B));
            assertTrue(seqOn(A, "FIX_SESSIONS", s0).startsWith("4 "), "bucket 0's session committed on A");
            assertTrue(seqOn(B, "FIX_SESSIONS_1", s1).startsWith("4 "), "bucket 1's session committed on B");
        } finally {
            store.close();
        }
    }

    /** The negative control: unpinned, every bucket is on the main connection's server, so B holds none. */
    @Test
    void withoutPinningEveryBucketIsWrittenThroughTheMainServer() throws Exception {
        final SessionID s1 = inBucket("UNPIN-B", 1);
        final FixSessionStore store = start("gw-unpin", List.of());
        try {
            final MessageStore ms = store.create(s1);
            assertTrue(store.acquire(s1));
            send(ms, 1);
            ms.get(1, 1, new ArrayList<>());
            assertNotNull(seqOn(A, "FIX_SESSIONS_1", s1), "unpinned, bucket 1 was written on A");
            assertFalse(streams(B).contains("KV_FIX_SESSIONS_1"), "and not on B, where the pinned store writes it");
        } finally {
            store.close();
        }
    }

    /**
     * FR-GFS-25 (RI39): A, the main connection's server and bucket 0's, stops answering. Bucket 1's live session keeps
     * its lease: the lease is in bucket 1's own lease bucket on B, renewed on its own thread. Before, every session's
     * lease was in one bucket on the main connection's server, renewed in one loop, so A's stall cost bucket 1 its
     * lease too (GKE 2026-11-12: a 40 MB/s write limit on one of five servers cost all 600 sessions their leases).
     * Checked within the lease TTL's reach, before the main connection's pings declare A gone (which drops every session
     * by design, ADR-086 §7).
     */
    @Test
    void aStalledServerCostsOnlyTheLeasesOfTheSessionsItHolds() throws Exception {
        final SessionID s0 = inBucket("STALL-A", 0);
        final SessionID s1 = inBucket("STALL-B", 1);
        final FixSessionStore store = start("gw-stall", List.of(url(A), url(B)));
        final Set<SessionID> lost = ConcurrentHashMap.newKeySet();
        store.onLost = lost::add;
        store.create(s0);
        store.create(s1);
        boolean paused = false;
        try {
            for (final SessionID sid : List.of(s0, s1)) {
                assertTrue(store.acquire(sid));
                store.live(sid, true);
            }
            A.getDockerClient().pauseContainerCmd(A.getContainerId()).exec();
            paused = true;
            final long until = System.nanoTime() + 7_000_000_000L;   // > 2 x the 3 s TTL, < the ~10 s ping verdict
            while (System.nanoTime() < until && !lost.contains(s1)) {
                Thread.sleep(100);
            }
            assertTrue(lost.contains(s0), "the stall did not cost bucket 0's session its lease: the fault did not take");
            assertFalse(lost.contains(s1), "bucket 1's session lost its lease to a stall on A, which does not hold it");
            assertTrue(store.confirm(s1), "bucket 1's session is not confirmed on B");
        } finally {
            if (paused) {
                A.getDockerClient().unpauseContainerCmd(A.getContainerId()).exec();
            }
            store.close();
        }
    }

    /**
     * B's connection is lost (B paused): bucket 1's live session is dropped, refuses writes and its Logon, while
     * bucket 0's is served on. Nothing of bucket 1 goes to A meanwhile. Once B is back the session is taken again.
     */
    @Test
    void losingOneServersConnectionEndsOnlyTheSessionsItHolds() throws Exception {
        final SessionID s0 = inBucket("LOSS-A", 0);
        final SessionID s1 = inBucket("LOSS-B", 1);
        final FixSessionStore store = start("gw-loss", List.of(url(A), url(B)));
        final Set<SessionID> lost = ConcurrentHashMap.newKeySet();
        store.onLost = lost::add;
        final MessageStore m0 = store.create(s0);
        final MessageStore m1 = store.create(s1);
        boolean paused = false;
        try {
            for (final SessionID sid : List.of(s0, s1)) {
                assertTrue(store.acquire(sid));
                store.live(sid, true);
            }
            send(m0, 1);
            send(m1, 1);
            m1.get(1, 1, new ArrayList<>());
            B.getDockerClient().pauseContainerCmd(B.getContainerId()).exec();
            paused = true;
            for (int i = 0; i < 300 && !lost.contains(s1); i++) {
                Thread.sleep(100);
            }
            assertTrue(lost.contains(s1), "bucket 1's live session was not dropped when B's connection went");
            assertFalse(lost.contains(s0), "bucket 0's session was dropped with it");
            assertFalse(store.confirm(s1), "bucket 1's session still confirmed");
            assertThrows(IOException.class, () -> send(m1, 2), "bucket 1 still took a write");
            assertFalse(store.acquire(s1), "bucket 1's Logon was not refused");
            assertTrue(store.confirm(s0), "bucket 0's session lost its store");
            send(m0, 2);
            m0.get(1, 2, new ArrayList<>());
            assertTrue(seqOn(A, "FIX_SESSIONS", s0).startsWith("3 "), "bucket 0 kept committing on A");
            assertFalse(streams(A).contains("KV_FIX_SESSIONS_1"), "bucket 1 was written to A meanwhile");
            B.getDockerClient().unpauseContainerCmd(B.getContainerId()).exec();
            paused = false;
            boolean back = false;
            for (int i = 0; i < 300 && !(back = store.acquire(s1)); i++) {
                Thread.sleep(100);
            }
            assertTrue(back, "bucket 1's session was not taken again once B returned");
            assertEquals(2, m1.getNextSenderMsgSeqNum(), "and resumed at its stored numbers");
        } finally {
            if (paused) {
                B.getDockerClient().unpauseContainerCmd(B.getContainerId()).exec();
            }
            store.close();
        }
    }
}
