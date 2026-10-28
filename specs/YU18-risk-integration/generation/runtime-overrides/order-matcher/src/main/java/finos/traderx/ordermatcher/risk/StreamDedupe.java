package finos.traderx.ordermatcher.risk;

/**
 * ADR-090: exactly-once order entry by a contiguous sequence per {@code (account, streamId)}.
 *
 * <p>A sequenced NEW names its stream and its {@code seq}. {@code seq == next} is admitted and
 * advances {@code next}; {@code seq < next} is a duplicate, answered from the stream's ring of the
 * last {@code window} answers; {@code seq > next} is a gap and changes nothing. Because nothing below
 * {@code next} is ever skipped, "below next" means "already answered" for the life of the stream —
 * no time bound, unlike the FR-IMRG14 key window (262,144 keys = 0.66 s at 400K/s).
 *
 * <p>Deterministic: every decision is a function of the log. Time enters only through
 * {@code nowMillis}, which the caller passes from the cluster log timestamp (FR-XOD-08), and only to
 * pick which idle stream a new one may evict. Stream indices are part of the replicated state (the
 * snapshot restores them exactly), because the eviction scan breaks ties by index.
 *
 * <p>Zero-allocation on the apply path; every array is presized. BLP thread only.
 */
public final class StreamDedupe {
    public static final int ADMIT = 0;
    public static final int DUPLICATE = 1;
    public static final int GAP = 2;
    public static final int LIMIT = 3;

    private static final byte PENDING = -1;   // admitted, no answer recorded (yet)

    private final int window;
    private final int maxStreams;
    private final long idleMillis;
    private final int[] slots;          // hash slot -> stream index + 1 (0 = empty)
    private final int[] accounts;       // per stream index
    private final long[] streamIds;
    private final long[] nexts;
    private final long[] lastUsed;
    private final int[] ringRefs;       // index * window + (seq - 1) % window
    private final byte[] ringDecisions;
    private int used;                   // indices [0, used) are occupied; only eviction reuses one

    // the last admit()'s stream and outcome, read by the caller and by BlpRiskState.remember
    private int current = -1;
    private long currentSeq;
    private long expected;

    public StreamDedupe(final int window, final int maxStreams, final long idleMillis) {
        if (window < 1 || maxStreams < 1 || idleMillis < 0) {
            throw new IllegalArgumentException("stream dedupe window " + window + ", maxStreams "
                + maxStreams + ", idleMillis " + idleMillis);
        }
        this.window = window;
        this.maxStreams = maxStreams;
        this.idleMillis = idleMillis;
        this.slots = new int[Integer.highestOneBit(maxStreams * 2 - 1) << 1];
        this.accounts = new int[maxStreams];
        this.streamIds = new long[maxStreams];
        this.nexts = new long[maxStreams];
        this.lastUsed = new long[maxStreams];
        this.ringRefs = new int[maxStreams * window];
        this.ringDecisions = new byte[maxStreams * window];
    }

    public int window() { return window; }
    public int maxStreams() { return maxStreams; }
    public int size() { return used; }

    /**
     * Classify a sequenced request. ADMIT advances {@code next} and opens a PENDING ring entry that
     * {@link #record} fills; DUPLICATE and GAP change nothing but the stream's last-use time (GAP not
     * even that); LIMIT means the table is full of streams used within {@code idleMillis}.
     */
    public int admit(final int account, final long streamId, final long seq, final long nowMillis) {
        current = -1;
        currentSeq = seq;
        int index = find(account, streamId);
        final long next = index < 0 ? 1L : nexts[index];
        expected = next;
        if (seq != next) {
            if (seq < 1L || seq > next || index < 0) {
                return GAP;   // an unknown stream starts at 1: it never adopts a later start
            }
            current = index;
            lastUsed[index] = nowMillis;
            return DUPLICATE;
        }
        if (index < 0) {
            index = allocate(nowMillis);
            if (index < 0) {
                return LIMIT;
            }
            accounts[index] = account;
            streamIds[index] = streamId;
            insert(index);
        }
        current = index;
        nexts[index] = next + 1;
        lastUsed[index] = nowMillis;
        final int pos = ringPos(index, seq);
        ringRefs[pos] = 0;
        ringDecisions[pos] = PENDING;
        return ADMIT;
    }

    /** {@code next} at the last admit(): the sequence a GAP answer tells the client to resend from. */
    public long expected() { return expected; }

    /** After ADMIT: the answer to remember for this sequence. */
    public void record(final int orderRef, final byte decision) {
        if (current < 0) return;
        final int pos = ringPos(current, currentSeq);
        ringRefs[pos] = orderRef;
        ringDecisions[pos] = decision;
    }

    /** After DUPLICATE: the recorded decision ordinal, or -1 when none is held (pending or older than the window). */
    public byte duplicateDecision() {
        return inWindow() ? ringDecisions[ringPos(current, currentSeq)] : PENDING;
    }

    /** After DUPLICATE: the original order ref, 0 when none is held. */
    public int duplicateRef() {
        return inWindow() ? ringRefs[ringPos(current, currentSeq)] : 0;
    }

