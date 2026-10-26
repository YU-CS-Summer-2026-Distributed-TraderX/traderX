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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable FIX client-order correlation ledger (ADR-035, FR-FIX09/FR-FIX10).
 *
 * <p>Append-only fixed-length records binding (sessionKey, ClOrdID) to the internal order
 * reference — the stable identity every lifecycle OutputEvent carries. A record is written at
 * admission, immediately after the sequenced response. The DUPLICATE/idempotency AUTHORITY is
 * the engine itself (clientOrderKey, FR-IMRG14 — journaled and replay-safe): the ledger's
 * duplicate check is a fast pre-filter and its restart rehydration restores correlation, while
 * a crash-window retry is resolved by the engine mapping the same client order key back to the
 * one original order. fsync is amortized ({@link #FORCE_EVERY} appends), the journal's
 * per-batch durability discipline.
 *
 * <p>Threading: appends and by-ClOrdID lookups happen on FIX session/submitter threads
 * (synchronized); {@link #byOrderRef(int)} is read by the ExecutionReport handler on the
 * output-ring thread via a ConcurrentHashMap — lock-free, and the output-ring thread sits
 * outside the exact-zero allocation boundary, so the boxed key lookup is acceptable there
 * (NFR-FIX01).
 *
 * <p>Retention (ENGINE-REVIEW F11). The in-memory index holds the newest {@link #CAPACITY}
 * correlations, the same bound as the cluster gateway's {@code FixGatewayAcceptor} ClOrdID map,
 * and rehydration reads only that many records from the file's tail, so neither heap nor startup
 * grows with the life of the file. Eviction costs the OrigClOrdID/status convenience for an order
 * older than the newest 64Ki; OrderID (37) still resolves, and the engine's replicated
 * clientOrderKey table stays the duplicate authority. ponytail: the file itself is still
 * append-only (88 B per order); rotate it when disk, not heap, becomes the bound.
 *
 * <p>Ambiguous orders (FR-FIX12 outcome d) enter the ledger with {@link #ORDER_REF_UNKNOWN}:
 * the ClOrdID was submitted but no sequenced response named its order. A same-ClOrdID retry is
 * NOT a duplicate (it is the documented reconciliation path, and the engine answers it from the
 * same key); the response to that retry resolves the entry. A cancel or status request against
 * an unresolved entry is refused as "outcome unknown" rather than "unknown order".
 */
public final class ClOrdIdLedger implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ClOrdIdLedger.class);

    static final int RECORD_SIZE = 88;
    static final int MAX_CLORDID_BYTES = 64;
    private static final int FORCE_EVERY = 64;
    /** Newest correlations kept in memory: the cluster gateway's {@code CLORDID_MAP_CAPACITY}. */
    static final int CAPACITY = 65_536;
    /** The orderRef of an ambiguous submission: no sequenced response named an order. Real refs
     *  are positive ({@code FixMessages.parseOrderRef} answers -1 for none). */
    public static final int ORDER_REF_UNKNOWN = 0;

    public enum AppendResult { OK, DUPLICATE, UNAVAILABLE }

    /** One correlated order. {@link #resolved()} is false while the outcome is unknown. */
    public record Entry(long sessionKey, String clOrdId, int orderRef) {
        public boolean resolved() {
            return orderRef > 0;
        }
    }

    private record Key(long sessionKey, String clOrdId) { }

    private final Path file;
    /** Insertion-ordered so the eldest correlation is the one evicted; a resolution re-inserts. */
    private final LinkedHashMap<Key, Entry> index = new LinkedHashMap<>(1024, 0.75f, false);
    private final ConcurrentHashMap<Integer, Entry> byOrderRef = new ConcurrentHashMap<>();
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
            log.info("ClOrdID ledger ready: {} entries from {}", byOrderRef.size(), file);
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

    /** Record an admitted order. DUPLICATE means already correlated (do not re-admit);
     *  UNAVAILABLE means fail closed — reject at the session level (FR-FIX10). A ClOrdID recorded
     *  as ambiguous ({@link #appendAmbiguous}) is resolved, not duplicated, by a positive ref. */
    public synchronized AppendResult append(long sessionKey, String clOrdId, int orderRef) {
        if (!available) {
            return AppendResult.UNAVAILABLE;
        }
        byte[] id = clOrdId.getBytes(StandardCharsets.UTF_8);
        if (id.length == 0 || id.length > MAX_CLORDID_BYTES) {
            throw new IllegalArgumentException("ClOrdID must be 1.." + MAX_CLORDID_BYTES + " UTF-8 bytes");
        }
        if (orderRef < 0) {
            throw new IllegalArgumentException("orderRef must be positive, or ORDER_REF_UNKNOWN for an ambiguous order");
        }
        Key key = new Key(sessionKey, clOrdId);
        Entry existing = index.get(key);
        if (existing != null && (existing.resolved() || orderRef == ORDER_REF_UNKNOWN)) {
            return AppendResult.DUPLICATE;
        }
        writeBuf.clear();
        writeBuf.putLong(sessionKey).putLong(0L /* reserved */).putInt(orderRef).putShort((short) id.length);
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
        remember(new Entry(sessionKey, clOrdId, orderRef));
        return AppendResult.OK;
    }

    /** Record a submission whose outcome is unknown (FR-FIX12 outcome d): no reject may be sent and
     *  no ref is known, but the ClOrdID has been submitted. DUPLICATE if already recorded. */
    public synchronized AppendResult appendAmbiguous(long sessionKey, String clOrdId) {
        return append(sessionKey, clOrdId, ORDER_REF_UNKNOWN);
    }

    /** Index an entry as the newest, evicting the eldest past {@link #CAPACITY}. A resolution
     *  replaces its ambiguous entry in place of age: it is re-inserted as newest. */
    private void remember(Entry e) {
        Key key = new Key(e.sessionKey(), e.clOrdId());
        index.remove(key);
        index.put(key, e);
        if (e.resolved()) {
            byOrderRef.put(e.orderRef(), e);
        }
        if (index.size() > CAPACITY) {
            Iterator<Entry> eldest = index.values().iterator();
            Entry evicted = eldest.next();
            eldest.remove();
            if (evicted.resolved()) {
                byOrderRef.remove(evicted.orderRef(), evicted);
            }
        }
    }

    /** Session-thread lookup for cancels/status (OrigClOrdID resolution). */
    public synchronized Entry byClOrdId(long sessionKey, String clOrdId) {
        return index.get(new Key(sessionKey, clOrdId));
    }

    /** Output-ring-thread lookup joining OutputEvent.orderRef to the owning session. */
    public Entry byOrderRef(int orderRef) {
        return byOrderRef.get(orderRef);
    }

    public boolean available() {
        return available;
    }

    public synchronized int size() {
        return index.size();
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
        long size = channel.size();
        // Only the newest CAPACITY records can survive into the index, so start there: a record
        // older than that would be evicted as soon as the tail replays over it. Records are fixed
        // size and the file is append-only, so the offset lands on a record boundary; a torn tail
        // still truncates at the last good record below.
        long offset = Math.max(0L, (size / RECORD_SIZE - CAPACITY) * RECORD_SIZE);
        while (offset + RECORD_SIZE <= size) {
            buf.clear();
            int read = channel.read(buf, offset);
            if (read < RECORD_SIZE) {
                break;
            }
            buf.flip();
            long sessionKey = buf.getLong();
            buf.getLong();  // reserved slot
            int orderRef = buf.getInt();
            short len = buf.getShort();
            if (len <= 0 || len > MAX_CLORDID_BYTES || orderRef < 0) {
                break;  // torn/corrupt record: stop, caller truncates here
            }
            byte[] id = new byte[len];
            buf.get(id);
            Entry e = new Entry(sessionKey, new String(id, StandardCharsets.UTF_8), orderRef);
            Entry existing = index.get(new Key(sessionKey, e.clOrdId()));
            if (existing == null || (!existing.resolved() && e.resolved())) {
                remember(e);   // an ambiguous record followed by its resolution replays to resolved
            }
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
