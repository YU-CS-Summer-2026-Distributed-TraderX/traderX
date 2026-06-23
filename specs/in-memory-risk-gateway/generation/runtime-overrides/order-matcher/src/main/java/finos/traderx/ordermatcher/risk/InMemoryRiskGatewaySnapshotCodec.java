package finos.traderx.ordermatcher.risk;

import finos.traderx.ordermatcher.lmax.MatchingEngine;
import finos.traderx.ordermatcher.lmax.PositionBook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

/** Atomic full-recovery snapshot for authoritative risk plus matcher state. */
public final class InMemoryRiskGatewaySnapshotCodec {
    private static final int MAGIC = 0x494d4753; // IMGS
    private static final int SCHEMA_VERSION = 1;

    public record Snapshot(long lastAppliedSequence, MatchingEngine.Image matchingImage) {}

    private InMemoryRiskGatewaySnapshotCodec() {}

    public static void write(Path path, long lastAppliedSequence, BlpRiskState riskState,
                             MatchingEngine matchingEngine) throws IOException {
        byte[] riskPayload = BlpRiskSnapshotCodec.encode(riskState.captureImage());
        byte[] matchingPayload = encodeMatching(matchingEngine.captureImage());
        CRC32 riskChecksum = new CRC32();
        CRC32 matchingChecksum = new CRC32();
        riskChecksum.update(riskPayload);
        matchingChecksum.update(matchingPayload);
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            output.writeLong(lastAppliedSequence);
            output.writeInt(riskPayload.length);
            output.writeLong(riskChecksum.getValue());
            output.write(riskPayload);
            output.writeInt(matchingPayload.length);
            output.writeLong(matchingChecksum.getValue());
            output.write(matchingPayload);
        }
        try {
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Snapshot restore(Path path, BlpRiskState riskState) throws IOException {
        byte[] file = Files.readAllBytes(path);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(file))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid recovery snapshot magic");
            int schemaVersion = input.readInt();
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IOException("unsupported recovery snapshot schema " + schemaVersion);
            }
            long lastAppliedSequence = input.readLong();
            byte[] riskPayload = readPayload(input, "risk");
            riskState.restoreImage(BlpRiskSnapshotCodec.decode(riskPayload));
            byte[] matchingPayload = readPayload(input, "matching");
            MatchingEngine.Image matchingImage = decodeMatching(matchingPayload);
            if (input.available() != 0) throw new IOException("trailing recovery snapshot data");
            return new Snapshot(lastAppliedSequence, matchingImage);
        } catch (IllegalArgumentException ex) {
            throw new IOException("incompatible recovery snapshot", ex);
        }
    }

    private static byte[] readPayload(DataInputStream input, String label) throws IOException {
        int length = input.readInt();
        long checksum = input.readLong();
        if (length < 0 || length > input.available()) {
            throw new IOException("invalid " + label + " snapshot length");
        }
        byte[] payload = input.readNBytes(length);
        CRC32 crc = new CRC32();
        crc.update(payload);
        if (crc.getValue() != checksum) {
            throw new IOException(label + " snapshot checksum mismatch");
        }
        return payload;
    }

    private static byte[] encodeMatching(MatchingEngine.Image image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            write(output, image.orderRefs());
            write(output, image.accountIds());
            write(output, image.securityIds());
            write(output, image.sides());
            write(output, image.quantities());
            write(output, image.remainingQuantities());
            write(output, image.limitPrices());
            write(output, image.statuses());
            write(output, image.riskReasons());
            write(output, image.lastExecPrices());
            write(output, image.lastFillQuantities());
            write(output, image.createdAtMillis());
            write(output, image.updatedAtMillis());
            write(output, image.expiresAtMillis());
            write(output, image.lastPricesBySecurity());
            write(output, image.positions().keys());
            write(output, image.positions().quantities());
            write(output, image.positions().avgCostTicks());
            write(output, image.expiryOrderRefs());
            write(output, image.expiryTimes());
            output.writeLong(image.tradeCounter());
            output.writeLong(image.eventsProcessed());
            output.writeLong(image.autoFillAttempts());
            output.writeLong(image.autoFillSuccess());
            output.writeLong(image.lastEventTimeMillis());
            output.writeLong(image.ordersNew());
            output.writeLong(image.ordersCancel());
            output.writeLong(image.ordersForceFill());
            output.writeLong(image.priceTicks());
            output.writeLong(image.tradesNew());
        }
        return bytes.toByteArray();
    }

    private static MatchingEngine.Image decodeMatching(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            MatchingEngine.Image image = new MatchingEngine.Image(
                readInts(input), readInts(input), readInts(input), readBytes(input), readInts(input),
                readInts(input), readLongs(input), readBytes(input), readBytes(input), readLongs(input),
                readInts(input), readLongs(input), readLongs(input), readLongs(input), readLongs(input),
                new PositionBook.Image(readLongs(input), readInts(input), readLongs(input)),
                readInts(input), readLongs(input), input.readLong(), input.readLong(), input.readLong(),
                input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong(),
                input.readLong(), input.readLong());
            if (input.available() != 0) throw new IOException("trailing matching snapshot data");
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
        if (length < 0 || length > remaining / elementSize) {
            throw new IOException("invalid snapshot array length");
        }
        return length;
    }
}
