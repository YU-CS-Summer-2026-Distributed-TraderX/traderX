package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;
import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AeronMdcReplicationTest {
    @TempDir Path tempDir;

    @Test
    void archiveAndFollowerDestinationsShareOneSessionAndPositionSpace() throws Exception {
        String aeronDir = tempDir.resolve("aeron").toString();
        MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED);
        String archiveChannel = "aeron:udp?endpoint=127.0.0.1:23127";
        String followerChannel = "aeron:udp?endpoint=127.0.0.1:23123";

        try (MediaDriver driver = MediaDriver.launch(driverContext);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
             Subscription archive = aeron.addSubscription(archiveChannel, 1101);
             AeronReplicator primary = new AeronReplicator(aeron,
                 "aeron:udp?control-mode=manual", 1101, CommonContext.IPC_CHANNEL, 1102,
                 7L, ReplicationAckMode.ON_RING, ReplicationFailurePolicy.DEGRADED_SOLO,
                 1_000L, false, () -> true, archiveChannel, followerChannel);
             AeronReplicationFollower follower = new AeronReplicationFollower(aeron,
                 followerChannel, 1101, CommonContext.IPC_CHANNEL, 1102,
                 7L, ReplicationAckMode.ON_RING, 64, 1_000L)) {
            RingBuffer<InputEvent> ring = RingBuffer.createSingleProducer(InputEvent::newInstance, 64);
            follower.setInputRing(ring);
            CountDownLatch ready = new CountDownLatch(1);
            follower.start(ready::countDown, () -> { });
            assertThat(primary.awaitConnected(5_000L)).isTrue();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            primary.onEvent(event(), 0L, true);
            await(() -> follower.lastInputSeq() == 0L, 5_000L);

            AtomicInteger archiveSession = new AtomicInteger();
            AtomicLong archivePosition = new AtomicLong();
            await(() -> archive.poll((buffer, offset, length, header) -> {
                archiveSession.set(header.sessionId());
                archivePosition.set(header.position());
            }, 8) > 0, 5_000L);

            assertThat(archiveSession.get()).isEqualTo(primary.publicationSessionId());
            assertThat(archivePosition.get()).isEqualTo(primary.publicationPosition());
            assertThat(follower.faultCode()).isZero();
        }
    }

    private static InputEvent event() {
        InputEvent event = InputEvent.newInstance();
        event.type = InputEvent.TYPE_ORDER_NEW;
        event.side = InputEvent.SIDE_BUY;
        event.orderRef = 101;
        event.accountId = 22214;
        event.securityId = 7;
        event.qty = 10;
        event.limitPx = 123_000_000L;
        event.priceTicks = 122_000_000L;
        event.eventTimeMillis = 17L;
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
    private interface Check { boolean ok() throws Exception; }
}
