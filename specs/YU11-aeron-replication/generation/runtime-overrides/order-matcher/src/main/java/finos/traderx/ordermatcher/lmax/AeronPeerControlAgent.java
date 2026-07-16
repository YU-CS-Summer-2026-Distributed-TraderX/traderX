package finos.traderx.ordermatcher.lmax;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.FragmentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/** Mutual signed hello plus direct heartbeat agent on the YU11 Aeron control stream. */
public final class AeronPeerControlAgent implements AutoCloseable, Runnable {
    private static final Logger log = LoggerFactory.getLogger(AeronPeerControlAgent.class);

    private final ExclusivePublication publication;
    private final Subscription subscription;
    private final AeronPeerAuthenticator authenticator;
    private final AeronPeerAuthenticator.Identity identity;
    private final long heartbeatIntervalNs;
    private final long staleThresholdNs;
    private final LongSupplier highestInputSeq;
    private final LongSupplier journaledSeq;
    private final LongSupplier appliedSeq;
    private final LongSupplier recordingPosition;
    private final AeronControlCodec codec = new AeronControlCodec();
    private final BufferClaim claim = new BufferClaim();
    private final FragmentHandler fragmentHandler = this::onFragment;

    private volatile boolean running;
    private volatile boolean authenticated;
    private volatile int protocolFault;
    private volatile int authenticatedSessionId = Integer.MIN_VALUE;
    private volatile long peerNonce;
    private volatile long lastPeerHeartbeatNs;
    private volatile PeerWatermark peerWatermark = new PeerWatermark(-1L, -1L, -1L, -1L);
    private volatile long negotiatedEpoch;
    private volatile long peerEpoch = -1L;
    private volatile long lastPeerSenderNanos = -1L;
    private volatile boolean heartbeatConfirmed;
    private Thread thread;
    private Runnable faultCallback;

    public AeronPeerControlAgent(Aeron aeron, String publishChannel, String subscribeChannel,
                                 int streamId, AeronPeerAuthenticator.Identity identity,
                                 byte[] secret, long heartbeatIntervalMs, long staleThresholdMs,
                                 LongSupplier highestInputSeq, LongSupplier journaledSeq,
                                 LongSupplier appliedSeq) {
        this(aeron, publishChannel, subscribeChannel, streamId, identity, secret,
            heartbeatIntervalMs, staleThresholdMs, highestInputSeq, journaledSeq,
            appliedSeq, () -> -1L);
    }

    public AeronPeerControlAgent(Aeron aeron, String publishChannel, String subscribeChannel,
                                 int streamId, AeronPeerAuthenticator.Identity identity,
                                 byte[] secret, long heartbeatIntervalMs, long staleThresholdMs,
                                 LongSupplier highestInputSeq, LongSupplier journaledSeq,
                                 LongSupplier appliedSeq, LongSupplier recordingPosition) {
        this.publication = aeron.addExclusivePublication(publishChannel, streamId);
        this.subscription = aeron.addSubscription(subscribeChannel, streamId);
        this.identity = identity;
        this.authenticator = new AeronPeerAuthenticator(identity, secret);
        this.heartbeatIntervalNs = Math.max(1L, heartbeatIntervalMs) * 1_000_000L;
        this.staleThresholdNs = Math.max(heartbeatIntervalMs + 1L, staleThresholdMs) * 1_000_000L;
        this.highestInputSeq = highestInputSeq;
        this.journaledSeq = journaledSeq;
        this.appliedSeq = appliedSeq;
        this.recordingPosition = recordingPosition;
        this.negotiatedEpoch = identity.epoch();
    }

    public void start(Runnable faultCallback) {
        if (running) return;
        this.faultCallback = faultCallback;
        running = true;
        thread = new Thread(this, "blp-aeron-peer-control");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        long nextHelloNs = 0L;
        long nextHeartbeatNs = 0L;
        while (running) {
            int fragments = subscription.poll(fragmentHandler, 32);
            long now = System.nanoTime();
            if (now >= nextHelloNs) {
                offerHello();
                nextHelloNs = now + (authenticated ? 1_000_000_000L : 100_000_000L);
            }
            if (authenticated && now >= nextHeartbeatNs) {
                offerHeartbeat(now);
                nextHeartbeatNs = now + heartbeatIntervalNs;
            }
            if (fragments == 0) LockSupport.parkNanos(50_000L);
        }
    }

    private void offerHello() {
        long result = publication.tryClaim(AeronControlCodec.HELLO_BYTES, claim);
        if (result < 0) return;
        authenticator.encodeHello(codec, claim.buffer(), claim.offset(), negotiatedEpoch,
            System.currentTimeMillis());
        claim.commit();
    }

