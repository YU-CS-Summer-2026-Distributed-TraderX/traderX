package finos.traderx.ordermatcher.lmax;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Forced two-slot monotonic epoch store used before opening a primary replication session. */
public final class LeaderEpochStore {
    private static final int MAGIC = 0x45504f43;
    private static final int VERSION = 1;
    private static final int SLOT_BYTES = 32;

    private LeaderEpochStore() { }

    public static long claimNext(Path path, long floor) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                Slot first = read(channel, 0L);
                Slot second = read(channel, SLOT_BYTES);
                Slot latest = first == null ? second : second == null || first.generation >= second.generation
                    ? first : second;
                long generation = latest == null ? 0L : latest.generation;
                long previousEpoch = latest == null ? 0L : latest.epoch;
                long epoch = Math.max(floor + 1L, previousEpoch + 1L);
                if (epoch > 0xffff_ffffL) throw new IllegalStateException("leader epoch exhausted uint32");
                write(channel, generation + 1L, epoch);
                return epoch;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("cannot persist next leader epoch", ex);
        }
    }

    private static void write(FileChannel channel, long generation, long epoch) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(SLOT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        long hash = hash(generation, epoch);
        buffer.putInt(MAGIC).putInt(VERSION).putLong(generation).putLong(epoch).putLong(hash).flip();
        long offset = (generation & 1L) * SLOT_BYTES;
        while (buffer.hasRemaining()) channel.write(buffer, offset + buffer.position());
        channel.force(false);
    }

    private static Slot read(FileChannel channel, long offset) throws IOException {
        if (channel.size() < offset + SLOT_BYTES) return null;
        ByteBuffer buffer = ByteBuffer.allocate(SLOT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset + buffer.position());
            if (read < 0) return null;
        }
        buffer.flip();
        if (buffer.getInt() != MAGIC || buffer.getInt() != VERSION) return null;
        long generation = buffer.getLong();
        long epoch = buffer.getLong();
        return buffer.getLong() == hash(generation, epoch) ? new Slot(generation, epoch) : null;
    }

    private static long hash(long generation, long epoch) {
        long value = 0xcbf29ce484222325L;
        value ^= generation;
        value *= 0x100000001b3L;
        value ^= epoch;
        return value * 0x100000001b3L;
    }

    private record Slot(long generation, long epoch) { }
}
