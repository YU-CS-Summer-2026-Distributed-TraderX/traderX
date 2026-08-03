package finos.traderx.ordermatcher.lmax;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.RecordingDescriptorConsumer;
import io.aeron.archive.client.ReplayMerge;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Remote-primary Archive replay merged into the follower's live UDP destination. */
public final class AeronArchiveReplayMerge implements AutoCloseable {
    private static final String MANUAL_SUBSCRIPTION = "aeron:udp?control-mode=manual";

    private final AeronArchive archive;
    private final Subscription subscription;
    private final ReplayMerge replayMerge;
    private volatile boolean merged;

    public AeronArchiveReplayMerge(Aeron aeron, Config config,
                                   AeronFollowerCheckpointStore.Record checkpoint,
                                   long requiredRecordingPosition) {
        this.archive = connectArchive(aeron, config);
        Recording recording = awaitRecording(archive, config,
            checkpoint == null ? -1 : checkpoint.dataSessionId(), requiredRecordingPosition);
        long startPosition = recording.startPosition();
        if (checkpoint != null
            && checkpoint.recordingPosition() >= recording.startPosition()
            && checkpoint.recordingPosition() <= recording.currentPosition()) {
            startPosition = checkpoint.recordingPosition();
        }
        this.subscription = aeron.addSubscription(MANUAL_SUBSCRIPTION, config.dataStreamId());
        String replayChannel = "aeron:udp?session-id=" + recording.sessionId();
        this.replayMerge = new ReplayMerge(subscription, archive, replayChannel,
            config.replayDestination(), config.liveDestination(), recording.recordingId(),
            startPosition);
    }

    public int poll(FragmentHandler handler, int fragmentLimit) {
        if (!merged) {
            int work = replayMerge.poll(handler, fragmentLimit);
            if (replayMerge.hasFailed()) {
                throw new IllegalStateException("Aeron Archive replay-to-live merge failed");
            }
            merged = replayMerge.isMerged();
            return work;
        }
        return subscription.poll(handler, fragmentLimit);
    }

    public boolean isMerged() { return merged; }
    public boolean isConnected() { return replayMerge.image() != null || subscription.isConnected(); }
    public String status() { return replayMerge.toString(); }

    /** Where a cross-epoch follower must start: the new leader epoch's first input sequence. */
    public record StreamStart(long firstInputSeq, long leaderEpoch, boolean fromRecording) { }

