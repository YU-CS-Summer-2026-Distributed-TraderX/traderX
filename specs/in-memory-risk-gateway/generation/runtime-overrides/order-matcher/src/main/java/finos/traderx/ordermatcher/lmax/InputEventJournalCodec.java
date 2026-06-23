package finos.traderx.ordermatcher.lmax;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/** Locked binary journal schema with explicit legacy upcasters and per-record corruption detection. */
public final class InputEventJournalCodec {
    public static final int MAGIC = 0x494d524a; // IMRJ
    public static final int SCHEMA_ID = 1101;
    public static final int SCHEMA_VERSION = 2;
    public static final int HEADER_SIZE = 16;
    public static final int RECORD_SIZE = 96;
    private static final int CHECKSUM_OFFSET = 92;
    private static final int LEGACY_RISK_RECORD_SIZE = 88;
    private static final int LEGACY_009B_RECORD_SIZE = 64;

    @FunctionalInterface
    public interface Visitor {
        void onEvent(InputEvent event) throws Exception;
    }

    private InputEventJournalCodec() {}

    public static void writeHeader(FileChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC).putInt(SCHEMA_ID).putInt(SCHEMA_VERSION).putInt(RECORD_SIZE).flip();
        writeFully(channel, header);
        channel.force(true);
    }

    public static void validateHeader(FileChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(0L);
        readFully(channel, header);
        header.flip();
        int magic = header.getInt();
        int schemaId = header.getInt();
        int version = header.getInt();
        int recordSize = header.getInt();
        if (magic != MAGIC || schemaId != SCHEMA_ID || version != SCHEMA_VERSION
            || recordSize != RECORD_SIZE) {
            throw new IOException("incompatible journal schema: magic=" + magic + " schemaId="
                + schemaId + " version=" + version + " recordSize=" + recordSize);
        }
    }

    public static void encode(InputEvent event, ByteBuffer target, CRC32 crc) {
        target.clear().order(ByteOrder.LITTLE_ENDIAN);
        target.putLong(event.seq);
        target.put(event.type);
        target.put(event.side);
        target.putShort((short) 0);
        target.putInt(event.orderRef);
        target.putInt(event.accountId);
        target.putInt(event.securityId);
        target.putInt(event.qty);
        target.putLong(event.limitPx);
        target.putLong(event.priceTicks);
        target.putLong(event.ingressNanos);
        target.putLong(event.eventTimeMillis);
        target.putLong(event.clientOrderKey);
        target.putLong(event.principalKey);
        target.putLong(event.controlVersion);
        target.put(event.controlEnabled ? (byte) 1 : (byte) 0);
        target.putInt(0).putShort((short) 0).put((byte) 0);
        crc.reset();
        for (int i = 0; i < CHECKSUM_OFFSET; i++) crc.update(target.get(i));
        target.putInt((int) crc.getValue());
        target.flip();
    }

    public static long replay(Path journalFile, long afterSequence, Visitor visitor) throws Exception {
        if (!Files.exists(journalFile) || Files.size(journalFile) == 0L) return afterSequence;
        try (FileChannel channel = FileChannel.open(journalFile, StandardOpenOption.READ)) {
            ByteBuffer prefix = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, prefix);
            prefix.flip();
            if (prefix.getInt() == MAGIC) {
                channel.position(0L);
                validateHeader(channel);
                return replayVersion2(channel, afterSequence, visitor);
            }
            channel.position(0L);
            long size = channel.size();
            if (size % LEGACY_RISK_RECORD_SIZE == 0L) {
                return replayLegacy(channel, LEGACY_RISK_RECORD_SIZE, afterSequence, visitor);
            }
            if (size % LEGACY_009B_RECORD_SIZE == 0L) {
                return replayLegacy(channel, LEGACY_009B_RECORD_SIZE, afterSequence, visitor);
            }
            throw new IOException("unrecognized or truncated legacy journal length " + size);
        }
    }

    private static long replayVersion2(FileChannel channel, long afterSequence, Visitor visitor)
        throws Exception {
        channel.position(HEADER_SIZE);
        ByteBuffer record = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        InputEvent event = new InputEvent();
        CRC32 crc = new CRC32();
        long last = afterSequence;
        while (channel.position() < channel.size()) {
            record.clear();
            readFully(channel, record);
            record.flip();
            crc.reset();
            for (int i = 0; i < CHECKSUM_OFFSET; i++) crc.update(record.get(i));
            int expected = record.getInt(CHECKSUM_OFFSET);
            if ((int) crc.getValue() != expected) {
                throw new IOException("journal checksum mismatch at file offset "
                    + (channel.position() - RECORD_SIZE));
            }
            decodeVersion2(record, event);
            if (event.seq > afterSequence) visitor.onEvent(event);
            last = Math.max(last, event.seq);
        }
        return last;
    }

    private static long replayLegacy(FileChannel channel, int recordSize, long afterSequence,
                                     Visitor visitor) throws Exception {
        ByteBuffer record = ByteBuffer.allocate(recordSize).order(ByteOrder.LITTLE_ENDIAN);
        InputEvent event = new InputEvent();
        long last = afterSequence;
        while (channel.position() < channel.size()) {
            record.clear();
            readFully(channel, record);
            record.flip();
            decodeCommon(record, event);
            if (recordSize == LEGACY_RISK_RECORD_SIZE) {
                event.clientOrderKey = record.getLong();
                event.principalKey = record.getLong();
                event.controlVersion = record.getLong();
                event.controlEnabled = record.get() != 0;
            } else {
                event.clientOrderKey = 0L;
                event.principalKey = 0L;
                event.controlVersion = 0L;
                event.controlEnabled = false;
            }
            event.ingressNanos = 0L;
            if (event.seq > afterSequence) visitor.onEvent(event);
            last = Math.max(last, event.seq);
        }
        return last;
    }

    private static void decodeVersion2(ByteBuffer source, InputEvent event) {
        event.seq = source.getLong();
        event.type = source.get();
        event.side = source.get();
        source.getShort();
        event.orderRef = source.getInt();
        event.accountId = source.getInt();
        event.securityId = source.getInt();
        event.qty = source.getInt();
        event.limitPx = source.getLong();
        event.priceTicks = source.getLong();
        event.ingressNanos = source.getLong();
        event.eventTimeMillis = source.getLong();
        event.clientOrderKey = source.getLong();
        event.principalKey = source.getLong();
        event.controlVersion = source.getLong();
        event.controlEnabled = source.get() != 0;
    }

    private static void decodeCommon(ByteBuffer source, InputEvent event) {
        event.seq = source.getLong();
        event.type = source.get();
        event.side = source.get();
        source.getShort();
        event.orderRef = source.getInt();
        event.accountId = source.getInt();
        event.securityId = source.getInt();
        event.qty = source.getInt();
        event.limitPx = source.getLong();
        event.priceTicks = source.getLong();
        event.eventTimeMillis = source.getLong();
    }

    private static void readFully(FileChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) throw new EOFException("truncated journal record");
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer source) throws IOException {
        while (source.hasRemaining()) channel.write(source);
    }
}
