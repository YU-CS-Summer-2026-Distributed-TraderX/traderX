package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.replication.sbe.HeartbeatMessageDecoder;
import finos.traderx.ordermatcher.replication.sbe.HeartbeatMessageEncoder;
import finos.traderx.ordermatcher.replication.sbe.MessageHeaderDecoder;
import finos.traderx.ordermatcher.replication.sbe.MessageHeaderEncoder;
import finos.traderx.ordermatcher.replication.sbe.PeerHelloMessageDecoder;
import finos.traderx.ordermatcher.replication.sbe.PeerHelloMessageEncoder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/** Reusable SBE flyweights for authenticated session setup and direct peer heartbeats. */
public final class AeronControlCodec {
    public static final int HELLO_BYTES = MessageHeaderEncoder.ENCODED_LENGTH
        + PeerHelloMessageEncoder.BLOCK_LENGTH;
    public static final int HELLO_SIGNED_BYTES = MessageHeaderEncoder.ENCODED_LENGTH
        + PeerHelloMessageEncoder.authTagEncodingOffset();
    public static final int HEARTBEAT_BYTES = MessageHeaderEncoder.ENCODED_LENGTH
        + HeartbeatMessageEncoder.BLOCK_LENGTH;
    public static final int HEARTBEAT_SIGNED_BYTES = MessageHeaderEncoder.ENCODED_LENGTH
        + HeartbeatMessageEncoder.authTagEncodingOffset();

    public static final int OK = 0;
    public static final int WRONG_LENGTH = 1;
    public static final int WRONG_SCHEMA = 2;
    public static final int WRONG_TEMPLATE = 3;
    public static final int WRONG_VERSION = 4;
    public static final int WRONG_BLOCK_LENGTH = 5;

    private static final byte[] ZERO_HASH = new byte[32];

    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final PeerHelloMessageEncoder helloEncoder = new PeerHelloMessageEncoder();
    private final PeerHelloMessageDecoder helloDecoder = new PeerHelloMessageDecoder();
    private final HeartbeatMessageEncoder heartbeatEncoder = new HeartbeatMessageEncoder();
    private final HeartbeatMessageDecoder heartbeatDecoder = new HeartbeatMessageDecoder();
    private int templateId;

    public void encodeHello(MutableDirectBuffer buffer, int offset, long epoch, int role,
                            int ordinal, long nonce, long issuedAtMillis,
                            byte[] clusterHash, byte[] peerHash, byte[] schemaHash) {
        helloEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .leaderEpoch(epoch)
            .role((short) role)
            .ordinal((short) ordinal)
            .flags(0)
            .nonce(nonce)
            .issuedAtMillis(issuedAtMillis)
            .putClusterIdHash(clusterHash, 0, clusterHash.length)
            .putPeerIdHash(peerHash, 0, peerHash.length)
            .putSchemaChecksumHash(schemaHash, 0, schemaHash.length)
            .putAuthTag(ZERO_HASH, 0, ZERO_HASH.length);
    }

    public void putHelloAuthTag(byte[] authTag) {
        helloEncoder.putAuthTag(authTag, 0, authTag.length);
    }

