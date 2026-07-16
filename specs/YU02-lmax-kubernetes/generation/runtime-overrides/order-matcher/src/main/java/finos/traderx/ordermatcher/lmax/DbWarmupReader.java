package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import finos.traderx.ordermatcher.model.Position;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Streams the persisted matcher read model without attaching rows to a Hibernate persistence
 * context. The connection is placed in a read-only, non-autocommit transaction so PostgreSQL and
 * MariaDB can honor the forward-only fetch size instead of materializing the whole result set.
 */
final class DbWarmupReader {
    private static final Logger log = LoggerFactory.getLogger(DbWarmupReader.class);
    static final int DEFAULT_FETCH_SIZE = 1_000;
    private static final long PROGRESS_INTERVAL = 100_000L;

    private static final String ORDERS_SQL = """
        SELECT OrderId, AccountId, Security, Side, Quantity, RemainingQuantity,
               LimitPrice, Status, CreatedAt, UpdatedAt, LastExecutionPrice, LastFillQuantity
          FROM OrderBook
         ORDER BY UpdatedAt DESC, OrderId DESC
        """;
    private static final String POSITIONS_SQL = """
        SELECT ACCOUNTID, SECURITY, QUANTITY, AVERAGECOSTBASIS, UPDATED
          FROM POSITIONS
         ORDER BY ACCOUNTID, SECURITY
        """;
    private static final String TRADE_IDS_SQL = "SELECT ID FROM TRADES";

    record Result(long orderRows, long positionRows, long tradeRows, long maxTradeSeq) {}

    private final JdbcTemplate jdbcTemplate;
    private final int fetchSize;

    DbWarmupReader(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, DEFAULT_FETCH_SIZE);
    }

    DbWarmupReader(JdbcTemplate jdbcTemplate, int fetchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.fetchSize = Math.max(1, fetchSize);
    }

    Result stream(Consumer<OrderRecord> orderConsumer, Consumer<Position> positionConsumer) {
        Result result = jdbcTemplate.execute((ConnectionCallback<Result>) connection ->
            streamConnection(connection, orderConsumer, positionConsumer));
        if (result == null) {
            throw new IllegalStateException("DB warm-up returned no result");
        }
        return result;
    }

    private Result streamConnection(
        Connection connection,
        Consumer<OrderRecord> orderConsumer,
        Consumer<Position> positionConsumer
    ) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        boolean originalReadOnly = connection.isReadOnly();
        try {
            if (!originalReadOnly) {
                connection.setReadOnly(true);
            }
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }

            long orderRows = streamOrders(connection, orderConsumer);
            long positionRows = streamPositions(connection, positionConsumer);
            TradeScan tradeScan = streamTradeIds(connection);
            return new Result(orderRows, positionRows, tradeScan.rows(), tradeScan.maxTradeSeq());
        } finally {
            // End the read-only cursor transaction before returning the pooled connection.
            if (originalAutoCommit) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            if (!originalReadOnly) {
                connection.setReadOnly(false);
            }
        }
    }

    private long streamOrders(Connection connection, Consumer<OrderRecord> consumer) throws SQLException {
        long rows = 0;
        try (PreparedStatement statement = streamingStatement(connection, ORDERS_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                consumer.accept(orderRecord(resultSet));
                rows++;
                logProgress("orders", rows);
            }
        }
        return rows;
    }

    private long streamPositions(Connection connection, Consumer<Position> consumer) throws SQLException {
        long rows = 0;
        try (PreparedStatement statement = streamingStatement(connection, POSITIONS_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                consumer.accept(position(resultSet));
                rows++;
                logProgress("positions", rows);
            }
        }
        return rows;
    }

    private TradeScan streamTradeIds(Connection connection) throws SQLException {
        long rows = 0;
        long maxTradeSeq = 0;
        try (PreparedStatement statement = streamingStatement(connection, TRADE_IDS_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                maxTradeSeq = Math.max(maxTradeSeq,
                    OrderSnapshot.tradeSeqFromId(resultSet.getString("ID")));
                rows++;
                logProgress("trade ids", rows);
            }
        }
        return new TradeScan(rows, maxTradeSeq);
    }

    private PreparedStatement streamingStatement(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
            sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        statement.setFetchSize(fetchSize);
        return statement;
    }

    private static OrderRecord orderRecord(ResultSet resultSet) throws SQLException {
        OrderRecord record = new OrderRecord();
        record.setOrderId(resultSet.getString("OrderId"));
        record.setAccountId(nullableInt(resultSet, "AccountId"));
        record.setSecurity(resultSet.getString("Security"));
        record.setSide(OrderSide.valueOf(resultSet.getString("Side")));
        record.setQuantity(nullableInt(resultSet, "Quantity"));
        record.setRemainingQuantity(nullableInt(resultSet, "RemainingQuantity"));
        record.setLimitPrice(resultSet.getBigDecimal("LimitPrice"));
        record.setStatus(OrderStatus.valueOf(resultSet.getString("Status")));
        record.setCreatedAt(instant(resultSet.getTimestamp("CreatedAt")));
        record.setUpdatedAt(instant(resultSet.getTimestamp("UpdatedAt")));
        record.setLastExecutionPrice(resultSet.getBigDecimal("LastExecutionPrice"));
        record.setLastFillQuantity(nullableInt(resultSet, "LastFillQuantity"));
        return record;
    }

    private static Position position(ResultSet resultSet) throws SQLException {
        Position position = new Position();
        position.setAccountId(nullableInt(resultSet, "ACCOUNTID"));
        position.setSecurity(resultSet.getString("SECURITY"));
        position.setQuantity(nullableInt(resultSet, "QUANTITY"));
        position.setAverageCostBasis(resultSet.getBigDecimal("AVERAGECOSTBASIS"));
        Timestamp updated = resultSet.getTimestamp("UPDATED");
        position.setUpdated(updated == null ? null : new Date(updated.getTime()));
        return position;
    }

    private static Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void logProgress(String table, long rows) {
        if (rows % PROGRESS_INTERVAL == 0) {
            log.info("DB warm-up streamed {} {}", rows, table);
        }
    }

    private record TradeScan(long rows, long maxTradeSeq) {}
}
