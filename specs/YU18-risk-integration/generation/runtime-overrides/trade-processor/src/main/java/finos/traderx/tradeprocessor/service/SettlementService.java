package finos.traderx.tradeprocessor.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded settlement of the projection only; never modifies the matching engine. */
@Service
public class SettlementService {
    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private final JdbcTemplate jdbc;
    private final ProjectionWriteLock lock;
    private final TransactionTemplate transactions;
    private final TransactionTemplate forceTransactions;
    private final int batchSize;
    private final int maxBatches;
    private final long budgetNanos;
    private final Timer batchTimer;
    private final LongAdder sweptCount = new LongAdder();

    public SettlementService(JdbcTemplate jdbc, ProjectionWriteLock lock,
            PlatformTransactionManager manager, MeterRegistry meters,
            @Value("${settlement.sweep.batch-size:500}") int batchSize,
            @Value("${settlement.sweep.max-batches:100}") int maxBatches,
            @Value("${settlement.sweep.budget-ms:1000}") long budgetMs) {
        if (batchSize < 1 || batchSize > 2000 || maxBatches < 1 || maxBatches > 1000
                || budgetMs < 1 || budgetMs > 30000) {
            throw new IllegalArgumentException("settlement bounds: batch-size 1..2000, max-batches 1..1000, budget-ms 1..30000");
        }
        this.jdbc = jdbc;
        this.lock = lock;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.budgetNanos = budgetMs * 1_000_000L;
        this.forceTransactions = new TransactionTemplate(manager);
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batchTimer = Timer.builder("traderx_settlement_batch").description("Committed settlement batch duration including lock wait").register(meters);
        Gauge.builder("traderx_settlement_swept_total", sweptCount, LongAdder::sum).register(meters);
        log.info("Settlement bounds: {} rows/transaction, {} batches/sweep, {} ms between-batch budget",
                batchSize, maxBatches, budgetMs);
    }

    @Scheduled(fixedDelayString = "${settlement.sweep.interval-ms:5000}")
    public void sweep() {
        Timestamp cutoff = new Timestamp(System.currentTimeMillis());
        long start = System.nanoTime();
        long total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            long batchStart = System.nanoTime();
            int changed = transactions.execute(status -> settleBatch(cutoff));
            batchTimer.record(System.nanoTime() - batchStart, TimeUnit.NANOSECONDS);
            // execute returns only after commit; exceptions cannot advance the counter.
            sweptCount.add(changed);
            total += changed;
            if (changed < batchSize || System.nanoTime() - start >= budgetNanos) break;
        }
        if (total > 0) log.info("Settlement sweep advanced {} trade(s) to Settled", total);
    }

    private int settleBatch(Timestamp cutoff) {
        lock.acquire();
        // Restart at the earliest remaining due row after each commit. The predicate shrinks:
        // no OFFSET, lifetime cursor, entity graph or outer transaction. The schema migration
        // supplies idx_trades_settlement_due for this ordered range on retained databases too.
        List<String> ids = jdbc.queryForList(
                "SELECT ID FROM TRADES WHERE STATE='Processing' AND SETTLEMENTDATE<=? "
                + "ORDER BY SETTLEMENTDATE,ID LIMIT ? FOR UPDATE", String.class, cutoff, batchSize);
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 2];
        args[0] = new Timestamp(System.currentTimeMillis());
        args[1] = cutoff;
        for (int i = 0; i < ids.size(); i++) args[i + 2] = ids.get(i);
        return jdbc.update("UPDATE TRADES SET STATE='Settled', UPDATED=? "
                + "WHERE STATE='Processing' AND SETTLEMENTDATE<=? AND ID IN (" + placeholders + ")", args);
    }

    /** Preserve the operator override: any existing non-Settled state can settle immediately. */
    public ForceResult forceSettle(String tradeId) {
        return forceTransactions.execute(status -> {
            lock.acquire();
            List<String> states = jdbc.queryForList(
                    "SELECT STATE FROM TRADES WHERE ID=? FOR UPDATE", String.class, tradeId);
            if (states.isEmpty()) return ForceResult.NOT_FOUND;
            if ("Settled".equals(states.getFirst())) return ForceResult.ALREADY_SETTLED;
            Timestamp now = new Timestamp(System.currentTimeMillis());
            int changed = jdbc.update("UPDATE TRADES SET STATE='Settled', SETTLEMENTDATE=?, UPDATED=? WHERE ID=?",
                    now, now, tradeId);
            if (changed != 1) throw new IllegalStateException("settlement override lost locked trade");
            // Preserve the old REQUIRED transaction participation. An enclosing rollback must
            // roll back the force operation too, and must not advance its process-local count.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    sweptCount.increment();
                    log.info("Settlement forced by operator override: id={}", tradeId);
                }
            });
            return ForceResult.SETTLED;
        });
    }

    public long sweptCount() { return sweptCount.sum(); }
    public enum ForceResult { SETTLED, ALREADY_SETTLED, NOT_FOUND }
}
