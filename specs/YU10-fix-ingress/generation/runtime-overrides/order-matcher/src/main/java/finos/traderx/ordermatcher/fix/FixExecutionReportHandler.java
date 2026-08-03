package finos.traderx.ordermatcher.fix;

import com.lmax.disruptor.EventHandler;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.Px;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Output-disruptor handler for FIX lifecycle ExecutionReports (FR-FIX05). Runs on the output-ring
 * thread and is strictly enqueue-only there: it copies the primitives it needs from the pooled
 * {@link OutputEvent} into a small job and hands it to the fix-report-sender thread, which builds
 * and sends the QuickFIX/J message. No network, disk, or QuickFIX/J call happens on the ring
 * thread (NFR-FIX01; the output-ring thread is outside the exact-zero allocation boundary, so
 * the job allocation is acceptable — the same trade every existing output handler makes).
 *
 * <p>Admission reports (New / Rejected) are NOT produced here — they derive synchronously from
 * the sequenced batch response on the session submitter thread (FixOrderApplication). This
 * handler covers the post-admission lifecycle: partial fills, fills, and cancels.
 *
 * <p>Inert until {@link #wire(ClOrdIdLedger, FixOrderRegistry, ReportSender)} is called by
 * FixIngress at startup — as a plain Spring bean in a context with FIX disabled (or in tests
 * that construct the engine directly) it drops every event at a null-check.
 */
@Component
public class FixExecutionReportHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(FixExecutionReportHandler.class);
    private static final int QUEUE_CAPACITY = 65536;

    /** Sender-side callback implemented by FixIngress (owns the QuickFIX/J session objects). */
    public interface ReportSender {
        void send(ReportJob job, FixOrderRegistry.Ctx ctx);
    }

    /** Primitive snapshot of one lifecycle event (the pooled OutputEvent must not escape). */
    public record ReportJob(byte kind, int orderRef, int quantity, int remainingQty,
                            long limitPx, long lastExecPx, int lastFillQty, byte riskReason) { }

    private volatile ClOrdIdLedger ledger;
    private volatile FixOrderRegistry registry;
    private volatile ReportSender sender;
    private volatile Thread senderThread;
    private final BlockingQueue<ReportJob> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();

    public void wire(ClOrdIdLedger ledger, FixOrderRegistry registry, ReportSender sender) {
        this.ledger = ledger;
        this.registry = registry;
        this.sender = sender;
        Thread t = new Thread(this::drainLoop, "fix-report-sender");
        t.setDaemon(true);
        t.start();
        this.senderThread = t;
    }

    @Override
    public void onEvent(OutputEvent ev, long sequence, boolean endOfBatch) {
        ClOrdIdLedger l = ledger;
        if (l == null) {
            return;                                   // FIX disabled / not started
        }
        switch (ev.kind) {
            case OutputEvent.KIND_ORDER_PARTIALLY_FILLED,
                 OutputEvent.KIND_ORDER_FILLED,
                 OutputEvent.KIND_ORDER_CANCELED -> {
                if (l.byOrderRef(ev.orderRef) == null) {
                    return;                           // not a FIX-originated order
                }
                ReportJob job = new ReportJob(ev.kind, ev.orderRef, ev.quantity, ev.remainingQty,
                    ev.limitPx, ev.lastExecPx, ev.lastFillQty, ev.riskReason);
                if (!queue.offer(job)) {
                    // Bounded by design: never stall the output ring for a slow FIX consumer.
                    // The dropped report is recoverable via OrderStatusRequest (ADR-037).
                    long n = dropped.incrementAndGet();
                    if ((n & (n - 1)) == 0) {         // log at 1,2,4,8,... not per event
                        log.warn("fix-report queue full; {} lifecycle reports dropped so far "
                            + "(clients reconcile via OrderStatusRequest)", n);
                    }
                }
            }
            default -> { }
        }
    }

    private void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ReportJob job = queue.take();
                FixOrderRegistry.Ctx ctx = registry.byOrderRef(job.orderRef());
                if (ctx == null) {
                    // Pre-restart order (registry is session-lifetime): the counterparty
                    // recovers this transition via OrderStatusRequest. TD-FIX01.
                    continue;
                }
                try {
                    sender.send(job, ctx);
                } catch (Exception ex) {
                    log.warn("ExecutionReport send failed for orderRef {}: {}", job.orderRef(), ex.toString());
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public long droppedReports() {
        return dropped.get();
    }

    static long pxOrZero(long px) {
        return px == Px.NONE ? 0L : px;
    }
}