    /**
     * Determines the current leader epoch's first business input sequence (S0), so a
     * checkpoint-less follower can align its local state to exactly S0-1 before joining.
     *
     * <p>Authoritative source: the first fragment of the newest matching Archive recording — the
     * recording IS the epoch's stream, so its first fragment carries S0 and the epoch (decoded
     * with the production codec, cross-checkable by the caller). When the recording is still
     * empty (a promoted primary that has not accepted traffic yet), the next fragment published
     * will be {@code peerHighWatermark + 1}, which the caller reads from the authenticated
     * heartbeat; with no history at all that degrades to 0 — the legacy stream-origin contract.
     */
    public static StreamStart probeStreamStart(Aeron aeron, Config config,
                                               long peerHighWatermark, long timeoutMs) {
        try (AeronArchive archive = connectArchive(aeron, config)) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            RecordingFinder finder = new RecordingFinder(config.dataStreamId(),
                config.recordingChannelFragment(), -1);
            Recording recording = null;
            while (System.nanoTime() < deadline) {
                finder.reset();
                archive.listRecordings(0L, Integer.MAX_VALUE, finder);
                if (finder.recording != null) {
                    recording = finder.recording;
                    break;
                }
                LockSupport.parkNanos(1_000_000L);
            }
            if (recording == null) {
                throw new IllegalStateException("bootstrap probe found no primary Archive recording"
                    + " for stream=" + config.dataStreamId());
            }
            long current = archive.getMaxRecordedPosition(recording.recordingId());
            if (current <= recording.startPosition()) {
                return new StreamStart(peerHighWatermark + 1L, -1L, false);
            }
            // The probe replays to its OWN endpoint (replay port + 2, opened in the manifests):
            // sharing the ReplayMerge replay endpoint leaves driver-side endpoint residue that
            // the later merge subscription never forms an image through (observed live:
            // ReplayMerge stuck at image=null, activeTransportCount=0 after a probe on the
            // shared endpoint).
            String probeDestination = probeDestination(config.replayDestination());
            try (Subscription probe = aeron.addSubscription(
                    probeDestination, config.dataStreamId())) {
                long replaySession = archive.startReplay(recording.recordingId(),
                    recording.startPosition(), current - recording.startPosition(),
                    probeDestination, config.dataStreamId());
                try {
                    AeronReplicationCodec codec = new AeronReplicationCodec();
                    long[] captured = {-1L, -1L};
                    FragmentHandler firstFragment = (buffer, offset, length, header) -> {
                        if (captured[0] < 0
                            && codec.tryInspectInput(buffer, offset, length) == AeronReplicationCodec.OK) {
                            captured[0] = codec.inputSeq();
                            captured[1] = codec.leaderEpoch();
                        }
                    };
                    while (captured[0] < 0 && System.nanoTime() < deadline) {
                        if (probe.poll(firstFragment, 1) == 0) {
                            LockSupport.parkNanos(1_000_000L);
                        }
                    }
                    if (captured[0] < 0) {
                        throw new IllegalStateException(
                            "bootstrap probe replay produced no fragment before timeout");
                    }
                    return new StreamStart(captured[0], captured[1], true);
                } finally {
                    try {
                        archive.stopReplay(replaySession);
                    } catch (RuntimeException ignored) {
                        // replay may have already terminated at its bounded length
                    }
                }
            }
        }
    }

    /** The probe endpoint: the replay destination with its port shifted by +2 (40126 -> 40128). */
    static String probeDestination(String replayDestination) {
        int endpointIndex = replayDestination.lastIndexOf("endpoint=");
        int portIndex = replayDestination.lastIndexOf(':');
        if (endpointIndex < 0 || portIndex <= endpointIndex) {
            throw new IllegalStateException(
                "cannot derive probe endpoint from replay destination: " + replayDestination);
        }
        int portEnd = portIndex + 1;
        while (portEnd < replayDestination.length()
            && Character.isDigit(replayDestination.charAt(portEnd))) {
            portEnd++;
        }
        int port = Integer.parseInt(replayDestination.substring(portIndex + 1, portEnd));
        return replayDestination.substring(0, portIndex + 1) + (port + 2)
            + replayDestination.substring(portEnd);
    }

    private static AeronArchive connectArchive(Aeron aeron, Config config) {
        return AeronArchive.connect(new AeronArchive.Context()
            .aeron(aeron)
            .ownsAeronClient(false)
            .controlRequestChannel(config.controlRequestChannel())
            .controlResponseChannel(config.controlResponseChannel())
            .messageTimeoutNs(TimeUnit.MILLISECONDS.toNanos(config.timeoutMs()))
            .clientName("traderx-blp-archive-catchup"));
    }

    private static Recording awaitRecording(AeronArchive archive, Config config,
                                             int requiredSessionId,
                                             long requiredRecordingPosition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs());
        RecordingFinder finder = new RecordingFinder(config.dataStreamId(),
            config.recordingChannelFragment(), requiredSessionId);
        while (System.nanoTime() < deadline) {
            finder.reset();
            archive.listRecordings(0L, Integer.MAX_VALUE, finder);
            if (finder.recording != null) {
                Recording found = finder.recording;
                long current = archive.getMaxRecordedPosition(found.recordingId());
                if (requiredRecordingPosition < 0L || current >= requiredRecordingPosition) {
                    return new Recording(found.recordingId(), found.startPosition(),
                        current, found.sessionId());
                }
            }
            LockSupport.parkNanos(1_000_000L);
        }
        throw new IllegalStateException("no matching primary Archive recording for stream="
            + config.dataStreamId() + " fragment=" + config.recordingChannelFragment());
    }

    @Override
    public void close() {
        replayMerge.close();
        subscription.close();
        archive.close();
    }

    public record Config(String controlRequestChannel, String controlResponseChannel,
                         String replayDestination, String liveDestination,
                         String recordingChannelFragment, int dataStreamId,
                         long timeoutMs) { }

    private record Recording(long recordingId, long startPosition,
                             long currentPosition, int sessionId) { }

    private static final class RecordingFinder implements RecordingDescriptorConsumer {
        private final int expectedStreamId;
        private final String channelFragment;
        private final int requiredSessionId;
        private Recording recording;

        private RecordingFinder(int expectedStreamId, String channelFragment,
                                int requiredSessionId) {
            this.expectedStreamId = expectedStreamId;
            this.channelFragment = channelFragment == null ? "" : channelFragment;
            this.requiredSessionId = requiredSessionId;
        }

        void reset() { recording = null; }

        @Override
        public void onRecordingDescriptor(long controlSessionId, long correlationId,
                                          long recordingId, long startTimestamp,
                                          long stopTimestamp, long startPosition,
                                          long stopPosition, int initialTermId,
                                          int segmentFileLength, int termBufferLength,
                                          int mtuLength, int sessionId, int streamId,
                                          String strippedChannel, String originalChannel,
                                          String sourceIdentity) {
            if (streamId != expectedStreamId
                || (requiredSessionId >= 0 && sessionId != requiredSessionId)
                || (!channelFragment.isEmpty() && !originalChannel.contains(channelFragment))) {
                return;
            }
            long currentPosition = stopPosition;
            if (recording == null || recordingId > recording.recordingId()) {
                recording = new Recording(recordingId, startPosition, currentPosition, sessionId);
            }
        }
    }
}
