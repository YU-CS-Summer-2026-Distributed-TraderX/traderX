package finos.traderx.ordermatcher.lmax;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.RecordingDescriptorConsumer;
import io.aeron.archive.client.ReplayMerge;
import io.aeron.logbuffer.FragmentHandler;

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
