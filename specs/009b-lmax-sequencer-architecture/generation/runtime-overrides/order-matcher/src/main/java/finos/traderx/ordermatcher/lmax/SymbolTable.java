package finos.traderx.ordermatcher.lmax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Gateway-owned ticker &lt;-&gt; securityId mapping (FR-09B05). Strings never cross into the
 * rings or the BLP: tickers are interned to dense ints at the edge, and rendered back only
 * in the output handlers. Registration is rare (first sighting of a ticker); lookups on the
 * egress side are a plain array read.
 *
 * <p>State 009b recovery: the securityId is the key the journal stores for every event, so it MUST
 * mean the same ticker across restarts — otherwise journal replay applies an event to the wrong
 * security. Because ids are assigned in first-seen order (process-local), the mapping is persisted
 * to a small durable file ({@code symbols.tab}) and reloaded on startup before any id is assigned,
 * so the assignment is stable for the life of the journal. New registrations append to the file.
 */
public final class SymbolTable {
    private static final Logger log = LoggerFactory.getLogger(SymbolTable.class);

    private final ConcurrentHashMap<String, Integer> idByTicker = new ConcurrentHashMap<>();
    private final AtomicReferenceArray<String> tickerById;
    private final AtomicInteger nextId = new AtomicInteger(0);
    private final int maxSecurities;

    private volatile Path persistFile;          // null => persistence disabled
    private final Object persistLock = new Object();

    public SymbolTable(int maxSecurities) {
        this.maxSecurities = maxSecurities;
        this.tickerById = new AtomicReferenceArray<>(maxSecurities);
    }

    /**
     * Enable durable ticker&lt;-&gt;id persistence and load any existing mapping, so ids are STABLE
     * across restarts (the journal stores ids; replay must resolve them to the same tickers). Must be
     * called before any {@link #idFor} so the restored ids win over fresh assignment.
     */
    public void enablePersistence(Path file) {
        this.persistFile = file;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
        } catch (IOException ex) {
            log.warn("Unable to create symbol-table directory {}; id persistence disabled", file, ex);
            this.persistFile = null;
            return;
        }
        load(file);
    }

    private void load(Path file) {
        if (!Files.exists(file)) {
            return;
        }
        int restored = 0;
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int tab = trimmed.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                int id = Integer.parseInt(trimmed.substring(0, tab));
                String ticker = trimmed.substring(tab + 1);
                if (id < 0 || id >= maxSecurities) {
                    continue;
                }
                idByTicker.put(ticker, id);
                tickerById.set(id, ticker);
                if (id >= nextId.get()) {
                    nextId.set(id + 1);
                }
                restored++;
            }
            log.info("Restored {} ticker->id mappings from {} (next id {})", restored,
                file.toAbsolutePath(), nextId.get());
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to load symbol table from {}; continuing with fresh ids (replay may diverge)",
                file, ex);
        }
    }

    /** Map a ticker to its securityId, registering it on first sight. Edge-only. */
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
            persist(id, key);
            return id;
        });
    }

    private void persist(int id, String ticker) {
        Path file = persistFile;
        if (file == null) {
            return;
        }
        synchronized (persistLock) {
            try {
                Files.writeString(file, id + "\t" + ticker + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                log.warn("Failed to persist ticker {} (id {}); replay may diverge after restart", ticker, id, ex);
            }
        }
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
