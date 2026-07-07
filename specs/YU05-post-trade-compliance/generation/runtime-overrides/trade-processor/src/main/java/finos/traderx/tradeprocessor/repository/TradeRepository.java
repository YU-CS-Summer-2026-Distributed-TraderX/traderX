package finos.traderx.tradeprocessor.repository;

import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeState;
import java.util.Date;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<Trade, String> {
  List<Trade> findByAccountId(Integer id);

  // YU05 (post-trade-compliance, FR-PTC02): SettlementService's T+N sweep target set.
  List<Trade> findByStateAndSettlementDateLessThanEqual(TradeState state, Date settlementDate);
}
