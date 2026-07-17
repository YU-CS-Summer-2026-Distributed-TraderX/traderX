package finos.traderx.ordermatcher.lmax;

import io.nats.client.Connection;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.StorageType;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Atomic NATS-KV witness lease used only by the opt-in YU11 fast failover mode. */
public final class FastWitness implements AutoCloseable {
    public static final String BUCKET = "TRADERX_BLP_FAST_WITNESS";

    private final WitnessStore store;
    private final String key;
    private final String clusterId;
    private final String holderIdentity;
    private final String schemaChecksum;
    private final long ttlMs;
    private final long ttlNs;
    private final long renewMs;
    private final Runnable lostCallback;
    private final LongAdder claimAttempts = new LongAdder();
    private final LongAdder claimConflicts = new LongAdder();
    private final LongAdder ambiguousOperations = new LongAdder();
    private final LongAdder lostClaims = new LongAdder();

    private volatile boolean held;
    private volatile long revision;
    private volatile long epoch = -1L;
    private volatile long lastConfirmedNs;
    private volatile long lastClaimLatencyNs;
    private volatile String lastError = "";
    private ScheduledExecutorService scheduler;

    public FastWitness(Connection connection, String clusterId, String holderIdentity,
                       String schemaChecksum, long ttlMs, long renewMs,
                       Runnable lostCallback) {
        this(new NatsWitnessStore(connection), clusterId, holderIdentity, schemaChecksum,
            ttlMs, renewMs, lostCallback);
    }

