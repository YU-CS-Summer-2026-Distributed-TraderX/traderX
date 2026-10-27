package finos.traderx.ordermatcher.cluster;

/**
 * YU18 downstream redesign (direction c): the leader-side bridges publish each event on a
 * per-account partition subject, {@code <base>.<accountId mod P>}, so P projection consumers can
 * share the load with disjoint accounts (positions and orders never cross a partition). P comes
 * from {@code BRIDGE_PARTITIONS}; unset or 1 keeps the bare subject, byte-for-byte today's wire.
 * Only the NATS subject changes: the JSON envelope's {@code topic} stays the bare subject, so the
 * cold replay verifier's bytes are unchanged.
 */
final class BridgePartitions {
    private BridgePartitions() {}

    static int count() {
        final String v = System.getenv("BRIDGE_PARTITIONS");
        if (v == null || v.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(v.trim()));
        } catch (final NumberFormatException e) {
            return 1;
        }
    }

    static String[] subjects(final String base, final int count) {
        if (count <= 1) {
            return new String[] {base};
        }
        final String[] out = new String[count];
        for (int p = 0; p < count; p++) {
            out[p] = base + "." + p;
        }
        return out;
    }

    static String subjectFor(final String[] subjects, final int accountId) {
        return subjects[index(accountId, subjects.length)];
    }

    /** The partition (lane) of an account: {@code accountId mod count}, non-negative. */
    static int index(final int accountId, final int count) {
        return count <= 1 ? 0 : Math.floorMod(accountId, count);
    }
}
