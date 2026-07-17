package finos.traderx.ordermatcher.lmax;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FastWitnessTest {
    private static final String SCHEMA = AeronReplicationCodec.SCHEMA_CHECKSUM;

    @Test
    void exactRevisionCasProducesOneWinnerAfterExpiry() throws Exception {
        InMemoryStore store = new InMemoryStore();
        try (FastWitness initial = witness(store, "pod-0", null);
             FastWitness first = witness(store, "pod-1", null);
             FastWitness second = witness(store, "pod-2", null)) {
            assertThat(initial.tryClaim(7L)).isTrue();
            assertThat(first.tryClaim(8L)).isFalse();

            Thread.sleep(250L);
            CountDownLatch start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(() -> { start.await(); return first.tryClaim(8L); });
                var b = pool.submit(() -> { start.await(); return second.tryClaim(8L); });
                start.countDown();
                assertThat(a.get(2, TimeUnit.SECONDS) ^ b.get(2, TimeUnit.SECONDS)).isTrue();
                assertThat(first.claimConflictCount() + second.claimConflictCount()).isEqualTo(2L);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void foreignExactRevisionClosesHeldWitness() throws Exception {
        InMemoryStore store = new InMemoryStore();
        CountDownLatch lost = new CountDownLatch(1);
        try (FastWitness witness = witness(store, "pod-0", lost::countDown)) {
            assertThat(witness.tryClaim(4L)).isTrue();
            witness.start();
            store.force("traderx", "pod-1", 5L);
            assertThat(lost.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(witness.isHeld()).isFalse();
            assertThat(witness.lostClaimCount()).isEqualTo(1L);
        }
    }

    private static FastWitness witness(InMemoryStore store, String pod, Runnable lost) {
        return new FastWitness(store, "traderx", pod, SCHEMA, 200L, 25L, lost);
    }

    private static final class InMemoryStore implements FastWitness.WitnessStore {
        private final AtomicLong revisions = new AtomicLong();
        private FastWitness.WitnessEntry entry;

        @Override public synchronized FastWitness.WitnessEntry get(String key) {
            return entry;
        }

        @Override public synchronized long create(String key, byte[] value) {
            if (entry != null) throw new IllegalStateException("key exists");
            long revision = revisions.incrementAndGet();
            entry = new FastWitness.WitnessEntry(value, revision, System.currentTimeMillis());
            return revision;
        }

        @Override public synchronized long update(String key, byte[] value, long expectedRevision) {
            if (entry == null || entry.revision() != expectedRevision) {
                throw new IllegalStateException("wrong last sequence");
            }
            long revision = revisions.incrementAndGet();
            entry = new FastWitness.WitnessEntry(value, revision, System.currentTimeMillis());
            return revision;
        }

        synchronized void force(String cluster, String holder, long epoch) {
            String value = cluster + "|" + holder + "|" + epoch
                + "|0|0|0|" + SCHEMA;
            long revision = revisions.incrementAndGet();
            entry = new FastWitness.WitnessEntry(value.getBytes(StandardCharsets.UTF_8),
                revision, System.currentTimeMillis());
        }
    }
}
