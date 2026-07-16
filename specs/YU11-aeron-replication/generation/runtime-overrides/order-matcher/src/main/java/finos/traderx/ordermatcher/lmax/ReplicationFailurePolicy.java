package finos.traderx.ordermatcher.lmax;

import java.util.Locale;

public enum ReplicationFailurePolicy {
    DEGRADED_SOLO, STRICT;

    public static ReplicationFailurePolicy parse(String value) {
        if (value == null || value.isBlank()) return DEGRADED_SOLO;
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "degraded-solo" -> DEGRADED_SOLO;
            case "strict" -> STRICT;
            default -> throw new IllegalArgumentException(
                "BLP_REPLICATION_FAILURE_POLICY must be degraded-solo or strict, got: " + value);
        };
    }

    public static void validate(ReplicationAckMode ackMode, ReplicationFailurePolicy policy,
                                boolean journalEnabled) {
        if (policy == STRICT && ackMode != ReplicationAckMode.DURABLE) {
            throw new IllegalArgumentException(
                "strict replication requires BLP_REPLICATION_ACK_MODE=durable");
        }
        if (ackMode == ReplicationAckMode.DURABLE && !journalEnabled) {
            throw new IllegalArgumentException(
                "durable replication ACK requires ORDER_MATCHER_JOURNAL_ENABLED=true");
        }
    }
}
