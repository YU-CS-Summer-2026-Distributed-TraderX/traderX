package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeState;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * YU05 (post-trade-compliance, ADR-022, FR-PTC02/06): a booked trade sits in {@code Processing}
 * until its settlement date passes, then this sweep advances it to {@code Settled}. Read/write
 * only against trade-processor's own MariaDB rows — never reaches into order-matcher's
 * journal/BLP state (FR-PTC07).
 */
@Service
public class SettlementService {
    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final TradeRepository tradeRepository;
    private final LongAdder sweptCount = new LongAdder();

    public SettlementService(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    @Scheduled(fixedDelayString = "${settlement.sweep.interval-ms:5000}")
    @Transactional
    public void sweep() {
        List<Trade> due = tradeRepository.findByStateAndSettlementDateLessThanEqual(
            TradeState.Processing, new Date());
        if (due.isEmpty()) {
            return;
        }
        Date now = new Date();
        for (Trade trade : due) {
            trade.setState(TradeState.Settled);
            trade.setUpdated(now);
        }
        tradeRepository.saveAll(due);
        sweptCount.add(due.size());
        log.info("Settlement sweep advanced {} trade(s) to Settled", due.size());
    }

    /** Operator override (`POST /trades/{id}/settlement/force`) — settles immediately. */
    @Transactional
    public ForceResult forceSettle(String tradeId) {
        Optional<Trade> found = tradeRepository.findById(tradeId);
        if (found.isEmpty()) {
            return ForceResult.NOT_FOUND;
        }
        Trade trade = found.get();
        if (trade.getState() == TradeState.Settled) {
            return ForceResult.ALREADY_SETTLED;
        }
        Date now = new Date();
        trade.setState(TradeState.Settled);
        trade.setSettlementDate(now);
        trade.setUpdated(now);
        tradeRepository.save(trade);
        sweptCount.increment();
        log.info("Settlement forced by operator override: id={}", tradeId);
        return ForceResult.SETTLED;
    }

    public long sweptCount() {
        return sweptCount.sum();
    }

    public enum ForceResult { SETTLED, ALREADY_SETTLED, NOT_FOUND }
}
