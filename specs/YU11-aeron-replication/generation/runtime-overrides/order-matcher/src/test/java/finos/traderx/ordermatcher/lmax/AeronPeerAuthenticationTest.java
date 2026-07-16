package finos.traderx.ordermatcher.lmax;

import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AeronPeerAuthenticationTest {
    private static final byte[] SECRET =
        "yu11-test-secret-is-at-least-thirty-two-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir Path tempDir;

    @Test
    void signedHelloAuthenticatesExpectedIdentityAndRejectsTamperAndNMinusOne() {
        AeronPeerAuthenticator primary = new AeronPeerAuthenticator(primaryIdentity(), SECRET);
        AeronPeerAuthenticator follower = new AeronPeerAuthenticator(followerIdentity(), SECRET);
        AeronControlCodec primaryCodec = new AeronControlCodec();
        AeronControlCodec followerCodec = new AeronControlCodec();
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(AeronControlCodec.HELLO_BYTES));
        long now = System.currentTimeMillis();

        primary.encodeHello(primaryCodec, buffer, 0, now);
        assertThat(follower.validateHello(followerCodec, buffer, 0,
            AeronControlCodec.HELLO_BYTES, now)).isEqualTo(AeronPeerAuthenticator.AUTH_OK);

        byte authByte = buffer.getByte(AeronControlCodec.HELLO_BYTES - 1);
        buffer.putByte(AeronControlCodec.HELLO_BYTES - 1, (byte) (authByte ^ 1));
        assertThat(follower.validateHello(followerCodec, buffer, 0,
            AeronControlCodec.HELLO_BYTES, now)).isEqualTo(AeronPeerAuthenticator.AUTH_HMAC);

        primary.encodeHello(primaryCodec, buffer, 0, now);
        buffer.putShort(6, (short) 0, ByteOrder.LITTLE_ENDIAN);
        assertThat(follower.validateHello(followerCodec, buffer, 0,
            AeronControlCodec.HELLO_BYTES, now)).isEqualTo(AeronPeerAuthenticator.AUTH_WIRE);
    }

    @Test
    void signedHeartbeatBindsWatermarksAndRejectsTamper() {
        AeronPeerAuthenticator primary = new AeronPeerAuthenticator(primaryIdentity(), SECRET);
        AeronPeerAuthenticator follower = new AeronPeerAuthenticator(followerIdentity(), SECRET);
        AeronControlCodec primaryCodec = new AeronControlCodec();
        AeronControlCodec followerCodec = new AeronControlCodec();
        UnsafeBuffer buffer = new UnsafeBuffer(
            ByteBuffer.allocateDirect(AeronControlCodec.HEARTBEAT_BYTES));

        primary.encodeHeartbeat(primaryCodec, buffer, 0, 7L,
            AeronPeerAuthenticator.ROLE_PRIMARY, 123L, 10L, 9L, 8L, 1_056L);
        assertThat(follower.validateHeartbeat(followerCodec, buffer, 0,
            AeronControlCodec.HEARTBEAT_BYTES, 7L)).isEqualTo(AeronPeerAuthenticator.AUTH_OK);
        assertThat(followerCodec.heartbeatRecordingPosition()).isEqualTo(1_056L);

        buffer.putByte(AeronControlCodec.HEARTBEAT_BYTES - 1,
            (byte) (buffer.getByte(AeronControlCodec.HEARTBEAT_BYTES - 1) ^ 1));
        assertThat(follower.validateHeartbeat(followerCodec, buffer, 0,
            AeronControlCodec.HEARTBEAT_BYTES, 7L)).isEqualTo(AeronPeerAuthenticator.AUTH_HMAC);
    }

    @Test
    void mutualAgentsBindSessionThenExchangeDirectHeartbeats() throws Exception {
        String aeronDir = tempDir.resolve("peer-control").toString();
        MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED);

        try (MediaDriver driver = MediaDriver.launch(driverContext);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
             AeronPeerControlAgent primary = new AeronPeerControlAgent(aeron,
                 CommonContext.IPC_CHANNEL, CommonContext.IPC_CHANNEL, 1401,
                 primaryIdentity(), SECRET, 5L, 50L, () -> 10L, () -> 9L, () -> 8L);
             AeronPeerControlAgent follower = new AeronPeerControlAgent(aeron,
                 CommonContext.IPC_CHANNEL, CommonContext.IPC_CHANNEL, 1401,
                 followerIdentity(1L), SECRET, 5L, 50L, () -> 11L, () -> 10L, () -> 9L)) {
            primary.start(() -> { });
            follower.start(() -> { });

            await(() -> primary.authenticated() && follower.authenticated(), 5_000L);
            await(() -> primary.sessionReady() && follower.sessionReady()
                    && primary.peerAppliedSeq() == 9L && follower.peerAppliedSeq() == 8L,
                5_000L);

            assertThat(primary.negotiatedEpoch()).isEqualTo(7L);
            assertThat(follower.negotiatedEpoch()).isEqualTo(7L);

            assertThat(primary.protocolFault()).isZero();
            assertThat(follower.protocolFault()).isZero();
            assertThat(primary.peerHeartbeatAgeMillis()).isLessThan(100L);
            assertThat(follower.peerHeartbeatAgeMillis()).isLessThan(100L);
        }
    }

    private static AeronPeerAuthenticator.Identity primaryIdentity() {
        return new AeronPeerAuthenticator.Identity("traderx", "order-matcher-0",
            "order-matcher-1", 7L, AeronPeerAuthenticator.ROLE_PRIMARY,
            AeronPeerAuthenticator.ROLE_FOLLOWER, 0, 1);
    }

    private static AeronPeerAuthenticator.Identity followerIdentity() {
        return followerIdentity(7L);
    }

    private static AeronPeerAuthenticator.Identity followerIdentity(long epoch) {
        return new AeronPeerAuthenticator.Identity("traderx", "order-matcher-1",
            "order-matcher-0", epoch, AeronPeerAuthenticator.ROLE_FOLLOWER,
            AeronPeerAuthenticator.ROLE_PRIMARY, 1, 0);
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
