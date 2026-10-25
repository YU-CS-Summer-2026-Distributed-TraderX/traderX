package finos.traderx.ordermatcher.lmax;

/**
 * YU18 typed-order index (components/order-types FR-OT42): an intrusive binary min-heap of orders
 * over one key, so a print or a reference change visits only the orders it can affect.
 *
 * <p>Six kinds share the code; a max-heap is a min-heap over the negated key:
 * <ul>
 *   <li>STOP_BUY / STOP_SELL: pending stops of one security and side, by stop level. A print
 *   {@code px} latches every key {@code <= px} (buy) or {@code <= -px} (sell).</li>
 *   <li>TRAIL_SELL / TRAIL_BUY: pending trailing stops by watermark. A print moves every key
 *   strictly below {@code px} (sell) or {@code -px} (buy).</li>
 *   <li>PEG_SELL / PEG_BUY: one peg group (security, side, reference, offset) by cap. A reference
 *   move can change only keys below a threshold; the rest stay pinned at their cap.</li>
 *   <li>SUSPENDED: a security's suspended pegs, by admission sequence; every reference move
 *   re-tries all of them (FR-OT24, FR-OT39).</li>
 * </ul>
 *
 * <p>The layout depends on insertion history but NEVER reaches output: every set
 * {@link #collectAtMost} returns is re-sorted by admission sequence ({@link #sortByAdmission})
 * before anything acts on it (FR-OT11, FR-OT25). Allocation-free except array growth, which
 * happens only at a new high-water mark.
 */
final class OrderHeap {
    static final byte STOP_BUY = 0;
    static final byte STOP_SELL = 1;
    static final byte TRAIL_SELL = 2;
    static final byte TRAIL_BUY = 3;
    static final byte PEG_SELL = 4;
    static final byte PEG_BUY = 5;
    static final byte SUSPENDED = 6;

    byte kind;
    private boolean trail;
    private RestingOrder[] a = new RestingOrder[16];
    private int size;

    // Peg-group identity (PEG_* kinds only); a security's groups form a singly linked list.
    byte pegRef;
    int pegOffset;
    OrderHeap nextGroup;

    OrderHeap(byte kind) {
        this(kind, (byte) 0, 0);
    }

    OrderHeap(byte kind, byte pegRef, int pegOffset) {
        reinit(kind, pegRef, pegOffset);
    }

    /** Re-key an EMPTY heap (a pooled peg group). */
    void reinit(byte kind, byte pegRef, int pegOffset) {
        this.kind = kind;
        this.trail = kind == TRAIL_SELL || kind == TRAIL_BUY || kind == SUSPENDED;
        this.pegRef = pegRef;
        this.pegOffset = pegOffset;
    }

    int size() {
        return size;
    }

    boolean buySide() {
        return kind == PEG_BUY;
    }

    private long key(RestingOrder o) {
        switch (kind) {
            case STOP_BUY:
                return o.stopPx;
            case STOP_SELL:
                return -o.stopPx;
            case TRAIL_SELL:
                return o.watermark;
            case TRAIL_BUY:
                return -o.watermark;
            case PEG_SELL:
                return o.capPx;
            case PEG_BUY:
                return -o.capPx;
            default:
                return o.admissionSeq;
        }
    }

    // An order can sit in two heaps — a trailing stop in a stop heap and a watermark heap, a
    // suspended peg in its group and the suspended heap — so the second one uses trailIdx.
    private int idx(RestingOrder o) {
        return trail ? o.trailIdx : o.heapIdx;
    }

    private void place(RestingOrder o, int i) {
        a[i] = o;
        if (trail) {
            o.trailIdx = i;
        } else {
            o.heapIdx = i;
        }
    }

    void add(RestingOrder o) {
        if (size == a.length) {
            a = java.util.Arrays.copyOf(a, size << 1);
        }
        place(o, size++);
        up(size - 1);
    }

    void remove(RestingOrder o) {
        final int i = idx(o);
        final RestingOrder last = a[--size];
        a[size] = null;
        if (trail) {
            o.trailIdx = -1;
        } else {
            o.heapIdx = -1;
        }
        if (last != o) {
            place(last, i);
            changed(last);
        }
    }

    /** Re-establish heap order after {@code o}'s key changed in place. */
    void changed(RestingOrder o) {
        down(up(idx(o)));
    }

    /** Append every member whose key is {@code <= max} to {@code out[from..]}; returns the new end. */
    int collectAtMost(long max, RestingOrder[] out, int from) {
        return collect(0, max, out, from);
    }

    // Visits only the qualifying members plus their children: O(collected). Recursion depth is
    // the heap height (log2 size), so it is bounded and allocation-free.
    private int collect(int i, long max, RestingOrder[] out, int n) {
        if (i >= size || key(a[i]) > max) {
            return n;
        }
        out[n++] = a[i];
        n = collect(2 * i + 1, max, out, n);
        return collect(2 * i + 2, max, out, n);
    }

    private int up(int i) {
        final RestingOrder o = a[i];
        final long k = key(o);
        while (i > 0) {
            final int p = (i - 1) >>> 1;
            if (key(a[p]) <= k) {
                break;
            }
            place(a[p], i);
            i = p;
        }
        place(o, i);
        return i;
    }

    private void down(int i) {
        final RestingOrder o = a[i];
        final long k = key(o);
        while (true) {
            int c = 2 * i + 1;
            if (c >= size) {
                break;
            }
            if (c + 1 < size && key(a[c + 1]) < key(a[c])) {
                c++;
            }
            if (key(a[c]) >= k) {
                break;
            }
            place(a[c], i);
            i = c;
        }
        place(o, i);
    }

    /** In-place heapsort of {@code a[from..to)} by admission sequence (unique, FR-OT11). */
    static void sortByAdmission(RestingOrder[] a, int from, int to) {
        final int n = to - from;
        for (int i = n / 2 - 1; i >= 0; i--) {
            sift(a, from, i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            final RestingOrder t = a[from];
            a[from] = a[from + end];
            a[from + end] = t;
            sift(a, from, 0, end);
        }
    }

    private static void sift(RestingOrder[] a, int base, int i, int n) {
        while (true) {
            int c = 2 * i + 1;
            if (c >= n) {
                return;
            }
            if (c + 1 < n && a[base + c + 1].admissionSeq > a[base + c].admissionSeq) {
                c++;
            }
            if (a[base + c].admissionSeq <= a[base + i].admissionSeq) {
                return;
            }
            final RestingOrder t = a[base + i];
            a[base + i] = a[base + c];
            a[base + c] = t;
            i = c;
        }
    }
}
