package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.replication.sbe.DurableAckMessageDecoder;
import finos.traderx.ordermatcher.replication.sbe.DurableAckMessageEncoder;
import finos.traderx.ordermatcher.replication.sbe.InputEventMessageDecoder;
import finos.traderx.ordermatcher.replication.sbe.InputEventMessageEncoder;
import finos.traderx.ordermatcher.replication.sbe.MessageHeaderDecoder;
import finos.traderx.ordermatcher.replication.sbe.MessageHeaderEncoder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Reusable generated-SBE flyweights for the YU11 replication wire. One instance belongs to one
 * transport agent; callers do not share it across threads. Validation returns a status code rather
 * than throwing on malformed peer data, keeping the follower poll path allocation-free.
 */
public final class AeronReplicationCodec {
    public static final int INPUT_BYTES = MessageHeaderEncoder.ENCODED_LENGTH
        + InputEventMessageEncoder.BLOCK_LENGTH;
    public static final int ACK_BYTES = MessageHeaderEncoder.ENCODED_LENGTH
        + DurableAckMessageEncoder.BLOCK_LENGTH;

    public static final int INPUT_FLAG_SHADOW = 1;
    public static final int KNOWN_INPUT_FLAGS = INPUT_FLAG_SHADOW;

    public static final long ACK_ON_RING = 1L;
    public static final long ACK_JOURNALED = 1L << 1;
    public static final long ACK_APPLIED = 1L << 2;
    public static final long ACK_REPLAYING = 1L << 3;
    public static final long ACK_DEGRADED = 1L << 4;
    public static final long KNOWN_ACK_FLAGS = ACK_ON_RING | ACK_JOURNALED | ACK_APPLIED
        | ACK_REPLAYING | ACK_DEGRADED;

    public static final int OK = 0;
    public static final int WRONG_LENGTH = 1;
    public static final int WRONG_SCHEMA = 2;
    public static final int WRONG_TEMPLATE = 3;
    public static final int WRONG_VERSION = 4;
    public static final int WRONG_BLOCK_LENGTH = 5;
    public static final int UNKNOWN_FLAGS = 6;

    /** SHA-256 of src/main/resources/sbe/blp-replication.xml. */
    public static final String SCHEMA_CHECKSUM =
        "45a46b6dac82b4620569a8c02507f558d887ff96ab919d4eb7c5aac09f60074e";

    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final InputEventMessageEncoder inputEncoder = new InputEventMessageEncoder();
    private final InputEventMessageDecoder inputDecoder = new InputEventMessageDecoder();
    private final DurableAckMessageEncoder ackEncoder = new DurableAckMessageEncoder();
    private final DurableAckMessageDecoder ackDecoder = new DurableAckMessageDecoder();

    private long inputSeq;
    private long leaderEpoch;
    private int inputFlags;
    private long ackFlags;
    private long recordingPosition;
    private long journalForceNanos;

    public void encodeInput(MutableDirectBuffer buffer, int offset, InputEvent event,
                            long primaryInputSeq, long epoch, int flags) {
        inputEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .inputSeq(primaryInputSeq)
            .eventTimeMillis(event.eventTimeMillis)
            .limitPx(event.limitPx)
            .priceTicks(event.priceTicks)
            .orderRef(Integer.toUnsignedLong(event.orderRef))
            .accountId(Integer.toUnsignedLong(event.accountId))
            .securityId(Integer.toUnsignedLong(event.securityId))
            .qty(event.qty)
            .leaderEpoch(epoch)
            .commandType((short) Byte.toUnsignedInt(event.type))
            .side((short) Byte.toUnsignedInt(event.side))
            .flags(flags);
    }

    /** Validate and bind the decoder without mutating a ring slot. */
    public int tryInspectInput(DirectBuffer buffer, int offset, int length) {
        int status = validateHeader(buffer, offset, length, INPUT_BYTES,
            InputEventMessageDecoder.TEMPLATE_ID, InputEventMessageDecoder.BLOCK_LENGTH,
            InputEventMessageDecoder.SCHEMA_VERSION);
        if (status != OK) return status;

        inputDecoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
            headerDecoder.blockLength(), headerDecoder.version());
        int flags = inputDecoder.flags();
        if ((flags & ~KNOWN_INPUT_FLAGS) != 0) return UNKNOWN_FLAGS;

