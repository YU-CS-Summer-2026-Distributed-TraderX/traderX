package finos.traderx.ordermatcher.lmax;

import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AeronShadowRoundTripTest {
    @TempDir Path tempDir;

    @Test
    void comparesCanonicalPayloadWithoutInjectingOrAcknowledging() throws Exception {
        String aeronDir = tempDir.resolve("aeron-shadow").toString();
        MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED);

        try (MediaDriver driver = MediaDriver.launch(driverContext);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
             AeronReplicator publisher = new AeronReplicator(aeron,
                 CommonContext.IPC_CHANNEL, 1201, CommonContext.IPC_CHANNEL, 1202,
                 7L, ReplicationAckMode.ON_RING, ReplicationFailurePolicy.DEGRADED_SOLO,
                 500L, true)) {
            ShadowSequenceMap authoritative = new ShadowSequenceMap(64);
            AtomicBoolean faulted = new AtomicBoolean();
            try (AeronShadowFollower shadow = new AeronShadowFollower(aeron,
                CommonContext.IPC_CHANNEL, 1201, 7L, authoritative, 500L)) {
                shadow.start(() -> faulted.set(true));
                await(publisher::connected, 5_000L);

                InputEvent event = event(101);
                authoritative.put(0L, AeronReplicationCodec.payloadChecksum(event));
                publisher.onEvent(event, 0L, true);

                await(() -> shadow.comparedCount() == 1L, 5_000L);
                assertThat(shadow.lastComparedSeq()).isZero();
                assertThat(shadow.mismatchCount()).isZero();
                assertThat(faulted).isFalse();
            }
        }
    }

    @Test
    void payloadDifferenceIsStickyAndVisible() throws Exception {
        String aeronDir = tempDir.resolve("aeron-shadow-mismatch").toString();
        MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED);

        try (MediaDriver driver = MediaDriver.launch(driverContext);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
             AeronReplicator publisher = new AeronReplicator(aeron,
                 CommonContext.IPC_CHANNEL, 1301, CommonContext.IPC_CHANNEL, 1302,
                 8L, ReplicationAckMode.ON_RING, ReplicationFailurePolicy.DEGRADED_SOLO,
                 500L, true)) {
            ShadowSequenceMap authoritative = new ShadowSequenceMap(64);
            try (AeronShadowFollower shadow = new AeronShadowFollower(aeron,
                CommonContext.IPC_CHANNEL, 1301, 8L, authoritative, 500L)) {
                shadow.start(() -> { });
                await(publisher::connected, 5_000L);

                InputEvent event = event(102);
                authoritative.put(0L, AeronReplicationCodec.payloadChecksum(event) + 1L);
                publisher.onEvent(event, 0L, true);

                await(() -> shadow.faultCode() != AeronShadowFollower.FAULT_NONE, 5_000L);
                assertThat(shadow.faultCode())
                    .isEqualTo(AeronShadowFollower.FAULT_PAYLOAD_MISMATCH);
                assertThat(shadow.mismatchCount()).isEqualTo(1L);
            }
        }
    }

    private static InputEvent event(int orderRef) {
        InputEvent event = InputEvent.newInstance();
        event.type = InputEvent.TYPE_ORDER_NEW;
        event.side = InputEvent.SIDE_BUY;
        event.orderRef = orderRef;
        event.accountId = 22214;
        event.securityId = 7;
        event.qty = 10;
        event.limitPx = 123_000_000L;
        event.priceTicks = 122_000_000L;
        event.eventTimeMillis = 1234L;
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
