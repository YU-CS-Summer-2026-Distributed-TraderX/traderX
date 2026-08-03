package finos.traderx.ordermatcher.lmax;

import java.util.Locale;

/** Runtime-selected BLP replication leg; NATS remains the default and rollback path. */
public enum ReplicationTransport {
    NATS, AERON;

    public static ReplicationTransport parse(String value) {
        if (value == null || value.isBlank()) return NATS;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "nats" -> NATS;
            case "aeron" -> AERON;
            default -> throw new IllegalArgumentException(
                "BLP_REPLICATION_TRANSPORT must be nats or aeron, got: " + value);
        };
    }
}
