package finos.traderx.ordermatcher.fix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable FIX client-order correlation ledger (ADR-035, FR-FIX09/FR-FIX10).
 *
 * <p>Append-only fixed-length records binding (sessionKey, ClOrdID) to the input-ring sequence
 * and internal order reference. A record is WRITTEN (buffered to the OS) before the order is
 * published to the input ring — the invariant is "no order on the ring without a ledger entry";
 * fsync is amortized ({@link #FORCE_EVERY} appends), the same durability discipline the journal
 * uses per drained batch. Rehydration replays the whole file at startup, truncating a torn tail.
 *
 * <p>Threading: appends and by-ClOrdID lookups happen on QuickFIX/J session threads
 * (synchronized); {@link #byInputSeq(long)} is read by the ExecutionReport handler on the
 * output-ring thread via a ConcurrentHashMap (lock-free read; the output-ring thread sits
 * outside the exact-zero allocation boundary, so the boxed key lookup is acceptable there —
 * NFR-FIX01).
 */
public final class ClOrdIdLedger implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ClOrdIdLedger.class);

    static final int RECORD_SIZE = 88;
    static final int MAX_CLORDID_BYTES = 64;
    private static final int FORCE_EVERY = 64;

    public enum AppendResult { OK, DUPLICATE, UNAVAILABLE }

    /** One correlated order. */
    public record Entry(long sessionKey, String clOrdId, long inputSeq, int orderRef) { }

    private final Path file;
    private final Map<Long, Map<String, Entry>> bySession = new HashMap<>();
    private final ConcurrentHashMap<Long, Entry> byInputSeq = new ConcurrentHashMap<>();
    private final ByteBuffer writeBuf = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);

    private FileChannel channel;
    private volatile boolean available;
    private int unforcedAppends;

    public ClOrdIdLedger(Path dir) {
        this.file = dir.resolve("clordid-ledger.dat");
        try {
            Files.createDirectories(dir);
            this.channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            long good = rehydrate();
            if (good < channel.size()) {
                log.warn("Truncating torn ledger tail: {} -> {} bytes", channel.size(), good);
                channel.truncate(good);
            }
            channel.position(channel.size());
            this.available = true;
            log.info("ClOrdID ledger ready: {} entries from {}", byInputSeq.size(), file);
        } catch (IOException ex) {
            log.error("ClOrdID ledger unavailable at {} — FIX order admission will fail closed", file, ex);
            this.available = false;
        }
    }

    /** FNV-1a of the session identity — stable across restarts, no allocation per call. */
    public static long sessionKey(String senderCompId, String targetCompId) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < senderCompId.length(); i++) { h ^= senderCompId.charAt(i); h *= 0x100000001b3L; }
        h ^= ':'; h *= 0x100000001b3L;
        for (int i = 0; i < targetCompId.length(); i++) { h ^= targetCompId.charAt(i); h *= 0x100000001b3L; }
        return h;
    }

    /** Record an order BEFORE ring publish. DUPLICATE and UNAVAILABLE mean: do not publish. */
    public synchronized AppendResult append(long sessionKey, String clOrdId, long inputSeq, int orderRef) {
        if (!available) {
            return AppendResult.UNAVAILABLE;
        }
        byte[] id = clOrdId.getBytes(StandardCharsets.UTF_8);
        if (id.length == 0 || id.length > MAX_CLORDID_BYTES) {
            throw new IllegalArgumentException("ClOrdID must be 1.." + MAX_CLORDID_BYTES + " UTF-8 bytes");
        }
        Map<String, Entry> session = bySession.computeIfAbsent(sessionKey, k -> new HashMap<>());
        if (session.containsKey(clOrdId)) {
            return AppendResult.DUPLICATE;
        }
        writeBuf.clear();
        writeBuf.putLong(sessionKey).putLong(inputSeq).putInt(orderRef).putShort((short) id.length);
        writeBuf.put(id);
        while (writeBuf.position() < RECORD_SIZE) {
            writeBuf.put((byte) 0);
        }
        writeBuf.flip();
        try {
            while (writeBuf.hasRemaining()) {
                channel.write(writeBuf);
            }
            if (++unforcedAppends >= FORCE_EVERY) {
                channel.force(false);
                unforcedAppends = 0;
            }
        } catch (IOException ex) {
            log.error("ClOrdID ledger append failed — failing closed", ex);
            available = false;
            return AppendResult.UNAVAILABLE;
        }
        Entry e = new Entry(sessionKey, clOrdId, inputSeq, orderRef);
        session.put(clOrdId, e);
        byInputSeq.put(inputSeq, e);
        return AppendResult.OK;
    }

    /** Session-thread lookup for cancels/status (OrigClOrdID resolution). */
    public synchronized Entry byClOrdId(long sessionKey, String clOrdId) {
        Map<String, Entry> session = bySession.get(sessionKey);
        return session == null ? null : session.get(clOrdId);
    }

    /** Output-ring-thread lookup joining OutputEvent.inputSeq to the owning session. */
    public Entry byInputSeq(long inputSeq) {
        return byInputSeq.get(inputSeq);
    }

    public boolean available() {
        return available;
    }

    public synchronized int size() {
        return byInputSeq.size();
    }

    /** Flush any unforced appends (shutdown hook / tests). */
    public synchronized void force() throws IOException {
        if (available && unforcedAppends > 0) {
            channel.force(false);
            unforcedAppends = 0;
        }
    }

    private long rehydrate() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        long offset = 0;
        long size = channel.size();
        while (offset + RECORD_SIZE <= size) {
            buf.clear();
            int read = channel.read(buf, offset);
            if (read < RECORD_SIZE) {
                break;
            }
            buf.flip();
            long sessionKey = buf.getLong();
            long inputSeq = buf.getLong();
            int orderRef = buf.getInt();
            short len = buf.getShort();
            if (len <= 0 || len > MAX_CLORDID_BYTES) {
                break;  // torn/corrupt record: stop, caller truncates here
            }
            byte[] id = new byte[len];
            buf.get(id);
            Entry e = new Entry(sessionKey, new String(id, StandardCharsets.UTF_8), inputSeq, orderRef);
            bySession.computeIfAbsent(sessionKey, k -> new HashMap<>()).put(e.clOrdId(), e);
            byInputSeq.put(inputSeq, e);
            offset += RECORD_SIZE;
        }
        return offset;
    }

    @Override
    public synchronized void close() {
        available = false;
        if (channel != null) {
            try {
                channel.force(false);
                channel.close();
            } catch (IOException ex) {
                log.warn("ClOrdID ledger close: {}", ex.toString());
            }
        }
    }
}