    FastWitness(WitnessStore store, String clusterId, String holderIdentity,
                String schemaChecksum, long ttlMs, long renewMs, Runnable lostCallback) {
        if (ttlMs < 10L || renewMs < 1L || renewMs >= ttlMs) {
            throw new IllegalArgumentException("fast witness requires 1 <= renew-ms < ttl-ms");
        }
        this.store = store;
        this.clusterId = clusterId;
        this.holderIdentity = holderIdentity;
        this.schemaChecksum = schemaChecksum;
        this.ttlMs = ttlMs;
        this.ttlNs = TimeUnit.MILLISECONDS.toNanos(ttlMs);
        this.renewMs = renewMs;
        this.lostCallback = lostCallback;
        this.key = clusterId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "blp-fast-witness");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::renewTick, renewMs, renewMs, TimeUnit.MILLISECONDS);
    }

    /** Claim an absent, self-owned, or expired record using its exact KV revision. */
    public synchronized boolean tryClaim(long requestedEpoch) {
        claimAttempts.increment();
        long started = System.nanoTime();
        try {
            WitnessEntry current = store.get(key);
            long nowMs = System.currentTimeMillis();
            long nextRevision;
            if (current == null) {
                nextRevision = store.create(key, encode(requestedEpoch, 0L, nowMs));
            } else {
                WitnessRecord record = decode(current.value());
                boolean self = record != null
                    && holderIdentity.equals(record.holderIdentity())
                    && clusterId.equals(record.clusterId())
                    && schemaChecksum.equals(record.schemaChecksum());
                if (!self && !expired(current, record, nowMs)) {
                    claimConflicts.increment();
                    return false;
                }
                nextRevision = store.update(key,
                    encode(requestedEpoch, current.revision(), nowMs), current.revision());
            }
            held = true;
            revision = nextRevision;
            epoch = requestedEpoch;
            lastConfirmedNs = System.nanoTime();
            lastClaimLatencyNs = lastConfirmedNs - started;
            lastError = "";
            return true;
        } catch (Exception ex) {
            // A failed exact-revision update is normally a clean CAS loss. Verify that another
            // live holder won before classifying it as a conflict; an unreadable/ambiguous result
            // remains fail-closed and is counted separately.
            try {
                WitnessEntry current = store.get(key);
                WitnessRecord record = current == null ? null : decode(current.value());
                long nowMs = System.currentTimeMillis();
                boolean foreignLiveHolder = record != null
                    && (!holderIdentity.equals(record.holderIdentity())
                        || !clusterId.equals(record.clusterId())
                        || !schemaChecksum.equals(record.schemaChecksum()))
                    && !expired(current, record, nowMs);
                if (foreignLiveHolder) {
                    claimConflicts.increment();
                    lastError = "witness CAS conflict";
                    return false;
                }
            } catch (Exception ignored) {
                // The verification read is also ambiguous; preserve the original exception.
            }
            ambiguousOperations.increment();
            lastError = ex.toString();
            return false;
        }
    }

    private synchronized void renewTick() {
        if (!held) return;
        try {
            long nowMs = System.currentTimeMillis();
            revision = store.update(key, encode(epoch, revision, nowMs), revision);
            lastConfirmedNs = System.nanoTime();
            lastError = "";
            return;
        } catch (Exception first) {
            ambiguousOperations.increment();
            lastError = first.toString();
        }

        // Resolve an ambiguous update. A timed-out CAS may have landed, so adopt its revision only
        // after reading back our exact holder/epoch/schema, then retry the CAS on the next tick.
        try {
            WitnessEntry current = store.get(key);
            WitnessRecord record = current == null ? null : decode(current.value());
            if (record == null
                || !holderIdentity.equals(record.holderIdentity())
                || !clusterId.equals(record.clusterId())
                || !schemaChecksum.equals(record.schemaChecksum())
                || record.leaderEpoch() != epoch) {
                loseClaim("foreign witness record");
                return;
            }
            revision = current.revision();
        } catch (Exception ex) {
            ambiguousOperations.increment();
            lastError = ex.toString();
        }
        if (System.nanoTime() - lastConfirmedNs >= ttlNs) {
            loseClaim("witness renewal deadline");
        }
    }

    private void loseClaim(String reason) {
        if (!held) return;
        held = false;
        lostClaims.increment();
        lastError = reason;
        if (lostCallback != null) lostCallback.run();
    }

    private boolean expired(WitnessEntry entry, WitnessRecord record, long nowMs) {
        long serverExpiry = entry.createdAtMillis() + ttlMs;
        long encodedExpiry = record == null ? Long.MIN_VALUE : record.expiresAtMillis();
        return nowMs >= Math.max(serverExpiry, encodedExpiry);
    }

    private byte[] encode(long leaderEpoch, long previousRevision, long nowMs) {
        String value = clusterId + "|" + holderIdentity + "|" + leaderEpoch + "|"
            + previousRevision + "|" + nowMs + "|" + (nowMs + ttlMs) + "|" + schemaChecksum;
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static WitnessRecord decode(byte[] value) {
        if (value == null) return null;
        try {
            String[] fields = new String(value, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 7) return null;
            return new WitnessRecord(fields[0], fields[1], Long.parseLong(fields[2]),
                Long.parseLong(fields[3]), Long.parseLong(fields[4]),
                Long.parseLong(fields[5]), fields[6]);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public boolean isHeld() {
        return held && System.nanoTime() - lastConfirmedNs < ttlNs;
    }
    public long revision() { return revision; }
    public long epoch() { return epoch; }
    public long lastClaimLatencyNanos() { return lastClaimLatencyNs; }
    public long claimAttemptCount() { return claimAttempts.sum(); }
    public long claimConflictCount() { return claimConflicts.sum(); }
    public long ambiguousOperationCount() { return ambiguousOperations.sum(); }
    public long lostClaimCount() { return lostClaims.sum(); }
    public String lastError() { return lastError; }

    public synchronized void relinquish() { held = false; }

    @Override
    public synchronized void close() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        held = false;
    }

    record WitnessRecord(String clusterId, String holderIdentity, long leaderEpoch,
                         long previousRevision, long claimedAtMillis,
                         long expiresAtMillis, String schemaChecksum) { }

    record WitnessEntry(byte[] value, long revision, long createdAtMillis) { }

    interface WitnessStore {
        WitnessEntry get(String key) throws Exception;
        long create(String key, byte[] value) throws Exception;
        long update(String key, byte[] value, long expectedRevision) throws Exception;
    }

    private static final class NatsWitnessStore implements WitnessStore {
        private final KeyValue keyValue;

        private NatsWitnessStore(Connection connection) {
            try {
                KeyValueManagement management = connection.keyValueManagement();
                try {
                    management.getBucketInfo(BUCKET);
                } catch (Exception missing) {
                    try {
                        management.create(KeyValueConfiguration.builder(BUCKET)
                            .description("TraderX BLP fast failover CAS witness")
                            .storageType(StorageType.File)
                            .maxHistoryPerKey(1)
                            .build());
                    } catch (Exception racedCreate) {
                        management.getBucketInfo(BUCKET);
                    }
                }
                this.keyValue = connection.keyValue(BUCKET);
            } catch (Exception ex) {
                throw new IllegalStateException("cannot initialize fast witness KV bucket", ex);
            }
        }

        @Override
        public WitnessEntry get(String key) throws Exception {
            KeyValueEntry entry = keyValue.get(key);
            if (entry == null) return null;
            return new WitnessEntry(entry.getValue(), entry.getRevision(),
                entry.getCreated().toInstant().toEpochMilli());
        }

        @Override public long create(String key, byte[] value) throws Exception {
            return keyValue.create(key, value);
        }

        @Override public long update(String key, byte[] value, long expectedRevision)
            throws Exception {
            return keyValue.update(key, value, expectedRevision);
        }
    }
}
