package finos.traderx.ordermatcher.risk;

/**
 * Where a deduplicated NEW's answer lives while it is applied: the ADR-090 stream ring ({@link StreamDedupe}) or the
 * ADR-104 per-session ClOrdID ring ({@link SessionRing}). {@link BlpRiskState#beginSequenced} names one, and the risk
 * state then answers the key from it instead of the FR-IMRG14 key table.
 */
public interface SequencedAnswers {
    /** After an admit: the answer to remember for the request. */
    void record(int orderRef, byte decision);

    /** After a duplicate: the original order ref, 0 when none is held. */
    int duplicateRef();

    /** After a duplicate: the recorded decision ordinal, or -1 when none is held. */
    byte duplicateDecision();
}
