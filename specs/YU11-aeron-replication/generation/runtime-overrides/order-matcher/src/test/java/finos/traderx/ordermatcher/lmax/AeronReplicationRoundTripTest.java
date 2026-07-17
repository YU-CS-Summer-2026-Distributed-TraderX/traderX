package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;
import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AeronReplicationRoundTripTest {
    @TempDir Path tempDir;

    @Test
    void claimedSbeInputIsInjectedAndAcknowledgedOverRealAeronIpc() throws Exception {
        String aeronDir = tempDir.resolve("aeron").toString();
        MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED);

        try (MediaDriver driver = MediaDriver.launch(driverContext);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
             AeronReplicator primary = new AeronReplicator(aeron,
                 CommonContext.IPC_CHANNEL, 1101, CommonContext.IPC_CHANNEL, 1102,
                 3L, ReplicationAckMode.ON_RING, ReplicationFailurePolicy.DEGRADED_SOLO,
                 1_000L, false);
             AeronReplicationFollower follower = new AeronReplicationFollower(aeron,
                 CommonContext.IPC_CHANNEL, 1101, CommonContext.IPC_CHANNEL, 1102,
                 3L, ReplicationAckMode.ON_RING, 64, 1_000L)) {
            AeronPublicationSequenceMap primaryBoundaries =
                new AeronPublicationSequenceMap(64);
            primary.setPublicationSequenceMap(primaryBoundaries);
            RingBuffer<InputEvent> ring = RingBuffer.createSingleProducer(InputEvent::newInstance, 64);
            follower.setInputRing(ring);
            CountDownLatch ready = new CountDownLatch(1);
            follower.start(ready::countDown, () -> { });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            InputEvent first = event(101, 11L);
            first.seq = 0L;   // the wire inputSeq includes commands and replicated markers
            primary.onEvent(first, 0L, true);
            await(() -> follower.lastInputSeq() >= 0L, 5_000L);

            InputEvent second = event(102, 12L);
            second.seq = 1L;
            primary.onEvent(second, 1L, true);
            await(() -> {
                primary.pollAcksOnce();
                return primary.followerAckedSeq() >= 1L;
            }, 5_000L);

            assertThat(follower.faultCode()).isZero();
            assertThat(follower.lastInputSeq()).isEqualTo(1L);
            assertThat(ring.get(1L).orderRef).isEqualTo(102);
            assertThat(ring.get(1L).eventTimeMillis).isEqualTo(12L);
            assertThat(primary.followerAckedSeq()).isEqualTo(1L);
            assertThat(primary.degraded()).isFalse();

            FollowerSequenceMap.Entry primaryBoundary = new FollowerSequenceMap.Entry();
            FollowerSequenceMap.Entry followerBoundary = new FollowerSequenceMap.Entry();
            assertThat(primaryBoundaries.read(1L, primaryBoundary)).isTrue();
            assertThat(follower.readSequenceBoundary(1L, followerBoundary)).isTrue();
            assertThat(primaryBoundary.inputSeq).isEqualTo(1L);
            assertThat(primaryBoundary.epoch).isEqualTo(3L);
            assertThat(primaryBoundary.recordingPosition)
                .isEqualTo(followerBoundary.recordingPosition);
            assertThat(primaryBoundary.dataSessionId).isEqualTo(followerBoundary.dataSessionId);
            assertThat(primaryBoundary.checksum).isEqualTo(followerBoundary.checksum);

            InputEvent marker = event(0, 13L);
            marker.type = InputEvent.TYPE_SNAPSHOT;
            marker.seq = 2L;
            primary.onEvent(marker, 2L, true);
            await(() -> follower.lastInputSeq() >= 2L, 5_000L);
            FollowerSequenceMap.Entry markerBoundary = new FollowerSequenceMap.Entry();
            assertThat(primary.readSnapshotBoundary(2L, markerBoundary)).isTrue();
            assertThat(markerBoundary.inputSeq).isEqualTo(2L);
            assertThat(markerBoundary.epoch).isEqualTo(3L);
            assertThat(markerBoundary.recordingPosition).isPositive();
            assertThat(primary.readSnapshotBoundary(1L, markerBoundary)).isFalse();
        }
    }

    private static InputEvent event(int orderRef, long eventTimeMillis) {
        InputEvent event = InputEvent.newInstance();
        event.type = InputEvent.TYPE_ORDER_NEW;
        event.side = InputEvent.SIDE_BUY;
        event.orderRef = orderRef;
        event.accountId = 22214;
        event.securityId = 7;
        event.qty = 10;
        event.limitPx = 123_000_000L;
        event.priceTicks = 122_000_000L;
        event.eventTimeMillis = eventTimeMillis;
        return event;
    }

    private static void await(Check check, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!check.ok()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("condition timed out");
            Thread.sleep(1L);
        }
    }

    @FunctionalInterface
    private interface Check { boolean ok(); }
}
