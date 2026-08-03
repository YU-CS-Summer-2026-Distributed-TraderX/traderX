package finos.traderx.ordermatcher.lmax;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Fixed binary manifest at the front of a bootstrap bundle. The HMAC covers every preceding
 * manifest byte; the two SHA-256 values bind the following snapshot and symbol payloads.
 */
public record AeronBootstrapManifest(
    long leaderEpoch,
    long inputSeq,
    long archivePosition,
    long payloadChecksum,
    int dataSessionId,
    long correlationId,
    long snapshotLength,
    long symbolsLength,
    byte[] snapshotHash,
    byte[] symbolsHash,
    byte[] schemaChecksumHash,
    byte[] authTag
) {
    static final int MAGIC = 0x59313142; // Y11B
    static final int VERSION = 1;
    public static final int HASH_BYTES = 32;
    public static final int SIGNED_BYTES = 168;
    public static final int BYTES = 200;

    public AeronBootstrapManifest {
        snapshotHash = copyHash(snapshotHash, "snapshotHash");
        symbolsHash = copyHash(symbolsHash, "symbolsHash");
        schemaChecksumHash = copyHash(schemaChecksumHash, "schemaChecksumHash");
        authTag = copyHash(authTag, "authTag");
    }

    static AeronBootstrapManifest signed(long leaderEpoch, long inputSeq, long archivePosition,
                                         long payloadChecksum, int dataSessionId, long correlationId,
                                         long snapshotLength, long symbolsLength, byte[] snapshotHash,
                                         byte[] symbolsHash, byte[] schemaHash, byte[] secret) {
        AeronBootstrapManifest unsigned = new AeronBootstrapManifest(leaderEpoch, inputSeq,
            archivePosition, payloadChecksum, dataSessionId, correlationId, snapshotLength,
            symbolsLength, snapshotHash, symbolsHash, schemaHash, new byte[HASH_BYTES]);
        byte[] encoded = unsigned.encode();
        byte[] tag = AeronBootstrapAuth.hmac(secret, Arrays.copyOf(encoded, SIGNED_BYTES));
        return new AeronBootstrapManifest(leaderEpoch, inputSeq, archivePosition, payloadChecksum,
            dataSessionId, correlationId, snapshotLength, symbolsLength, snapshotHash,
            symbolsHash, schemaHash, tag);
    }

    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC).putInt(VERSION);
        buffer.putLong(leaderEpoch).putLong(inputSeq).putLong(archivePosition)
            .putLong(payloadChecksum);
        buffer.putInt(dataSessionId).putInt(0);
        buffer.putLong(correlationId).putLong(snapshotLength).putLong(symbolsLength);
        buffer.put(snapshotHash).put(symbolsHash).put(schemaChecksumHash).put(authTag);
        return buffer.array();
    }

    public static AeronBootstrapManifest decode(byte[] bytes, byte[] secret) {
        if (bytes == null || bytes.length != BYTES) {
            throw new IllegalArgumentException("bootstrap manifest must be exactly " + BYTES + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.getInt() != VERSION) {
            throw new IllegalArgumentException("unsupported bootstrap manifest");
        }
        long epoch = buffer.getLong();
        long inputSeq = buffer.getLong();
        long position = buffer.getLong();
        long checksum = buffer.getLong();
        int sessionId = buffer.getInt();
        buffer.getInt();
        long correlation = buffer.getLong();
        long snapshotLength = buffer.getLong();
        long symbolsLength = buffer.getLong();
        byte[] snapshotHash = new byte[HASH_BYTES];
        byte[] symbolsHash = new byte[HASH_BYTES];
        byte[] schemaHash = new byte[HASH_BYTES];
        byte[] tag = new byte[HASH_BYTES];
        buffer.get(snapshotHash).get(symbolsHash).get(schemaHash).get(tag);
        byte[] expected = AeronBootstrapAuth.hmac(secret, Arrays.copyOf(bytes, SIGNED_BYTES));
        if (!MessageDigest.isEqual(tag, expected)) {
            throw new IllegalArgumentException("bootstrap manifest HMAC mismatch");
        }
        return new AeronBootstrapManifest(epoch, inputSeq, position, checksum, sessionId,
            correlation, snapshotLength, symbolsLength, snapshotHash, symbolsHash, schemaHash, tag);
    }

    private static byte[] copyHash(byte[] value, String name) {
        if (value == null || value.length != HASH_BYTES) {
            throw new IllegalArgumentException(name + " must be 32 bytes");
        }
        return value.clone();
    }
}
