package finos.traderx.ordermatcher.lmax;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Two-slot forced follower checkpoint. A torn slot is ignored via its record hash; the previous
 * generation remains valid, so restart never invents an Archive/logical position.
 */
public final class AeronFollowerCheckpointStore implements AutoCloseable {
    private static final int MAGIC = 0x59553131;
    private static final int VERSION = 2;
    private static final int SLOT_BYTES = 64;

    private final Path path;
    private final FileChannel channel;
    private final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(SLOT_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN);
    private long generation;

    public AeronFollowerCheckpointStore(Path path) throws IOException {
        this.path = path;
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        channel = FileChannel.open(path, StandardOpenOption.CREATE,
            StandardOpenOption.READ, StandardOpenOption.WRITE);
        Record current = read();
        generation = current == null ? 0L : current.generation();
    }

    public void write(long epoch, long inputSeq, long recordingPosition,
                      long payloadChecksum, int dataSessionId) throws IOException {
        long nextGeneration = generation + 1L;
        long hash = recordHash(nextGeneration, epoch, inputSeq,
            recordingPosition, payloadChecksum, dataSessionId);
        writeBuffer.clear();
        writeBuffer.putInt(MAGIC).putInt(VERSION).putLong(nextGeneration).putLong(epoch)
            .putLong(inputSeq).putLong(recordingPosition).putLong(payloadChecksum)
            .putInt(dataSessionId).putInt(0).putLong(hash).flip();
        long offset = (nextGeneration & 1L) * SLOT_BYTES;
        while (writeBuffer.hasRemaining()) channel.write(writeBuffer, offset + writeBuffer.position());
        channel.force(false);
        generation = nextGeneration;
    }

    public Record read() throws IOException {
        long size = channel.size();
        if (size == 0L) return null;
        Record first = readSlot(0L);
        Record second = readSlot(SLOT_BYTES);
        if (first == null && second == null) {
            throw new IOException("no valid Aeron follower checkpoint slot in " + path);
        }
        if (first == null) return second;
        if (second == null) return first;
        return first.generation() >= second.generation() ? first : second;
    }

    private Record readSlot(long offset) throws IOException {
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
        long inputSeq = buffer.getLong();
        long recordingPosition = buffer.getLong();
        long payloadChecksum = buffer.getLong();
        int dataSessionId = buffer.getInt();
        buffer.getInt();
        long expectedHash = buffer.getLong();
        long actualHash = recordHash(generation, epoch, inputSeq,
            recordingPosition, payloadChecksum, dataSessionId);
        return expectedHash == actualHash
            ? new Record(generation, epoch, inputSeq, recordingPosition,
                payloadChecksum, dataSessionId) : null;
    }

    private static long recordHash(long generation, long epoch, long inputSeq,
                                   long recordingPosition, long payloadChecksum,
                                   int dataSessionId) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, generation);
        hash = mix(hash, epoch);
        hash = mix(hash, inputSeq);
        hash = mix(hash, recordingPosition);
        hash = mix(hash, payloadChecksum);
        return mix(hash, dataSessionId);
    }

    private static long mix(long hash, long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash ^= (value >>> shift) & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    @Override public void close() throws IOException { channel.close(); }

    public record Record(long generation, long epoch, long inputSeq,
                         long recordingPosition, long payloadChecksum,
                         int dataSessionId) { }
}
