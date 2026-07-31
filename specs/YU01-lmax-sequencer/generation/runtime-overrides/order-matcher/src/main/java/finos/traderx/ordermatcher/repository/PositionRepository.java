package finos.traderx.ordermatcher.repository;

import finos.traderx.ordermatcher.model.Position;
import finos.traderx.ordermatcher.model.PositionID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Read-model writer + warm-start source for net positions (POSITIONS). State 009b: the BLP is
 * the single position writer (FR-09B10); the Projector persists results and the engine warms
 * the in-memory PositionBook from here at startup (FR-09B16 recovery parity).
 */
@Repository
public interface PositionRepository extends JpaRepository<Position, PositionID> {
}
