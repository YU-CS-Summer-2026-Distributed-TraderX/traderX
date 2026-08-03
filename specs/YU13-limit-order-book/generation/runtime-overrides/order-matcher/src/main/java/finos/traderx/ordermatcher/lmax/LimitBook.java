package finos.traderx.ordermatcher.lmax;

/**
 * One security's two-sided limit-order book (YU13, FR-LOB01..05): array-indexed price
 * levels with intrusive doubly-linked FIFO queues of pooled {@link RestingOrder} entries.
 *
 * Structure (all BLP-thread only, zero allocation after construction):
 * <ul>
 *   <li>Prices live on a fixed grid: {@code tickTicks} Px units per book tick
 *       ({@code 10_000} = one cent at the Px x1e6 scale). Limit prices must be exact grid
 *       multiples — the engine rejects off-grid limits, so a level's price IS every resting
 *       order's exact limit price and execution at level price never violates a limit.</li>
 *   <li>The book covers a band of {@code levels} consecutive ticks anchored when the
 *       security's first limit order arrives: slot 0 = {@code baseLevel} absolute ticks,
 *       anchored so the first price sits mid-band. Limits outside the band are rejected
 *       (PRICE_COLLAR) — deterministic, and exactly what a venue's price collar does.</li>
 *   <li>Per side: head/tail entry per level (FIFO = arrival order = consensus-log order,
 *       the YU12 time-priority source), an aggregate open-quantity per level (plain long[]
 *       — edge threads read depth racily-but-safely, never walking the links), and an
 *       occupancy bitmap driving best-price maintenance. Best-price advance scans the
 *       bitmap word-wise away from the emptied level.</li>
 * </ul>
 */
public final class LimitBook {
    public static final int NO_LEVEL = -1;

    private final long tickTicks;
    private final int levels;
    private long baseLevel = -1;  // absolute tick index of slot 0; -1 = not yet anchored

    private final RestingOrder[] bidHead;
    private final RestingOrder[] bidTail;
    private final RestingOrder[] askHead;
    private final RestingOrder[] askTail;
    private final long[] bidQty;
    private final long[] askQty;
    private final long[] bidBits;
    private final long[] askBits;
    private int bestBid = NO_LEVEL;   // slot index; bids match from highest slot downward
    private int bestAsk = NO_LEVEL;   // slot index; asks match from lowest slot upward
    private int openOrders;

    public LimitBook(int levels, long tickTicks) {
        // Constant messages: this class is on the banned-API gate's hot-path scan list
        // (no runtime string concat in the constant pool).
        if (Integer.bitCount(levels) != 1 || levels < 64) {
            throw new IllegalArgumentException("book levels must be a power of two >= 64");
        }
        if (tickTicks <= 0) {
            throw new IllegalArgumentException("book tick must be positive");
        }
        this.levels = levels;
        this.tickTicks = tickTicks;
        this.bidHead = new RestingOrder[levels];
        this.bidTail = new RestingOrder[levels];
        this.askHead = new RestingOrder[levels];
        this.askTail = new RestingOrder[levels];
        this.bidQty = new long[levels];
        this.askQty = new long[levels];
        this.bidBits = new long[levels >> 6];
        this.askBits = new long[levels >> 6];
    }

    public int levels() {
        return levels;
    }

    public long tickTicks() {
        return tickTicks;
    }

    public boolean anchored() {
        return baseLevel >= 0;
    }

    public long baseLevel() {
        return baseLevel;
    }

    /** Snapshot restore: adopt the exact band anchor the snapshot recorded (replica identity). */
    public void bootstrapBase(long restoredBaseLevel) {
        this.baseLevel = restoredBaseLevel;
    }

    /** True when {@code limitPx} is an exact multiple of the book tick. */
    public boolean onGrid(long limitPx) {
        return limitPx % tickTicks == 0;
    }

    /**
     * Array slot for a grid-aligned limit price, anchoring the band on first use so the
     * first price sits mid-band. Returns {@link #NO_LEVEL} for prices outside the band.
     */
    public int slotFor(long limitPx) {
        final long absLevel = limitPx / tickTicks;
        if (baseLevel < 0) {
            baseLevel = Math.max(0, absLevel - (levels >> 1));
        }
        final long slot = absLevel - baseLevel;
        return slot < 0 || slot >= levels ? NO_LEVEL : (int) slot;
    }

    /** Exact price (Px ticks) of a level slot. */
    public long priceAt(int slot) {
        return (baseLevel + slot) * tickTicks;
    }

    /** Append at the level's FIFO tail (arrival order = consensus-log order = time priority). */
    public void append(RestingOrder o, int slot) {
        final boolean bid = o.side == InputEvent.SIDE_BUY;
        final RestingOrder[] tail = bid ? bidTail : askTail;
        o.bookLevel = slot;
        o.bookNext = null;
        o.bookPrev = tail[slot];
        if (tail[slot] == null) {
            (bid ? bidHead : askHead)[slot] = o;
            (bid ? bidBits : askBits)[slot >> 6] |= 1L << (slot & 63);
            if (bid) {
                if (bestBid == NO_LEVEL || slot > bestBid) {
                    bestBid = slot;
                }
            } else {
                if (bestAsk == NO_LEVEL || slot < bestAsk) {
                    bestAsk = slot;
                }
            }
        } else {
            tail[slot].bookNext = o;
        }
        tail[slot] = o;
        (bid ? bidQty : askQty)[slot] += o.remaining;
        openOrders++;
    }

