package finos.traderx.positionservice.repository;

import finos.traderx.positionservice.model.Trade;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TradeRepository {

  private static final RowMapper<Trade> TRADE_ROW_MAPPER = (rs, rowNum) -> {
    Trade trade = new Trade();
    trade.setProjectionScope(rs.getString("ProjectionScope"));
    trade.setId(rs.getString("ID"));
    trade.setAccountId(rs.getInt("AccountID"));
    trade.setSecurity(rs.getString("Security"));
    trade.setSide(rs.getString("Side"));
    trade.setState(rs.getString("State"));
    trade.setQuantity(rs.getInt("Quantity"));
    BigDecimal price = rs.getBigDecimal("Price");
    trade.setPrice(price);
    trade.setUpdated(rs.getTimestamp("Updated"));
    trade.setCreated(rs.getTimestamp("Created"));
    // YU16 (FR-CDM23): rejection attribution; both columns exist from this state's migration.
    trade.setRejectionReason(rs.getString("RejectionReason"));
    trade.setSourceOrderId(rs.getString("SourceOrderId"));
    return trade;
  };

  private final JdbcTemplate jdbcTemplate;

  public TradeRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<Trade> findAll() {
    return jdbcTemplate.query(
        "select ID, AccountID, Security, Side, State, Quantity, Price, Updated, Created, RejectionReason, SourceOrderId, ProjectionScope from Trades where ProjectionScope=(SELECT projection_scope FROM projection_active WHERE singleton_id=1) order by Updated desc",
        TRADE_ROW_MAPPER
    );
  }

  public List<Trade> findByAccountId(int accountId) {
    return jdbcTemplate.query(
        "select ID, AccountID, Security, Side, State, Quantity, Price, Updated, Created, RejectionReason, SourceOrderId, ProjectionScope from Trades where ProjectionScope=(SELECT projection_scope FROM projection_active WHERE singleton_id=1) and AccountID = ? order by Updated desc",
        TRADE_ROW_MAPPER,
        accountId
    );
  }

  public List<Trade> findByScope(String scope) {
    return jdbcTemplate.query("select ID, AccountID, Security, Side, State, Quantity, Price, Updated, Created, RejectionReason, SourceOrderId, ProjectionScope from Trades where ProjectionScope=? order by Updated desc", TRADE_ROW_MAPPER, scope);
  }
  public List<Trade> findByScopeAndAccount(String scope,int account) {
    return jdbcTemplate.query("select ID, AccountID, Security, Side, State, Quantity, Price, Updated, Created, RejectionReason, SourceOrderId, ProjectionScope from Trades where ProjectionScope=? and AccountID=? order by Updated desc", TRADE_ROW_MAPPER, scope, account);
  }
}
