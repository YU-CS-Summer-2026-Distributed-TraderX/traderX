package finos.traderx.ordermatcher.risk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

/** Versioned, checksummed, atomic cold-path persistence for authoritative BLP risk state. */
public final class BlpRiskSnapshotCodec {
    static final int MAGIC = 0x494d5247; // IMRG
    static final int SCHEMA_VERSION = 1;

    private BlpRiskSnapshotCodec() {}

    public static void write(Path path, BlpRiskState state) throws IOException {
        byte[] payload = encode(state.captureImage());
        CRC32 crc = new CRC32();
        crc.update(payload);
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            output.writeInt(payload.length);
            output.writeLong(crc.getValue());
            output.write(payload);
        }
        try {
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void restore(Path path, BlpRiskState state) throws IOException {
        byte[] file = Files.readAllBytes(path);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(file))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid risk snapshot magic");
            int schemaVersion = input.readInt();
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IOException("unsupported risk snapshot schema " + schemaVersion);
            }
            int payloadLength = input.readInt();
            long checksum = input.readLong();
            if (payloadLength < 0 || payloadLength != input.available()) {
                throw new IOException("invalid risk snapshot length");
            }
            byte[] payload = input.readNBytes(payloadLength);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != checksum) throw new IOException("risk snapshot checksum mismatch");
            state.restoreImage(decode(payload));
        } catch (IllegalArgumentException ex) {
            throw new IOException("incompatible risk snapshot", ex);
        }
    }

    static byte[] encode(BlpRiskState.Image image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            write(output, image.accountIds());
            write(output, image.accountEnabled());
            write(output, image.reservedNotional());
            write(output, image.reservedBuyNotional());
            write(output, image.reservedSellNotional());
            write(output, image.executedNotional());
            write(output, image.securityEnabled());
            write(output, image.securityRestricted());
            write(output, image.lastPrice());
            write(output, image.lastPriceTime());
            write(output, image.idempotencyKeys());
            write(output, image.idempotencyOrderRefs());
            write(output, image.idempotencyDecisions());
            write(output, image.idempotencyRetentionKeys());
            write(output, image.entitlementKeys());
            write(output, image.entitlementEnabled());
            write(output, image.reservationNotionalByOrderRef());
            write(output, image.reservationQtyByOrderRef());
            write(output, image.reservationAccountByOrderRef());
            write(output, image.reservationSecurityByOrderRef());
            write(output, image.reservationSideByOrderRef());
            write(output, image.exposureKeys());
            write(output, image.reservedBuyQtyByExposure());
            write(output, image.reservedSellQtyByExposure());
            output.writeLong(image.policyVersion());
            output.writeBoolean(image.killSwitch());
            output.writeInt(image.idempotencyRetentionCursor());
            output.writeInt(image.idempotencyRetentionSize());
            output.writeLong(image.idempotencyInsertions());
            output.writeInt(image.maxPositionQuantity());
            output.writeLong(image.maxConcentrationNotionalTicks());
        }
        return bytes.toByteArray();
    }

    static BlpRiskState.Image decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            BlpRiskState.Image image = new BlpRiskState.Image(
                readInts(input), readBytes(input), readLongs(input), readLongs(input), readLongs(input),
                readLongs(input), readBytes(input), readBytes(input), readLongs(input), readLongs(input),
                readLongs(input), readInts(input), readBytes(input), readLongs(input), readLongs(input),
                readBytes(input), readLongs(input), readInts(input), readInts(input), readInts(input),
                readBytes(input), readLongs(input), readInts(input), readInts(input), input.readLong(),
                input.readBoolean(), input.readInt(), input.readInt(), input.readLong(), input.readInt(),
                input.readLong());
            if (input.available() != 0) throw new IOException("trailing risk snapshot data");
            return image;
        }
    }

    private static void write(DataOutputStream output, byte[] values) throws IOException {
        output.writeInt(values.length);
        output.write(values);
    }

    private static void write(DataOutputStream output, int[] values) throws IOException {
        output.writeInt(values.length);
        for (int value : values) output.writeInt(value);
    }

    private static void write(DataOutputStream output, long[] values) throws IOException {
        output.writeInt(values.length);
        for (long value : values) output.writeLong(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = checkedLength(input.readInt(), 1, input.available());
        return input.readNBytes(length);
    }

    private static int[] readInts(DataInputStream input) throws IOException {
        int length = checkedLength(input.readInt(), Integer.BYTES, input.available());
        int[] values = new int[length];
        for (int i = 0; i < length; i++) values[i] = input.readInt();
        return values;
    }

    private static long[] readLongs(DataInputStream input) throws IOException {
        int length = checkedLength(input.readInt(), Long.BYTES, input.available());
        long[] values = new long[length];
        for (int i = 0; i < length; i++) values[i] = input.readLong();
        return values;
    }

    private static int checkedLength(int length, int elementSize, int remaining) throws IOException {
        if (length < 0 || length > remaining / elementSize) throw new IOException("invalid snapshot array length");
        return length;
    }
}
