package finos.traderx.ordermatcher.lmax;

/** Exact transport boundary represented by a full-state snapshot. */
public record AeronSnapshotBoundary(long leaderEpoch, long inputSeq, long recordingPosition,
                                    long payloadChecksum, int dataSessionId) {
    public static final AeronSnapshotBoundary NONE =
        new AeronSnapshotBoundary(-1L, -1L, -1L, 0L, -1);

    public boolean transferable() {
        return leaderEpoch >= 0L && inputSeq >= 0L && recordingPosition >= 0L
            && dataSessionId >= 0;
    }
}