    /** A resting order was partially executed in place: keep its queue slot, shrink the level. */
    public void reduce(RestingOrder o, int qty) {
        (o.side == InputEvent.SIDE_BUY ? bidQty : askQty)[o.bookLevel] -= qty;
    }

    /**
     * Unlink a resting order (cancel, full fill, force-fill). Call while {@code o.remaining}
     * still holds the order's open quantity — the level aggregate subtracts it.
     */
    public void remove(RestingOrder o) {
        final int slot = o.bookLevel;
        final boolean bid = o.side == InputEvent.SIDE_BUY;
        final RestingOrder[] head = bid ? bidHead : askHead;
        final RestingOrder[] tail = bid ? bidTail : askTail;
        if (o.bookPrev == null) {
            head[slot] = o.bookNext;
        } else {
            o.bookPrev.bookNext = o.bookNext;
        }
        if (o.bookNext == null) {
            tail[slot] = o.bookPrev;
        } else {
            o.bookNext.bookPrev = o.bookPrev;
        }
        (bid ? bidQty : askQty)[slot] -= o.remaining;
        o.bookNext = null;
        o.bookPrev = null;
        o.bookLevel = NO_LEVEL;
        openOrders--;
        if (head[slot] == null) {
            (bid ? bidBits : askBits)[slot >> 6] &= ~(1L << (slot & 63));
            if (bid && slot == bestBid) {
                bestBid = scanDown(bidBits, slot);
            } else if (!bid && slot == bestAsk) {
                bestAsk = scanUp(askBits, slot);
            }
        }
    }

    /** Best bid slot or {@link #NO_LEVEL}; bids match from the highest occupied level down. */
    public int bestBidSlot() {
        return bestBid;
    }

    /** Best ask slot or {@link #NO_LEVEL}; asks match from the lowest occupied level up. */
    public int bestAskSlot() {
        return bestAsk;
    }

    /** FIFO head of a level on the given side (time priority: oldest first). */
    public RestingOrder headAt(byte side, int slot) {
        return (side == InputEvent.SIDE_BUY ? bidHead : askHead)[slot];
    }

    public int openOrders() {
        return openOrders;
    }

    /** Aggregate open quantity resting at a slot (racy-but-safe for edge depth reads). */
    public long quantityAt(byte side, int slot) {
        return (side == InputEvent.SIDE_BUY ? bidQty : askQty)[slot];
    }

    /**
     * Top-of-book depth: fills {@code outPx}/{@code outQty} best-first with up to
     * {@code max} non-empty levels of {@code side}, returning the count. Bitmap walk over
     * plain arrays — safe for racy edge reads (a torn read shows a stale level, never a
     * broken structure). Cold path.
     */
    public int depth(byte side, long[] outPx, long[] outQty, int max) {
        final boolean bid = side == InputEvent.SIDE_BUY;
        final long[] bits = bid ? bidBits : askBits;
        final long[] qty = bid ? bidQty : askQty;
        int n = 0;
        int slot = bid ? bestBid : bestAsk;
        while (slot != NO_LEVEL && n < max) {
            final long levelQty = qty[slot];
            if (levelQty > 0) {
                outPx[n] = priceAt(slot);
                outQty[n] = levelQty;
                n++;
            }
            slot = bid ? scanDown(bits, slot) : scanUp(bits, slot);
        }
        return n;
    }

    // ponytail: single-level bitmap scan — worst case levels/64 word reads (~1us at 1<<16
    // levels) when a side empties across the whole band; add a summary word tier only if a
    // measured tail ever blames this.
    private static int scanDown(long[] bits, int fromExclusive) {
        int slot = fromExclusive - 1;
        if (slot < 0) {
            return NO_LEVEL;
        }
        int word = slot >> 6;
        long mask = bits[word] & (-1L >>> (63 - (slot & 63)));
        while (true) {
            if (mask != 0) {
                return (word << 6) + 63 - Long.numberOfLeadingZeros(mask);
            }
            if (--word < 0) {
                return NO_LEVEL;
            }
            mask = bits[word];
        }
    }

    private int scanUp(long[] bits, int fromExclusive) {
        int slot = fromExclusive + 1;
        if (slot >= levels) {
            return NO_LEVEL;
        }
        int word = slot >> 6;
        long mask = bits[word] & (-1L << (slot & 63));
        final int words = bits.length;
        while (true) {
            if (mask != 0) {
                return (word << 6) + Long.numberOfTrailingZeros(mask);
            }
            if (++word >= words) {
                return NO_LEVEL;
            }
            mask = bits[word];
        }
    }
}