        inputSeq = inputDecoder.inputSeq();
        leaderEpoch = inputDecoder.leaderEpoch();
        inputFlags = flags;
        return OK;
    }

    /** Copy the already inspected flyweight directly into a pre-claimed reusable ring slot. */
    public void decodeInspectedInput(InputEvent target) {
        target.seq = inputSeq;
        target.eventTimeMillis = inputDecoder.eventTimeMillis();
        target.limitPx = inputDecoder.limitPx();
        target.priceTicks = inputDecoder.priceTicks();
        target.orderRef = (int) inputDecoder.orderRef();
        target.accountId = (int) inputDecoder.accountId();
        target.securityId = (int) inputDecoder.securityId();
        target.qty = inputDecoder.qty();
        target.type = (byte) inputDecoder.commandType();
        target.side = (byte) inputDecoder.side();
        target.ingressNanos = 0L;
    }

    /** Convenience for tests and non-ring callers. */
    public int tryDecodeInput(DirectBuffer buffer, int offset, int length, InputEvent target) {
        int status = tryInspectInput(buffer, offset, length);
        if (status == OK) decodeInspectedInput(target);
        return status;
    }

    public void encodeAck(MutableDirectBuffer buffer, int offset, long epoch, long flags,
                          long highestInputSeq, long archivePosition, long forceNanos) {
        ackEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .leaderEpoch(epoch)
            .flags(flags)
            .inputSeq(highestInputSeq)
            .recordingPosition(archivePosition)
            .journalForceNanos(forceNanos);
    }

    public int tryDecodeAck(DirectBuffer buffer, int offset, int length) {
        int status = validateHeader(buffer, offset, length, ACK_BYTES,
            DurableAckMessageDecoder.TEMPLATE_ID, DurableAckMessageDecoder.BLOCK_LENGTH,
            DurableAckMessageDecoder.SCHEMA_VERSION);
        if (status != OK) return status;

        ackDecoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
            headerDecoder.blockLength(), headerDecoder.version());
        long flags = ackDecoder.flags();
        if ((flags & ~KNOWN_ACK_FLAGS) != 0) return UNKNOWN_FLAGS;

        leaderEpoch = ackDecoder.leaderEpoch();
        ackFlags = flags;
        inputSeq = ackDecoder.inputSeq();
        recordingPosition = ackDecoder.recordingPosition();
        journalForceNanos = ackDecoder.journalForceNanos();
        return OK;
    }

    private int validateHeader(DirectBuffer buffer, int offset, int length, int expectedLength,
                               int templateId, int blockLength, int version) {
        if (length != expectedLength) return WRONG_LENGTH;
        headerDecoder.wrap(buffer, offset);
        if (headerDecoder.schemaId() != InputEventMessageDecoder.SCHEMA_ID) return WRONG_SCHEMA;
        if (headerDecoder.templateId() != templateId) return WRONG_TEMPLATE;
        if (headerDecoder.version() != version) return WRONG_VERSION;
        if (headerDecoder.blockLength() != blockLength) return WRONG_BLOCK_LENGTH;
        return OK;
    }

    public long inputSeq() { return inputSeq; }
    public long leaderEpoch() { return leaderEpoch; }
    public int inputFlags() { return inputFlags; }
    public long ackFlags() { return ackFlags; }
    public long recordingPosition() { return recordingPosition; }
    public long journalForceNanos() { return journalForceNanos; }

    /** Canonical payload checksum for the currently inspected input, excluding transport metadata. */
    public long inspectedPayloadChecksum() {
        long hash = 0xcbf29ce484222325L;
        hash = mixLong(hash, inputDecoder.eventTimeMillis());
        hash = mixLong(hash, inputDecoder.limitPx());
        hash = mixLong(hash, inputDecoder.priceTicks());
        hash = mixInt(hash, (int) inputDecoder.orderRef());
        hash = mixInt(hash, (int) inputDecoder.accountId());
        hash = mixInt(hash, (int) inputDecoder.securityId());
        hash = mixInt(hash, inputDecoder.qty());
        hash = mixByte(hash, (byte) inputDecoder.commandType());
        return mixByte(hash, (byte) inputDecoder.side());
    }

    /** Same canonical payload checksum from the authoritative NATS-decoded ring event. */
    public static long payloadChecksum(InputEvent event) {
        long hash = 0xcbf29ce484222325L;
        hash = mixLong(hash, event.eventTimeMillis);
        hash = mixLong(hash, event.limitPx);
        hash = mixLong(hash, event.priceTicks);
        hash = mixInt(hash, event.orderRef);
        hash = mixInt(hash, event.accountId);
        hash = mixInt(hash, event.securityId);
        hash = mixInt(hash, event.qty);
        hash = mixByte(hash, event.type);
        return mixByte(hash, event.side);
    }

    private static long mixLong(long hash, long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash = mixByte(hash, (byte) (value >>> shift));
        }
        return hash;
    }

    private static long mixInt(long hash, int value) {
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            hash = mixByte(hash, (byte) (value >>> shift));
        }
        return hash;
    }

    private static long mixByte(long hash, byte value) {
        hash ^= value & 0xffL;
        return hash * 0x100000001b3L;
    }

    /** Stable duplicate-comparison checksum; no allocation and no hidden native state. */
    public static long checksum64(DirectBuffer buffer, int offset, int length) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < length; i++) {
            hash ^= buffer.getByte(offset + i) & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