    public int tryDecodeHello(DirectBuffer buffer, int offset, int length) {
        int status = validateHeader(buffer, offset, length, HELLO_BYTES,
            PeerHelloMessageDecoder.TEMPLATE_ID, PeerHelloMessageDecoder.BLOCK_LENGTH,
            PeerHelloMessageDecoder.SCHEMA_VERSION);
        if (status != OK) return status;
        helloDecoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
            headerDecoder.blockLength(), headerDecoder.version());
        return OK;
    }

    public void encodeHeartbeat(MutableDirectBuffer buffer, int offset, long epoch, int role,
                                long senderNanos, long highestInputSeq,
                                long journaledSeq, long appliedSeq, long recordingPosition) {
        heartbeatEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .leaderEpoch(epoch)
            .role((short) role)
            .flags((short) 0)
            .reserved(0)
            .senderNanos(senderNanos)
            .highestInputSeq(highestInputSeq)
            .journaledSeq(journaledSeq)
            .appliedSeq(appliedSeq)
            .recordingPosition(recordingPosition)
            .putAuthTag(ZERO_HASH, 0, ZERO_HASH.length);
    }

    public void putHeartbeatAuthTag(byte[] authTag) {
        heartbeatEncoder.putAuthTag(authTag, 0, authTag.length);
    }

    public int tryDecodeHeartbeat(DirectBuffer buffer, int offset, int length) {
        int status = validateHeader(buffer, offset, length, HEARTBEAT_BYTES,
            HeartbeatMessageDecoder.TEMPLATE_ID, HeartbeatMessageDecoder.BLOCK_LENGTH,
            HeartbeatMessageDecoder.SCHEMA_VERSION);
        if (status != OK) return status;
        heartbeatDecoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
            headerDecoder.blockLength(), headerDecoder.version());
        return OK;
    }

    public int tryInspectTemplate(DirectBuffer buffer, int offset, int length) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return WRONG_LENGTH;
        headerDecoder.wrap(buffer, offset);
        if (headerDecoder.schemaId() != PeerHelloMessageDecoder.SCHEMA_ID) return WRONG_SCHEMA;
        if (headerDecoder.version() != PeerHelloMessageDecoder.SCHEMA_VERSION) return WRONG_VERSION;
        templateId = headerDecoder.templateId();
        return OK;
    }

    private int validateHeader(DirectBuffer buffer, int offset, int length, int expectedLength,
                               int expectedTemplate, int expectedBlockLength, int expectedVersion) {
        if (length != expectedLength) return WRONG_LENGTH;
        headerDecoder.wrap(buffer, offset);
        if (headerDecoder.schemaId() != PeerHelloMessageDecoder.SCHEMA_ID) return WRONG_SCHEMA;
        if (headerDecoder.templateId() != expectedTemplate) return WRONG_TEMPLATE;
        if (headerDecoder.version() != expectedVersion) return WRONG_VERSION;
        if (headerDecoder.blockLength() != expectedBlockLength) return WRONG_BLOCK_LENGTH;
        templateId = headerDecoder.templateId();
        return OK;
    }

    public int templateId() { return templateId; }
    public long helloEpoch() { return helloDecoder.leaderEpoch(); }
    public int helloRole() { return helloDecoder.role(); }
    public int helloOrdinal() { return helloDecoder.ordinal(); }
    public long helloNonce() { return helloDecoder.nonce(); }
    public long helloIssuedAtMillis() { return helloDecoder.issuedAtMillis(); }
    public int getHelloClusterHash(byte[] target) {
        return helloDecoder.getClusterIdHash(target, 0, target.length);
    }
    public int getHelloPeerHash(byte[] target) {
        return helloDecoder.getPeerIdHash(target, 0, target.length);
    }
    public int getHelloSchemaHash(byte[] target) {
        return helloDecoder.getSchemaChecksumHash(target, 0, target.length);
    }
    public int getHelloAuthTag(byte[] target) {
        return helloDecoder.getAuthTag(target, 0, target.length);
    }

    public long heartbeatEpoch() { return heartbeatDecoder.leaderEpoch(); }
    public int heartbeatRole() { return heartbeatDecoder.role(); }
    public long heartbeatSenderNanos() { return heartbeatDecoder.senderNanos(); }
    public long heartbeatHighestInputSeq() { return heartbeatDecoder.highestInputSeq(); }
    public long heartbeatJournaledSeq() { return heartbeatDecoder.journaledSeq(); }
    public long heartbeatAppliedSeq() { return heartbeatDecoder.appliedSeq(); }
    public long heartbeatRecordingPosition() { return heartbeatDecoder.recordingPosition(); }
    public int getHeartbeatAuthTag(byte[] target) {
        return heartbeatDecoder.getAuthTag(target, 0, target.length);
    }
}
