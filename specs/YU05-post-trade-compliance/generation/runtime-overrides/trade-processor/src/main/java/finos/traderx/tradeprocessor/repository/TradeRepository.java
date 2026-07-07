package finos.traderx.tradeprocessor.repository;

import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeState;
import java.util.Date;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TradeRepository extends JpaRepository<Trade, String> {
  List<Trade> findByAccountId(Integer id);

  // YU05 (post-trade-compliance, FR-PTC02): SettlementService's T+N sweep target set.
  List<Trade> findByStateAndSettlementDateLessThanEqual(TradeState state, Date settlementDate);

  // YU05 (post-trade-compliance, FR-PTC10): orphan sweep only needs ids, not full rows.
  @Query("select t.id from Trade t")
  List<String> findAllIds();
}
