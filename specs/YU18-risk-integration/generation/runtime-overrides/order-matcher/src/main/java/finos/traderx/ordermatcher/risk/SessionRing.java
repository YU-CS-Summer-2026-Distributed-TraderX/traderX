package finos.traderx.ordermatcher.risk;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * ADR-104 (FR-XOD-19..25): FIX exactly-once in the core. Each FIX session-day ({@code sessionStream}, which the
 * gateway derives from the session key and the FIX day's creation time, the same on every replica) keeps its last
 * {@code n} NewOrderSingles: the ClOrdID's key ({@code clientOrderKey}), an independent 32-bit check of it, the
 * message's MsgSeqNum and the answer. A NEW whose key and check are held is a resend: it is answered from the ring and
 * never executes. A NEW that is not held is new, unless the ring has aged (evicted something) and the request says it
 * may be a resend; then it is refused AGED_OUT, never booked, except a FIX-level resend (PossDup) whose own MsgSeqNum is
 * above every MsgSeqNum the ring has evicted, which provably never reached the engine. Nothing is stored before submit,
 * and no NATS body is added.
 *
 * <p>Same key, different check: a different ClOrdID whose 64-bit key collides. It is booked as new (a collision never
 * refuses a real order), and both stay indexed: an entry is found by its key and its check together.
 *
 * <p>Deterministic: every decision is a function of the log. Time enters only through {@code nowMillis}, the cluster log
 * timestamp (FR-XOD-08), and only to pick which idle session a new one may evict. Session indices are replicated state.
 * A session's arrays are allocated when its index is first used and reused when it is evicted, so the heap is
 * proportional to the sessions seen (~60 KB each at n = 2,048), not to the table. BLP thread only.
 */
public final class SessionRing implements SequencedAnswers {
    public static final int ADMIT = 0;
    public static final int DUPLICATE = 1;
    public static final int AGED_OUT = 2;
    public static final int LIMIT = 3;

    private static final byte PENDING = -1;
    /** Format 15's T_SESSION_RING entries per record (~6 KB, unpacked): read on load, written by tests only. */
    public static final int CHUNK = 256;
    /** FR-XOD-23 format 16: {@link #writeHead}'s bytes, {int s, long stream, long lastUsed, long inserted,
     *  long evictedMaxMsgSeq, int n}. */
    public static final int HEAD_BYTES = 40;
    /** {@link #writeEntries}: {int s, int start, int count}, then the entries column by column: count longs (key),
     *  count ints each (check, msgSeq), count longs (ref), count bytes (decision). 25 bytes per entry; column-major so
     *  each column is one sequential copy (measured ~2.5x faster to write than entry-major). Format 16 wrote the ref as
     *  an int, 21 bytes per entry; ADR-105's format 17 widened it, and a format 16 record still restores. */
    public static final int ENTRIES_HEADER_BYTES = 12;
    public static final int ENTRY_BYTES = 25;
    public static final int FORMAT_16_ENTRY_BYTES = 21;
    /** Entries per record: the most that pack into one 1,376-byte snapshot message with the record type and the
     *  packer's length slot, 4 + 4 + 4 + 12 + 25 x 53 = 1,349 (FR-XOD-18 then sends no ring record unpacked; format 16
     *  packed 64 at 21 bytes). */
    public static final int ENTRIES_PER_RECORD = 53;

    private final int n;
    private final int maxSessions;
    private final long idleMillis;
    // sessions
    private final int[] sessionSlots;        // hash slot -> session index + 1 (0 = empty)
    private final long[] sessionStreams;
    private final long[] lastUsed;
    private final long[] inserted;           // NEWs admitted on the session, ever (its ring position is inserted % n)
    private final long[] evictedMaxMsgSeq;   // the highest MsgSeqNum the ring has evicted (0: never aged)
    private int used;
    // entries, per session at its ring position; null until the session index is first used
    private final long[][] keys;
    private final int[][] checks;
    private final int[][] msgSeqs;
    private final long[][] refs;
    private final byte[][] decisions;
    private final int[][] keySlots;          // per session: hash(key) slot -> position + 1 (0 = empty)
    private final int slotCount;

    private int currentSession = -1;         // the entry the last admit() opened or found
    private int current = -1;
    private int restoring = -1;              // format 16 load: the session whose head was read last, or -1
    private int restoreHeld;                 // its entries, as the head declared them
    private int restoreNext;                 // its next expected entry

    public SessionRing(final int n, final int maxSessions, final long idleMillis) {
        if (n < 1 || maxSessions < 1 || idleMillis < 0) {
            throw new IllegalArgumentException("session ring n " + n + ", maxSessions " + maxSessions
                + ", idleMillis " + idleMillis);
        }
        this.n = n;
        this.maxSessions = maxSessions;
        this.idleMillis = idleMillis;
        this.sessionSlots = new int[Integer.highestOneBit(maxSessions * 2 - 1) << 1];
        this.sessionStreams = new long[maxSessions];
        this.lastUsed = new long[maxSessions];
        this.inserted = new long[maxSessions];
        this.evictedMaxMsgSeq = new long[maxSessions];
        this.keys = new long[maxSessions][];
        this.checks = new int[maxSessions][];
        this.msgSeqs = new int[maxSessions][];
        this.refs = new long[maxSessions][];
        this.decisions = new byte[maxSessions][];
        this.keySlots = new int[maxSessions][];
        this.slotCount = Integer.highestOneBit(n * 2 - 1) << 1;
    }

    public int n() { return n; }
    public int maxSessions() { return maxSessions; }
    public int size() { return used; }

    /**
     * Classify a session-keyed NEW. ADMIT opens a PENDING entry that {@link #record} fills; DUPLICATE (key and check
     * held) changes nothing but the session's last-use time; AGED_OUT and LIMIT change nothing.
     */
    public int admit(final long sessionStream, final long key, final int check, final int msgSeqNum,
                     final boolean possDup, final boolean possResend, final long nowMillis) {
        currentSession = -1;
        current = -1;
        int s = findSession(sessionStream);
        if (s >= 0) {
            final int e = findKey(s, key, check);
            if (e >= 0) {
                currentSession = s;
                current = e;
                lastUsed[s] = nowMillis;
                return DUPLICATE;
            }
            if (e < 0 && evictedMaxMsgSeq[s] > 0 && (possDup || possResend)
                && !(possDup && Integer.toUnsignedLong(msgSeqNum) > evictedMaxMsgSeq[s])) {
                return AGED_OUT;   // it may have executed and been evicted: never booked by this request
            }
        } else {
            s = allocate(nowMillis);
            if (s < 0) {
                return LIMIT;
            }
            open(s, sessionStream);
        }
        lastUsed[s] = nowMillis;
        currentSession = s;
        current = append(s, key, check, msgSeqNum);
        refs[s][current] = 0;
        decisions[s][current] = PENDING;
        return ADMIT;
    }

    @Override
    public void record(final long orderRef, final byte decision) {
        if (current < 0) return;
        refs[currentSession][current] = orderRef;
        decisions[currentSession][current] = decision;
    }

    @Override
    public byte duplicateDecision() {
        return current < 0 ? PENDING : decisions[currentSession][current];
    }

    @Override
    public long duplicateRef() {
        return current < 0 ? 0 : refs[currentSession][current];
    }

    /** True once the session has evicted an entry (ADR-104's aged rule applies). Tests and /health. */
    public boolean aged(final long sessionStream) {
        final int s = findSession(sessionStream);
        return s >= 0 && evictedMaxMsgSeq[s] > 0;
    }

    /** Session index s now names {@code sessionStream}: empty ring, arrays allocated on first use or reused. */
    private void open(final int s, final long sessionStream) {
        if (keys[s] == null) {
            keys[s] = new long[n];
            checks[s] = new int[n];
            msgSeqs[s] = new int[n];
            refs[s] = new long[n];
            decisions[s] = new byte[n];
            keySlots[s] = new int[slotCount];
        } else {
            java.util.Arrays.fill(keySlots[s], 0);
        }
        sessionStreams[s] = sessionStream;
        inserted[s] = 0;
        evictedMaxMsgSeq[s] = 0;
        insertSession(s);
    }

    /** The next ring position, evicting the oldest entry when full; indexes it. */
    private int append(final int s, final long key, final int check, final int msgSeqNum) {
        final int e = (int) (inserted[s] % n);
        if (inserted[s] >= n) {
            unindex(s, e);
            evictedMaxMsgSeq[s] = Math.max(evictedMaxMsgSeq[s], Math.max(1L, Integer.toUnsignedLong(msgSeqs[s][e])));
        }
        keys[s][e] = key;
        checks[s][e] = check;
        msgSeqs[s][e] = msgSeqNum;
        index(s, e);
        inserted[s]++;
        return e;
    }

    // ----- snapshot (FR-XOD-23) ------------------------------------------------------------------

    /** Records for one session, oldest entry first, at most {@link #CHUNK} entries each (at least one record). */
    public int chunks(final int s) {
        final int held = (int) Math.min(n, inserted[s]);
        return Math.max(1, (held + CHUNK - 1) / CHUNK);
    }

    /**
     * Chunk {@code c} of session {@code s} as {index, sessionStream, lastUsed, inserted, evictedMaxMsgSeq, n, start,
     * count, entries...}, each entry {key, check << 32 | msgSeq, ref << 8 | decision}.
     */
    public long[] tuple(final int s, final int c) {
        final int held = (int) Math.min(n, inserted[s]);
        final int start = c * CHUNK;
        final int count = Math.max(0, Math.min(CHUNK, held - start));
        final long[] t = new long[8 + 3 * count];
        t[0] = s;
        t[1] = sessionStreams[s];
        t[2] = lastUsed[s];
        t[3] = inserted[s];
        t[4] = evictedMaxMsgSeq[s];
        t[5] = n;
        t[6] = start;
        t[7] = count;
        for (int i = 0; i < count; i++) {
            final int e = (int) ((inserted[s] - held + start + i) % n);
            t[8 + 3 * i] = keys[s][e];
            t[9 + 3 * i] = ((long) checks[s][e] << 32) | Integer.toUnsignedLong(msgSeqs[s][e]);
            t[10 + 3 * i] = ((long) refs[s][e] << 8) | (decisions[s][e] & 0xffL);
        }
        return t;
    }

    /** Format 16 entry records for session s: oldest entry first, {@link #ENTRIES_PER_RECORD} each. */
    public int entryRecords(final int s) {
        final int held = (int) Math.min(n, inserted[s]);
        return (held + ENTRIES_PER_RECORD - 1) / ENTRIES_PER_RECORD;
    }

    /** Session s's head record body at {@code at}; returns its length ({@link #HEAD_BYTES}). Allocates nothing. */
    public int writeHead(final int s, final MutableDirectBuffer b, final int at) {
        b.putInt(at, s);
        b.putLong(at + 4, sessionStreams[s]);
        b.putLong(at + 12, lastUsed[s]);
        b.putLong(at + 20, inserted[s]);
        b.putLong(at + 28, evictedMaxMsgSeq[s]);
        b.putInt(at + 36, n);
        return HEAD_BYTES;
    }

    /** Entry record c of session s at {@code at}, straight from the ring's arrays; returns its length. Allocates nothing. */
    public int writeEntries(final int s, final int c, final MutableDirectBuffer b, final int at) {
        final int held = (int) Math.min(n, inserted[s]);
        final int start = c * ENTRIES_PER_RECORD;
        final int count = Math.max(0, Math.min(ENTRIES_PER_RECORD, held - start));
        b.putInt(at, s);
        b.putInt(at + 4, start);
        b.putInt(at + 8, count);
        final long[] k = keys[s];
        final int[] ch = checks[s], ms = msgSeqs[s];
        final long[] rf = refs[s];
        final byte[] d = decisions[s];
        final int col = at + ENTRIES_HEADER_BYTES;
        // the record's entries are ring positions p0.. in order, wrapping once at most: one run, or two
        int e = (int) ((inserted[s] - held + start) % n);
        for (int i = 0; i < count; e = 0) {
            final int run = Math.min(count - i, n - e);
            for (int j = 0; j < run; j++) b.putLong(col + 8 * (i + j), k[e + j]);
            for (int j = 0; j < run; j++) b.putInt(col + 8 * count + 4 * (i + j), ch[e + j]);
            for (int j = 0; j < run; j++) b.putInt(col + 12 * count + 4 * (i + j), ms[e + j]);
            for (int j = 0; j < run; j++) b.putLong(col + 16 * count + 8 * (i + j), rf[e + j]);
            b.putBytes(col + 24 * count + i, d, e, run);
            i += run;
        }
        return ENTRIES_HEADER_BYTES + ENTRY_BYTES * count;
    }

    /** Restore one {@link #writeHead} record: opens the session with no entries. Sessions arrive in index order, each
     *  complete before the next. Fails closed, as {@link #restore}. */
    public void restoreHead(final DirectBuffer b, final int at, final int length) {
        final int s = b.getInt(at);
        final long stream = b.getLong(at + 4);
        final long ins = b.getLong(at + 20);
        final int ringN = b.getInt(at + 36);
        if (length != HEAD_BYTES) {
            throw new IllegalStateException("snapshot corrupt: session ring head " + s + " of " + length + " bytes");
        }
        if (ringN != n) {
            throw new IllegalStateException("snapshot session rings were written with EXACTLY_ONCE_SESSION_RING " + ringN
                + ", this member runs " + n + " (FR-XOD-23)");
        }
        requireRestoreComplete();
        if (ins < 0) {
            throw new IllegalStateException("snapshot corrupt: session ring " + s);
        }
        if (s != used || s >= maxSessions || findSession(stream) >= 0) {
            throw new IllegalStateException("snapshot session index " + s + " (expected " + used
                + ", EXACTLY_ONCE_SESSION_STREAMS " + maxSessions + ")");
        }
        used++;
        open(s, stream);
        lastUsed[s] = b.getLong(at + 12);
        evictedMaxMsgSeq[s] = b.getLong(at + 28);
        restoreHeld = (int) Math.min(n, ins);
        restoreNext = 0;
        restoring = s;
        inserted[s] = ins - restoreHeld;   // the entries go back at their own ring positions, as restore() puts them
        if (restoreHeld == 0) restoring = -1;   // held is 0 only for a session that was never sent an entry
    }

    /** Restore one {@link #writeEntries} record of the session whose head came last; contiguous, in order. A format 16
     *  record ({@code wideRefs} false) carries its refs as ints. */
    public void restoreEntries(final DirectBuffer b, final int at, final int length, final boolean wideRefs) {
        final int entryBytes = wideRefs ? ENTRY_BYTES : FORMAT_16_ENTRY_BYTES;
        final int s = b.getInt(at);
        final int start = b.getInt(at + 4);
        final int count = b.getInt(at + 8);
        if (restoring < 0 || s != restoring) {
            throw new IllegalStateException("snapshot corrupt: session ring " + s + " entries without their head");
        }
        if (start != restoreNext || count < 1 || count > restoreHeld - start
            || length != ENTRIES_HEADER_BYTES + entryBytes * count) {
            throw new IllegalStateException("snapshot corrupt: session ring " + s + " entries " + start + "+" + count
                + " (expected from " + restoreNext + " of " + restoreHeld + ", " + length + " bytes)");
        }
        final long[] k = keys[s];
        final int[] ch = checks[s], ms = msgSeqs[s];
        final long[] rf = refs[s];
        final byte[] d = decisions[s];
        final int col = at + ENTRIES_HEADER_BYTES;
        // entries go back at their own ring positions, in insertion order, and are indexed as admit() indexed them
        for (int i = 0; i < count; i++) {
            final int e = (int) (inserted[s] % n);
            k[e] = b.getLong(col + 8 * i);
            ch[e] = b.getInt(col + 8 * count + 4 * i);
            ms[e] = b.getInt(col + 12 * count + 4 * i);
            rf[e] = wideRefs ? b.getLong(col + 16 * count + 8 * i) : b.getInt(col + 16 * count + 4 * i);
            d[e] = b.getByte(col + (entryBytes - 1) * count + i);
            index(s, e);
            inserted[s]++;
        }
        restoreNext = start + count;
        if (restoreNext == restoreHeld) {
            restoring = -1;
            restoreHeld = restoreNext = 0;   // a restored ring is field-for-field the one format 15's path restores
        }
    }

    /** Fails if a format 16 session's head declared entries the stream did not carry. Called at each head and at the
     *  end of the load. */
    public void requireRestoreComplete() {
        if (restoring >= 0) {
            throw new IllegalStateException("snapshot incomplete: session ring " + restoring + " restored "
                + restoreNext + " of " + restoreHeld + " entries");
        }
    }

    /** Restore one {@link #tuple}; sessions arrive in index order, each session's chunks in order. Fails closed. */
    public void restore(final long[] t) {
        final int s = (int) t[0];
        if (t[5] != n) {
            throw new IllegalStateException("snapshot session rings were written with EXACTLY_ONCE_SESSION_RING " + t[5]
                + ", this member runs " + n + " (FR-XOD-23)");
        }
        final long ins = t[3];
        final int held = (int) Math.min(n, ins);
        final int start = (int) t[6];
        final int count = (int) t[7];
        if (t.length != 8 + 3 * count || ins < 0 || start < 0 || count < 0 || start + count > held) {
            throw new IllegalStateException("snapshot corrupt: session ring " + s);
        }
        if (start == 0) {
            if (s != used || s >= maxSessions || findSession(t[1]) >= 0) {
                throw new IllegalStateException("snapshot session index " + s + " (expected " + used
                    + ", EXACTLY_ONCE_SESSION_STREAMS " + maxSessions + ")");
            }
            requireRestoreComplete();
            used++;
            open(s, t[1]);
        } else if (s != used - 1 || sessionStreams[s] != t[1]) {
            throw new IllegalStateException("snapshot corrupt: session ring " + s + " chunk out of order");
        }
        lastUsed[s] = t[2];
        evictedMaxMsgSeq[s] = t[4];
        // entries go back at their own ring positions, in insertion order, and are indexed as admit() indexed them
        inserted[s] = ins - held + start;
        for (int i = 0; i < count; i++) {
            final int e = (int) (inserted[s] % n);
            keys[s][e] = t[8 + 3 * i];
            checks[s][e] = (int) (t[9 + 3 * i] >>> 32);
            msgSeqs[s][e] = (int) t[9 + 3 * i];
            refs[s][e] = t[10 + 3 * i] >> 8;
            decisions[s][e] = (byte) t[10 + 3 * i];
            index(s, e);
            inserted[s]++;
        }
        if (start + count == held) {
            inserted[s] = ins;
        }
    }

    public void clear() {
        java.util.Arrays.fill(sessionSlots, 0);
        used = 0;
        currentSession = -1;
        current = -1;
        restoring = -1;
    }

    // ----- sessions ------------------------------------------------------------------------------

    /** A free index, or the least-recently-used session idle at least idleMillis (ties: lowest index). */
    private int allocate(final long nowMillis) {
        if (used < maxSessions) {
            return used++;
        }
        // ponytail: O(maxSessions) scan, only when a NEW session meets a full table; open() clears its index.
        int oldest = 0;
        for (int i = 1; i < maxSessions; i++) {
            if (lastUsed[i] < lastUsed[oldest]) oldest = i;
        }
        if (nowMillis - lastUsed[oldest] < idleMillis) {
            return -1;
        }
        removeSession(oldest);
        return oldest;
    }

    private int findSession(final long sessionStream) {
        final int mask = sessionSlots.length - 1;
        for (int s = mix(sessionStream) & mask; ; s = (s + 1) & mask) {
            final int v = sessionSlots[s];
            if (v == 0) return -1;
            if (sessionStreams[v - 1] == sessionStream) return v - 1;
        }
    }

    private void insertSession(final int index) {
        final int mask = sessionSlots.length - 1;
        int s = mix(sessionStreams[index]) & mask;
        while (sessionSlots[s] != 0) s = (s + 1) & mask;
        sessionSlots[s] = index + 1;
    }

    /** Backward-shift deletion, as StreamDedupe.remove: no tombstones. */
    private void removeSession(final int index) {
        final int mask = sessionSlots.length - 1;
        int hole = mix(sessionStreams[index]) & mask;
        while (sessionSlots[hole] != index + 1) hole = (hole + 1) & mask;
        for (int j = (hole + 1) & mask; sessionSlots[j] != 0; j = (j + 1) & mask) {
            final int home = mix(sessionStreams[sessionSlots[j] - 1]) & mask;
            if (hole <= j ? (hole < home && home <= j) : (hole < home || home <= j)) continue;
            sessionSlots[hole] = sessionSlots[j];
            hole = j;
        }
        sessionSlots[hole] = 0;
    }

    // ----- keys ----------------------------------------------------------------------------------

    private int findKey(final int s, final long key, final int check) {
        final int[] slots = keySlots[s];
        final int mask = slots.length - 1;
        for (int slot = mix(key) & mask; ; slot = (slot + 1) & mask) {
            final int v = slots[slot];
            if (v == 0) return -1;
            if (keys[s][v - 1] == key && checks[s][v - 1] == check) return v - 1;
        }
    }

    /** Index position e. Entries sharing a key (a 64-bit collision) are all indexed and told apart by their checks. */
    private void index(final int s, final int e) {
        final int[] slots = keySlots[s];
        final int mask = slots.length - 1;
        int slot = mix(keys[s][e]) & mask;
        while (slots[slot] != 0) slot = (slot + 1) & mask;
        slots[slot] = e + 1;
    }

    /** Drop position e from the index: backward-shift deletion. */
    private void unindex(final int s, final int e) {
        final int[] slots = keySlots[s];
        final int mask = slots.length - 1;
        int hole = mix(keys[s][e]) & mask;
        while (slots[hole] != e + 1) hole = (hole + 1) & mask;
        for (int j = (hole + 1) & mask; slots[j] != 0; j = (j + 1) & mask) {
            final int home = mix(keys[s][slots[j] - 1]) & mask;
            if (hole <= j ? (hole < home && home <= j) : (hole < home || home <= j)) continue;
            slots[hole] = slots[j];
            hole = j;
        }
        slots[hole] = 0;
    }

    private static int mix(long h) {
        h ^= h >>> 29;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 32;
        return (int) h;
    }
}
