package finos.traderx.tradeprocessor.repository;

import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.PositionID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<Position, PositionID> {
  @org.springframework.data.jpa.repository.Query("select p from Position p where p.projectionScope='legacy-unknown' and p.accountId=?1")
  List<Position> findByAccountId(Integer id);
  @org.springframework.data.jpa.repository.Query("select p from Position p where p.projectionScope='legacy-unknown' and p.accountId=?1 and p.security=?2")
  Position findByAccountIdAndSecurity(Integer id, String security);
  Position findByProjectionScopeAndAccountIdAndSecurity(String scope,Integer id,String security);
  List<Position> findByProjectionScope(String scope);
  List<Position> findByProjectionScopeAndAccountId(String scope,Integer id);
}
