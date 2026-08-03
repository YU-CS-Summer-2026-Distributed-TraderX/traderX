package finos.traderx.tradeprocessor.repository;

import finos.traderx.tradeprocessor.model.OrderRow;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderRow, String> {
  List<OrderRow> findByAccountId(Integer accountId);

  List<OrderRow> findByAccountIdAndStatusIn(Integer accountId, Collection<String> statuses);
}