    /** After DUPLICATE: true when the sequence is older than the ring holds. */
    public boolean beyondWindow() {
        return current >= 0 && !inWindow();
    }

    private boolean inWindow() {
        return current >= 0 && nexts[current] - currentSeq <= window;
    }

    private int ringPos(final int index, final long seq) {
        return index * window + (int) ((seq - 1L) % window);
    }

    // ----- snapshot (FR-XOD-09) ------------------------------------------------------------------

    /**
     * One stream as {index, account, streamId, next, lastUsed, window, n, answers...}: the n answers
     * for seq next-n .. next-1, each {@code orderRef << 8 | decision & 0xff}.
     */
    public long[] tuple(final int index) {
        final long next = nexts[index];
        final int n = (int) Math.min(window, next - 1L);
        final long[] t = new long[7 + n];
        t[0] = index;
        t[1] = accounts[index];
        t[2] = streamIds[index];
        t[3] = next;
        t[4] = lastUsed[index];
        t[5] = window;
        t[6] = n;
        for (int i = 0; i < n; i++) {
            final int pos = ringPos(index, next - n + i);
            t[7 + i] = ((long) ringRefs[pos] << 8) | (ringDecisions[pos] & 0xffL);
        }
        return t;
    }

    /** Restore one {@link #tuple}; tuples arrive in index order 0, 1, 2 ... Fails closed. */
    public void restore(final long[] t) {
        final int index = (int) t[0];
        if (index != used || index >= maxStreams) {
            throw new IllegalStateException("snapshot stream index " + index + " (expected " + used
                + ", EXACTLY_ONCE_MAX_STREAMS " + maxStreams + ")");
        }
        if (t[5] != window) {
            throw new IllegalStateException("snapshot streams were written with EXACTLY_ONCE_STREAM_WINDOW "
                + t[5] + ", this member runs " + window + " (FR-XOD-06)");
        }
        final long next = t[3];
        final int n = (int) t[6];
        if (next < 1L || n != Math.min(window, next - 1L) || t.length != 7 + n
            || find((int) t[1], t[2]) >= 0) {
            throw new IllegalStateException("snapshot corrupt: stream " + index);
        }
        used++;
        accounts[index] = (int) t[1];
        streamIds[index] = t[2];
        nexts[index] = next;
        lastUsed[index] = t[4];
        insert(index);
        for (int i = 0; i < n; i++) {
            final int pos = ringPos(index, next - n + i);
            ringRefs[pos] = (int) (t[7 + i] >> 8);
            ringDecisions[pos] = (byte) t[7 + i];
        }
    }

    public void clear() {
        java.util.Arrays.fill(slots, 0);
        used = 0;
        current = -1;
    }

    // ----- table ---------------------------------------------------------------------------------

    /** A free index, or the least-recently-used stream idle at least idleMillis (ties: lowest index). */
    private int allocate(final long nowMillis) {
        if (used < maxStreams) {
            return used++;
        }
        // ponytail: O(maxStreams) scan, and only when a NEW stream meets a full table; an intrusive LRU
        // list if EXACTLY_ONCE_MAX_STREAMS grows past ~1e5.
        int oldest = 0;
        for (int i = 1; i < maxStreams; i++) {
            if (lastUsed[i] < lastUsed[oldest]) oldest = i;
        }
        if (nowMillis - lastUsed[oldest] < idleMillis) {
            return -1;
        }
        remove(oldest);
        return oldest;
    }

    private int find(final int account, final long streamId) {
        final int mask = slots.length - 1;
        for (int s = hash(account, streamId) & mask; ; s = (s + 1) & mask) {
            final int v = slots[s];
            if (v == 0) return -1;
            if (accounts[v - 1] == account && streamIds[v - 1] == streamId) return v - 1;
        }
    }

    private void insert(final int index) {
        final int mask = slots.length - 1;
        int s = hash(accounts[index], streamIds[index]) & mask;
        while (slots[s] != 0) s = (s + 1) & mask;
        slots[s] = index + 1;
    }

    /** Backward-shift deletion, as BlpRiskState.deleteIdempotencySlot: no tombstones. */
    private void remove(final int index) {
        final int mask = slots.length - 1;
        int hole = hash(accounts[index], streamIds[index]) & mask;
        while (slots[hole] != index + 1) hole = (hole + 1) & mask;
        for (int j = (hole + 1) & mask; slots[j] != 0; j = (j + 1) & mask) {
            final int v = slots[j] - 1;
            final int home = hash(accounts[v], streamIds[v]) & mask;
            final boolean homeInGap = hole <= j ? (hole < home && home <= j) : (hole < home || home <= j);
            if (homeInGap) continue;
            slots[hole] = slots[j];
            hole = j;
        }
        slots[hole] = 0;
    }

    private static int hash(final int account, final long streamId) {
        long h = streamId ^ (0x9e3779b97f4a7c15L * account);
        h ^= h >>> 29;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 32;
        return (int) h;
    }
}
