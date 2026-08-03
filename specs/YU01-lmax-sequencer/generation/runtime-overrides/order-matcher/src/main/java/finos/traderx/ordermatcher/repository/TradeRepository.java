package finos.traderx.ordermatcher.repository;

import finos.traderx.ordermatcher.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Read-model writer for booked trades (TRADES). state YU01: the order-matcher Projector
 * persists trades off the output ring (FR-09B22), replacing trade-processor's inline JPA.
 */
@Repository
public interface TradeRepository extends JpaRepository<Trade, String> {
}
