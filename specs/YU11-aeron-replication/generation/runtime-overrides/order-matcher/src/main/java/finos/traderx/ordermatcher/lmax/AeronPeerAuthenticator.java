package finos.traderx.ordermatcher.lmax;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** HMAC-SHA256 identity and schema authentication for a YU11 Aeron peer session. */
public final class AeronPeerAuthenticator {
    public static final int ROLE_PRIMARY = 1;
    public static final int ROLE_FOLLOWER = 2;

    public static final int AUTH_OK = 0;
    public static final int AUTH_WIRE = 1;
    public static final int AUTH_EPOCH = 2;
    public static final int AUTH_ROLE = 3;
    public static final int AUTH_ORDINAL = 4;
    public static final int AUTH_CLUSTER = 5;
    public static final int AUTH_PEER = 6;
    public static final int AUTH_SCHEMA = 7;
    public static final int AUTH_CLOCK = 8;
    public static final int AUTH_HMAC = 9;

    private static final long DEFAULT_CLOCK_SKEW_MS = 30_000L;

    private final Identity identity;
    private final byte[] secret;
    private final byte[] clusterHash;
    private final byte[] localPeerHash;
    private final byte[] expectedPeerHash;
    private final byte[] schemaHash;
    private final byte[] prefix = new byte[AeronControlCodec.HELLO_SIGNED_BYTES];
    private final byte[] receivedHash = new byte[32];
    private final byte[] receivedTag = new byte[32];
    private final long nonce = new SecureRandom().nextLong();

    public AeronPeerAuthenticator(Identity identity, byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("replication HMAC secret must contain at least 32 bytes");
        }
        this.identity = identity;
        this.secret = secret.clone();
        this.clusterHash = sha256(identity.clusterId());
        this.localPeerHash = sha256(identity.localPeerId());
        this.expectedPeerHash = sha256(identity.expectedPeerId());
        this.schemaHash = sha256(AeronReplicationCodec.SCHEMA_CHECKSUM);
    }

    public void encodeHello(AeronControlCodec codec, MutableDirectBuffer buffer,
                            int offset, long nowMillis) {
        encodeHello(codec, buffer, offset, identity.epoch(), nowMillis);
    }

    public void encodeHello(AeronControlCodec codec, MutableDirectBuffer buffer,
                            int offset, long epoch, long nowMillis) {
        codec.encodeHello(buffer, offset, epoch, identity.localRole(),
            identity.localOrdinal(), nonce, nowMillis, clusterHash, localPeerHash, schemaHash);
        buffer.getBytes(offset, prefix, 0, prefix.length);
        codec.putHelloAuthTag(hmac(prefix));
    }

    public int validateHello(AeronControlCodec codec, DirectBuffer buffer,
                             int offset, int length, long nowMillis) {
        if (codec.tryDecodeHello(buffer, offset, length) != AeronControlCodec.OK) return AUTH_WIRE;
        if (identity.localRole() == ROLE_FOLLOWER && codec.helloEpoch() < identity.epoch()) {
            return AUTH_EPOCH;
        }
        if (codec.helloRole() != identity.expectedRole()) return AUTH_ROLE;
        if (codec.helloOrdinal() != identity.expectedOrdinal()) return AUTH_ORDINAL;
        codec.getHelloClusterHash(receivedHash);
        if (!MessageDigest.isEqual(clusterHash, receivedHash)) return AUTH_CLUSTER;
        codec.getHelloPeerHash(receivedHash);
        if (!MessageDigest.isEqual(expectedPeerHash, receivedHash)) return AUTH_PEER;
        codec.getHelloSchemaHash(receivedHash);
        if (!MessageDigest.isEqual(schemaHash, receivedHash)) return AUTH_SCHEMA;
        if (Math.abs(nowMillis - codec.helloIssuedAtMillis()) > DEFAULT_CLOCK_SKEW_MS) return AUTH_CLOCK;
        codec.getHelloAuthTag(receivedTag);
        buffer.getBytes(offset, prefix, 0, prefix.length);
        return MessageDigest.isEqual(receivedTag, hmac(prefix)) ? AUTH_OK : AUTH_HMAC;
    }

    public void encodeHeartbeat(AeronControlCodec codec, MutableDirectBuffer buffer, int offset,
                                long epoch, int role, long senderNanos, long highestInputSeq,
                                long journaledSeq, long appliedSeq, long recordingPosition) {
        codec.encodeHeartbeat(buffer, offset, epoch, role, senderNanos, highestInputSeq,
            journaledSeq, appliedSeq, recordingPosition);
        buffer.getBytes(offset, prefix, 0, AeronControlCodec.HEARTBEAT_SIGNED_BYTES);
        codec.putHeartbeatAuthTag(hmac(prefix, AeronControlCodec.HEARTBEAT_SIGNED_BYTES));
    }

    public int validateHeartbeat(AeronControlCodec codec, DirectBuffer buffer,
                                 int offset, int length, long expectedEpoch) {
        if (codec.tryDecodeHeartbeat(buffer, offset, length) != AeronControlCodec.OK) {
            return AUTH_WIRE;
        }
        if (codec.heartbeatEpoch() != expectedEpoch) return AUTH_EPOCH;
        if (codec.heartbeatRole() != identity.expectedRole()) return AUTH_ROLE;
        codec.getHeartbeatAuthTag(receivedTag);
        buffer.getBytes(offset, prefix, 0, AeronControlCodec.HEARTBEAT_SIGNED_BYTES);
        return MessageDigest.isEqual(receivedTag,
            hmac(prefix, AeronControlCodec.HEARTBEAT_SIGNED_BYTES)) ? AUTH_OK : AUTH_HMAC;
    }

    public long nonce() { return nonce; }

    public static byte[] loadSecret(String secretFile, String inlineSecret) {
        try {
            byte[] value;
            if (secretFile != null && !secretFile.isBlank()) {
                value = Files.readAllBytes(Path.of(secretFile.trim()));
            } else if (inlineSecret != null && !inlineSecret.isBlank()) {
                value = inlineSecret.getBytes(StandardCharsets.UTF_8);
            } else {
                throw new IllegalArgumentException(
                    "Aeron replication requires BLP_REPLICATION_SECRET_FILE (inline secret is local-only)");
            }
            if (value.length < 32) {
                throw new IllegalArgumentException("replication HMAC secret must contain at least 32 bytes");
            }
            return value;
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("cannot read BLP_REPLICATION_SECRET_FILE", ex);
        }
    }

    private byte[] hmac(byte[] message) {
        return hmac(message, message.length);
    }

    private byte[] hmac(byte[] message, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(message, 0, length);
            return mac.doFinal();
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record Identity(String clusterId, String localPeerId, String expectedPeerId,
                           long epoch, int localRole, int expectedRole,
                           int localOrdinal, int expectedOrdinal) {
        public Identity {
            if (clusterId == null || clusterId.isBlank()) throw new IllegalArgumentException("clusterId required");
            if (localPeerId == null || localPeerId.isBlank()) throw new IllegalArgumentException("localPeerId required");
            if (expectedPeerId == null || expectedPeerId.isBlank()) throw new IllegalArgumentException("expectedPeerId required");
        }
    }
}
