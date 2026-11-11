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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
                // A still thinks it is the owner: its last renewal is fresh. (Older than FRESH_MILLIS, as on a slow
                // box, A rechecks, finds B's lease and writes nothing at all: safe, but not the case under test.)
                field(ma, "renewedAt", System.nanoTime());
                send(ma, 6, "a-late");
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
     * codeX review 2026-10-30 §10: A stalled past the TTL; B took the session, moved its numbers and left. A's renewal
     * fails and the lease is free again, but that does not mean nobody served the session meanwhile: retake fences
     * K.seq from the revision A last saw, which B moved, so A is refused rather than confirmed with stale numbers. In
     * both modes: retake predates group commit. The control (nobody in between) is FixSessionPersistenceIT's
     * aStalledOwnerThatNobodyReplacedKeepsItsSession.
     */
    @Test
    void aStalledOwnerIsNotConfirmedAfterAnotherServedTheSessionAndLeft() throws Exception {
        retakeAfterAnotherOwner(true);
    }

    @Test
    void aStalledOwnerIsNotConfirmedAfterAnotherServedTheSessionAndLeftSynchronously() throws Exception {
        retakeAfterAnotherOwner(false);
    }

    private static void retakeAfterAnotherOwner(final boolean group) throws Exception {
        final SessionID sid = new SessionID("FIX.4.4", "TRADERX", group ? "RETAKE-G" : "RETAKE-S");
        try (Connection nc = Nats.connect(url(NATS))) {
            final FixSessionStore a = start(NATS, "gw-a");
            final FixSessionStore b = start(NATS, "gw-b");
            try {
                field(a, "groupCommit", group);
                field(b, "groupCommit", group);
                a.renewalsStalled = true;
                b.renewalsStalled = true;
                final MessageStore ma = a.create(sid);
                final MessageStore mb = b.create(sid);
                assertTrue(a.acquire(sid));
                assertTrue(a.confirm(sid), "control: A holds it");
                nc.keyValue(FixSessionStore.LEASE_BUCKET).delete(FixSessionStore.keyOf(sid));   // A's lease lapses
                assertTrue(b.acquire(sid));
                mb.setNextTargetMsgSeqNum(9);
                mb.setNextSenderMsgSeqNum(7);
                b.close();                                                                      // B leaves
                field(ma, "renewedAt", 0L);                                                     // A resumes
                assertFalse(a.confirm(sid), "A's numbers (1/1) are stale: the session was served at 7/9 since");
                assertEquals(null, nc.keyValue(FixSessionStore.LEASE_BUCKET).get(FixSessionStore.keyOf(sid)),
                    "the refused retake leaves the lease free for the client's next Logon");
                assertTrue(nc.keyValue(FixSessionStore.BUCKET).get(FixSessionStore.keyOf(sid) + ".seq")
                    .getValueAsString().startsWith("7 9 "), "B's numbers stand");
            } finally {
                a.close();
                b.close();
            }
        }
    }

    /**
     * FR-GFS-22: A's lease lapsed while its own commit was in flight (a NATS slow enough to delay renewals), and nobody
     * served the session meanwhile. Retaking wrote K.seq from the revision A last saw, which fenced A's own commit out
     * (10071) and dropped a session nobody else wanted: the 2026-11-10 store bench lost hundreds of its 600 that way.
     * The commit in flight is the retake's fence instead, and confirm waits for it. Falsifiable: the old store confirms
     * at once and its commit then fails. Someone did serve it: aStalledOwnerIsNotConfirmedAfterAnotherServedTheSessionAndLeft.
     */
    @Test
    void aRetakeWithItsOwnCommitInFlightKeepsTheSession() throws Exception {
        final SessionID sid = new SessionID("FIX.4.4", "TRADERX", "RETAKE-INFLIGHT");
        try (Connection nc = Nats.connect(url(NATS))) {
            final FixSessionStore a = start(NATS, "gw-a");
            final CountDownLatch hold = new CountDownLatch(1);
            try {
                a.renewalsStalled = true;
                final MessageStore ma = a.create(sid);
                assertTrue(a.acquire(sid));
                ((ExecutorService) field(a, "commits")).execute(() -> {
                    try {
                        hold.await();   // the commit's K.seq write waits behind this
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                ma.set(1, "8=FIX.4.4\u000134=1\u0001");
                ma.incrNextSenderMsgSeqNum();
                nc.keyValue(FixSessionStore.LEASE_BUCKET).delete(FixSessionStore.keyOf(sid));   // A's lease lapses
                field(ma, "renewedAt", 0L);                                                     // A resumes
                final CompletableFuture<Boolean> confirmed = CompletableFuture.supplyAsync(() -> a.confirm(sid));
                Thread.sleep(300);
                assertFalse(confirmed.isDone(), "not confirmed before its commit shows nobody served it since");
                hold.countDown();
                assertTrue(confirmed.get(10, TimeUnit.SECONDS), "retaken: its own commit landed");
                assertTrue(nc.keyValue(FixSessionStore.BUCKET).get(FixSessionStore.keyOf(sid) + ".seq")
                    .getValueAsString().startsWith("2 "), "message 1 is committed");
            } finally {
                hold.countDown();
                a.close();
            }
        }
    }

    /**
     * codeX review 2026-10-30 §17: synchronously, B took the session, durably recorded a sequenced order (the order
     * bucket, not K.seq) and left without moving a number; or only answered resends. Acquiring fenced K.seq only under
     * group commit, so A's retake found it unchanged and was confirmed over B's order. Every acquisition moves it now.
     */
    @Test
    void aStalledOwnerIsNotConfirmedAfterAnotherRecordedAnOrderSynchronously() throws Exception {
        retakeAfterQuietOwner(true);
    }

    @Test
    void aStalledOwnerIsNotConfirmedAfterAnotherOnlyHeldTheSessionSynchronously() throws Exception {
        retakeAfterQuietOwner(false);
    }

    private static void retakeAfterQuietOwner(final boolean recordsOrder) throws Exception {
        final SessionID sid = new SessionID("FIX.4.4", "TRADERX", recordsOrder ? "QUIET-O" : "QUIET-R");
        try (Connection nc = Nats.connect(url(NATS))) {
            final FixSessionStore a = start(NATS, "gw-a");
            final FixSessionStore b = start(NATS, "gw-b");
            try {
                for (final FixSessionStore s : new FixSessionStore[] { a, b }) {
                    field(s, "groupCommit", false);
                    s.sequenceOrders = true;
                    s.renewalsStalled = true;
                }
                final MessageStore ma = a.create(sid);
                b.create(sid);
                assertTrue(a.acquire(sid));
                assertTrue(a.confirm(sid), "control: A holds it");
                nc.keyValue(FixSessionStore.LEASE_BUCKET).delete(FixSessionStore.keyOf(sid));   // A's lease lapses
                assertTrue(b.acquire(sid));
                if (recordsOrder) {
                    assertEquals(1, b.recordOrder(sid, 22214, "B-ORDER", "stored inbound request"));
                }
                b.close();                                                                      // B leaves, 1/1
                field(ma, "renewedAt", 0L);                                                     // A resumes
                assertFalse(a.confirm(sid), "B served the session meanwhile, though no number moved");
                assertEquals(1, ma.getNextTargetMsgSeqNum());
            } finally {
                a.close();
                b.close();
            }
        }
    }

    /**
     * codeX review 2026-10-30 §11: a commit whose body the server refuses must not leave K.seq (with the inbound
     * number it carries) durable past the request that body answers. Batch 1 is held in flight so that request 2 and
     * its answer form batch 2; the stream refuses answer 2 (too large), as it would any body for its own reason.
     */
    @Test
    void aRefusedBodyNeverCommitsTheInboundNumberPastItsRequest() throws Exception {
        batchWithBody(true);
    }

    /** Control: the same two batches with a body the stream accepts commit both requests and keep both answers. */
    @Test
    void aBatchWhoseBodiesAreStoredCommitsItsInboundNumber() throws Exception {
        batchWithBody(false);
    }

    private static void batchWithBody(final boolean refused) throws Exception {
        try (GenericContainer<?> nats = nats("nats:2.14-alpine")) {
            nats.start();
            final SessionID sid = new SessionID("FIX.4.4", "TRADERX", "BATCH");
            final FixSessionStore a = start(nats, "gw-a");
            final FixSessionStore b = start(nats, "gw-b");
            final CountDownLatch held = new CountDownLatch(1);
            try (Connection nc = Nats.connect(url(nats))) {
                final MessageStore ma = a.create(sid);
                final MessageStore mb = b.create(sid);
                assertTrue(a.acquire(sid));
                if (refused) {
                    final var jsm = nc.jetStreamManagement();
                    jsm.updateStream(io.nats.client.api.StreamConfiguration.builder(
                        jsm.getStreamInfo("KV_" + FixSessionStore.BUCKET).getConfiguration()).maxMsgSize(1024).build());
                }
                ((ExecutorService) field(a, "commits")).submit(() -> {   // batch 1's acknowledgement waits
                    held.await();
                    return null;
                });
                answer(ma, 1, "x");                                       // batch 1: answer 1, sent at once
                ma.incrNextTargetMsgSeqNum();                             // batch 2: request 1's and 2's numbers,
                answer(ma, 2, "x".repeat(refused ? 2000 : 20));           // and answer 2
                ma.incrNextTargetMsgSeqNum();
                held.countDown();
                for (int i = 0; i < 250 && field(ma, "failed") == null && (int) field(ma, "persistedTarget") != 3; i++) {
                    Thread.sleep(20);
                }
                assertEquals(refused, field(ma, "failed") != null);
                a.close();
                assertTrue(b.acquire(sid));
                final ArrayList<String> got = new ArrayList<>();
                mb.get(2, 2, got);
                if (refused) {   // none of batch 2 is durable: the client resends requests 1 and 2 (ADR-099 §4)
                    assertEquals(1, mb.getNextTargetMsgSeqNum(), "request 2 is not retired: its answer was not stored");
                    assertEquals(2, mb.getNextSenderMsgSeqNum(), "answer 2's number is not claimed");
                } else {
                    assertEquals(3, mb.getNextTargetMsgSeqNum());
                    assertEquals(3, mb.getNextSenderMsgSeqNum());
                    assertEquals(1, got.size(), "answer 2 is stored");
                }
            } finally {
                held.countDown();
                a.close();
                b.close();
            }
        }
    }

    private static void answer(final MessageStore ms, final int n, final String text) throws Exception {
        ms.set(n, "8=FIX.4.4\u00019=60\u000135=8\u000134=" + n + "\u000149=TRADERX\u000156=BATCH\u000158=" + text
            + "\u000110=000\u0001");
        ms.incrNextSenderMsgSeqNum();
    }

    /**
     * codeX review 2026-10-30 §12: FIX_STORE_MAX_UNCOMMITTED bounds every body taken and not yet committed, the batch
     * in flight included. With the server paused nothing commits, so the message past the bound waits, and times out.
     */
    @Test
    void theUncommittedBoundCountsTheBatchInFlight() throws Exception {
        try (GenericContainer<?> nats = nats("nats:2.14-alpine")) {
            nats.start();
            final SessionID sid = new SessionID("FIX.4.4", "TRADERX", "BOUND");
            final FixSessionStore a = start(nats, "gw-a");
            try {
                final MessageStore ma = a.create(sid);
                assertTrue(a.acquire(sid));
                a.renewalsStalled = true;   // the pause is not a lease loss: only the bound is under test
                nats.getDockerClient().pauseContainerCmd(nats.getContainerId()).exec();
                int taken = 0;
                try {
                    for (int n = 1; n <= FixSessionStore.MAX_UNCOMMITTED + 1; n++) {
                        ma.set(n, "8=FIX.4.4\u000134=" + n + "\u0001");
                        ma.incrNextSenderMsgSeqNum();
                        taken++;
                    }
                } catch (final IOException waitedOutTheBound) {
                    // the sender waited for room that never came
                }
                assertEquals(FixSessionStore.MAX_UNCOMMITTED, taken, "the in-flight batch counts against the bound");
            } finally {
                nats.getDockerClient().unpauseContainerCmd(nats.getContainerId()).exec();
                a.close();
            }
        }
    }

    /**
     * FR-GFS-19: QuickFIX/J advances the inbound number when fromApp returns, before a pipelined order (FR-BSP-02) is
     * decided. K.seq stores no target past the lowest undecided order, so a crash resumes there and the client
     * resends it; once it is decided the target catches up without waiting for the session's next message.
     * Falsifiable: without the hold K.seq stores 4, and order 2 is never resent.
     */
    @Test
    void theStoredTargetStaysBelowAnUndecidedOrder() throws Exception {
        for (final boolean group : new boolean[] {true, false}) {
            final SessionID sid = new SessionID("FIX.4.4", "TRADERX", group ? "UNDECIDED-G" : "UNDECIDED-S");
            try (Connection nc = Nats.connect(url(NATS))) {
                final FixSessionStore a = start(NATS, "gw-a");
                try {
                    field(a, "groupCommit", group);
                    final MessageStore ma = a.create(sid);
                    assertTrue(a.acquire(sid));
                    ma.incrNextTargetMsgSeqNum();             // order 1 decided
                    a.undecidedFrom(sid, 2);                   // order 2 submitted, undecided
                    ma.incrNextTargetMsgSeqNum();             // fromApp returned for 2...
                    ma.incrNextTargetMsgSeqNum();             // ...and for 3
                    ma.set(1, "8=FIX.4.4\u00019=10\u000135=8\u000134=1\u000110=000\u0001");
                    ma.incrNextSenderMsgSeqNum();
                    ma.get(1, 1, new ArrayList<>());          // every commit so far has landed
                    assertEquals(4, ma.getNextTargetMsgSeqNum(), group + ": QuickFIX/J's own number moves on");
                    assertEquals("2", storedTarget(nc, sid), group + ": K.seq holds at the undecided order");
                    a.undecidedFrom(sid, Integer.MAX_VALUE);   // order 2 answered
                    for (int i = 0; i < 50 && !"4".equals(storedTarget(nc, sid)); i++) {
                        Thread.sleep(20);
                    }
                    assertEquals("4", storedTarget(nc, sid), group + ": decided, the target is stored at once");
                } finally {
                    a.close();
                }
            }
        }
    }

    private static String storedTarget(final Connection nc, final SessionID sid) throws Exception {
        return nc.keyValue(FixSessionStore.BUCKET).get(FixSessionStore.keyOf(sid) + ".seq").getValueAsString()
            .split(" ")[1];
    }

    private static Object field(final Object o, final String name) throws Exception {
        final java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void field(final Object o, final String name, final Object value) throws Exception {
        final java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
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
