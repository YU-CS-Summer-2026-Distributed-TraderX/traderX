package finos.traderx.ordermatcher.lmax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * Warm-standby journal tailer (FR-09B30..FR-09B32 realized over the shared journal): continuously
 * reads the live node's append-only input journal and applies every complete record to this
 * process's BLP, so the standby's book/positions/read-model track the leader within milliseconds.
 * This is the LMAX "replicator feeds a follower BLP" seam with the journal itself as the
 * replication stream — no extra hot-path work on the leader, and the stream is exactly the one
 * recovery already trusts.
 *
 * <p>Single consumer thread; the reused {@link InputEvent} and the 64-byte framing mirror
 * {@link Journaler}/{@link JournalReader}. A partial record at the file tail (an append in
 * progress) is simply left in the buffer until its remaining bytes arrive. When a record carries a
 * securityId this process has not seen, {@code symbols.tab} is reloaded first — the leader persists
 * the mapping before sequencing the event, so the mapping is always already durable.
 */
public final class JournalFollower implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JournalFollower.class);
    private static final int RECORD_SIZE = 64;

    private final Path journalFile;
    private final SymbolTable symbols;
    private final Consumer<InputEvent> apply;
    private final long pollMillis;
    private final Thread thread;

    private volatile boolean running = true;
    private volatile long appliedOffset;
    private volatile long appliedEvents;

    public JournalFollower(Path journalDir, SymbolTable symbols, Consumer<InputEvent> apply,
                           long startOffset, long pollMillis) {
        this.journalFile = journalDir.resolve("input-events.journal");
        this.symbols = symbols;
        this.apply = apply;
        this.appliedOffset = Math.max(0, startOffset - (startOffset % RECORD_SIZE));
        this.pollMillis = Math.max(1, pollMillis);
        this.thread = new Thread(this::run, "journal-follower");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
        log.info("Journal follower tailing {} from byte {}", journalFile.toAbsolutePath(), appliedOffset);
    }

    /** Journal byte offset up to which every record has been applied (a record boundary). */
    public long appliedOffset() {
        return appliedOffset;
    }

    public long appliedEvents() {
        return appliedEvents;
    }

    /** Bytes the leader has journaled that this follower has not applied yet (staleness bound). */
    public long lagBytes() {
        try {
            return Math.max(0, Files.size(journalFile) - appliedOffset);
        } catch (IOException ex) {
            return 0;
        }
    }

    /** Signal the tail loop to exit and wait for it; the caller then owns the journal position. */
    public void stopAndJoin() {
        running = false;
        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        InputEvent event = new InputEvent();
        ByteBuffer buf = ByteBuffer.allocateDirect(RECORD_SIZE * 4096).order(ByteOrder.LITTLE_ENDIAN);
        FileChannel channel = null;
        try {
            while (running) {
                if (channel == null) {
                    channel = openWhenPresent();
                    if (channel == null) {
                        continue;   // still waiting for the leader's first append (or stop)
                    }
                }
                int read = channel.read(buf);
                buf.flip();
                boolean appliedAny = false;
                while (buf.remaining() >= RECORD_SIZE) {
                    JournalReader.decode(buf, event);
                    resolveSymbolIfUnknown(event);
                    apply.accept(event);
                    appliedEvents++;
                    appliedOffset += RECORD_SIZE;
                    appliedAny = true;
                }
                buf.compact();
                if (read <= 0 && !appliedAny) {
                    Thread.sleep(pollMillis);   // caught up; wait for the leader to append
                }
            }
        } catch (InterruptedException | ClosedByInterruptException ex) {
            // stopAndJoin: fall through and exit at the current record boundary
        } catch (Exception ex) {
            log.error("Journal follower stopped at byte {}: {}", appliedOffset, ex.toString(), ex);
        } finally {
            closeQuietly(channel);
        }
        log.info("Journal follower exited at byte {} ({} events applied)", appliedOffset, appliedEvents);
    }

    /** Open the journal positioned at the applied offset, waiting while it does not exist yet. */
    private FileChannel openWhenPresent() throws IOException, InterruptedException {
        while (running) {
            if (Files.exists(journalFile)) {
                FileChannel channel = FileChannel.open(journalFile, StandardOpenOption.READ);
                channel.position(Math.min(appliedOffset, channel.size()));
                return channel;
            }
            Thread.sleep(Math.max(pollMillis, 200));
        }
        return null;
    }

    /**
     * The journal stores securityIds; rendering (read model, output handlers) needs the ticker. The
     * leader appends new mappings to symbols.tab BEFORE sequencing the first event that uses them,
     * so an unknown id here just means our load is stale — reload the table.
     */
    private void resolveSymbolIfUnknown(InputEvent e) {
        boolean carriesSecurity = e.type == InputEvent.TYPE_ORDER_NEW
            || e.type == InputEvent.TYPE_PRICE_TICK
            || e.type == InputEvent.TYPE_TRADE_NEW;
        if (carriesSecurity && symbols.tickerFor(e.securityId) == null) {
            symbols.reload();
            if (symbols.tickerFor(e.securityId) == null) {
                log.warn("securityId {} not in symbols.tab after reload; rendering will show it as unknown",
                    e.securityId);
            }
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (IOException ignored) {
            // shutdown path
        }
    }

    @Override
    public void close() {
        stopAndJoin();
    }
}