    private void offerHeartbeat(long now) {
        long result = publication.tryClaim(AeronControlCodec.HEARTBEAT_BYTES, claim);
        if (result < 0) return;
        authenticator.encodeHeartbeat(codec, claim.buffer(), claim.offset(),
            negotiatedEpoch, identity.localRole(),
            now, highestInputSeq.getAsLong(), journaledSeq.getAsLong(), appliedSeq.getAsLong(),
            recordingPosition.getAsLong());
        claim.commit();
    }

    private void onFragment(org.agrona.DirectBuffer buffer, int offset, int length,
                            io.aeron.logbuffer.Header header) {
        if (header.sessionId() == publication.sessionId() || protocolFault != 0) return;
        int inspect = codec.tryInspectTemplate(buffer, offset, length);
        if (inspect != AeronControlCodec.OK) {
            fault(AeronPeerAuthenticator.AUTH_WIRE);
            return;
        }
        if (codec.templateId() == finos.traderx.ordermatcher.replication.sbe.PeerHelloMessageDecoder.TEMPLATE_ID) {
            int result = authenticator.validateHello(codec, buffer, offset, length,
                System.currentTimeMillis());
            if (result != AeronPeerAuthenticator.AUTH_OK) {
                fault(result);
                return;
            }
            if (authenticatedSessionId != header.sessionId() || peerNonce != codec.helloNonce()) {
                authenticatedSessionId = header.sessionId();
                peerNonce = codec.helloNonce();
                heartbeatConfirmed = false;
                lastPeerSenderNanos = -1L;
                log.info("Authenticated Aeron peer session={} ordinal={} epoch={}",
                    authenticatedSessionId, codec.helloOrdinal(), codec.helloEpoch());
            }
            peerEpoch = codec.helloEpoch();
            if (identity.localRole() == AeronPeerAuthenticator.ROLE_FOLLOWER) {
                negotiatedEpoch = peerEpoch;
            }
            lastPeerHeartbeatNs = System.nanoTime();
            authenticated = true;
            return;
        }
        if (codec.templateId() == finos.traderx.ordermatcher.replication.sbe.HeartbeatMessageDecoder.TEMPLATE_ID) {
            if (!authenticated || header.sessionId() != authenticatedSessionId) {
                return;
            }
            int result = authenticator.validateHeartbeat(codec, buffer, offset, length,
                negotiatedEpoch);
            if (result != AeronPeerAuthenticator.AUTH_OK) {
                fault(result);
                return;
            }
            if (codec.heartbeatSenderNanos() <= lastPeerSenderNanos) return;
            lastPeerSenderNanos = codec.heartbeatSenderNanos();
            peerWatermark = new PeerWatermark(codec.heartbeatHighestInputSeq(),
                codec.heartbeatJournaledSeq(), codec.heartbeatAppliedSeq(),
                codec.heartbeatRecordingPosition());
            peerEpoch = codec.heartbeatEpoch();
            if (!heartbeatConfirmed) {
                log.info("Aeron peer heartbeat confirmed session={} epoch={}",
                    authenticatedSessionId, negotiatedEpoch);
            }
            heartbeatConfirmed = true;
            lastPeerHeartbeatNs = System.nanoTime();
        }
    }

    private void fault(int code) {
        protocolFault = code;
        authenticated = false;
        running = false;
        log.error("Aeron peer authentication fault: code={} local={} expected={}",
            code, identity.localPeerId(), identity.expectedPeerId());
        if (faultCallback != null) faultCallback.run();
    }

    public boolean authenticated() { return authenticated && protocolFault == 0; }
    public boolean sessionReady() { return authenticated() && heartbeatConfirmed; }
    public int protocolFault() { return protocolFault; }
    public boolean peerStale() {
        long last = lastPeerHeartbeatNs;
        return last <= 0L || System.nanoTime() - last > staleThresholdNs;
    }
    public long peerHeartbeatAgeMillis() {
        long last = lastPeerHeartbeatNs;
        return last <= 0L ? Long.MAX_VALUE : Math.max(0L, System.nanoTime() - last) / 1_000_000L;
    }
    public long peerHighestInputSeq() { return peerWatermark.highestInputSeq(); }
    public long peerJournaledSeq() { return peerWatermark.journaledSeq(); }
    public long peerAppliedSeq() { return peerWatermark.appliedSeq(); }
    public long peerRecordingPosition() { return peerWatermark.recordingPosition(); }
    public long negotiatedEpoch() { return negotiatedEpoch; }
    public long peerEpoch() { return peerEpoch; }

    @Override
    public void close() {
        running = false;
        if (thread != null) thread.interrupt();
        publication.close();
        subscription.close();
    }

    private record PeerWatermark(long highestInputSeq, long journaledSeq,
                                 long appliedSeq, long recordingPosition) { }
}
