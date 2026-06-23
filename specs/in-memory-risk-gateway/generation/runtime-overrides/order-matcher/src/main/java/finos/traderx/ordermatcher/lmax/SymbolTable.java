package finos.traderx.ordermatcher.lmax;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Gateway-owned ticker &lt;-&gt; securityId mapping (FR-09B05). Strings never cross into the
 * rings or the BLP: tickers are interned to dense ints at the edge, and rendered back only
 * in the output handlers. Registration is rare (first sighting of a ticker); lookups on the
 * egress side are a plain array read.
 */
public final class SymbolTable {
    private final ConcurrentHashMap<String, Integer> idByTicker = new ConcurrentHashMap<>();
    private final AtomicReferenceArray<String> tickerById;
    private final AtomicInteger nextId = new AtomicInteger(0);
    private final int maxSecurities;

    public SymbolTable(int maxSecurities) {
        this.maxSecurities = maxSecurities;
        this.tickerById = new AtomicReferenceArray<>(maxSecurities);
    }

    /** Map a ticker to its securityId, registering it on first sight during authoritative bootstrap only. */
    public int idFor(String ticker) {
        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        Integer existing = idByTicker.get(normalized);
        if (existing != null) {
            return existing;
        }
        return idByTicker.computeIfAbsent(normalized, key -> {
            int id = nextId.getAndIncrement();
            if (id >= maxSecurities) {
                throw new IllegalStateException("symbol table full: max " + maxSecurities + " securities");
            }
            tickerById.set(id, key);
            return id;
        });
    }

    /** Register an authoritative reference-data id without allowing client-driven assignment. */
    public synchronized void registerAuthoritative(int securityId, String ticker) {
        if (securityId < 0 || securityId >= maxSecurities) {
            throw new IllegalArgumentException("security id out of range: " + securityId);
        }
        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        Integer existing = idByTicker.putIfAbsent(normalized, securityId);
        if (existing != null && existing != securityId) {
            throw new IllegalStateException("ticker already mapped to another id: " + normalized);
        }
        tickerById.set(securityId, normalized);
        nextId.accumulateAndGet(securityId + 1, Math::max);
    }

    /** Client-command lookup. Never creates a new symbol. */
    public int idForExisting(String ticker) {
        Integer id = idByTicker.get(ticker.trim().toUpperCase(Locale.ROOT));
        if (id == null) {
            throw new IllegalArgumentException("unknown security: " + ticker);
        }
        return id;
    }

    /** Render a securityId back to its ticker. Output-edge only (FR-09B25). */
    public String tickerFor(int securityId) {
        if (securityId < 0 || securityId >= maxSecurities) {
            return null;
        }
        return tickerById.get(securityId);
    }

    public int size() {
        return nextId.get();
    }
}
