package finos.traderx.ordermatcher.lmax;

import java.util.Locale;

public enum ReplicationAckMode {
    ON_RING, DURABLE;

    public static ReplicationAckMode parse(String value) {
        if (value == null || value.isBlank()) return ON_RING;
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "onring", "on_ring" -> ON_RING;
            case "durable" -> DURABLE;
            default -> throw new IllegalArgumentException(
                "BLP_REPLICATION_ACK_MODE must be onring or durable, got: " + value);
        };
    }
}
