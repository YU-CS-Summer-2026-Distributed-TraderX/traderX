package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderRecord;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.model.OrderStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbWarmupReaderTest {
    private JdbcTemplate jdbc;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void createSchema() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:warmup-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setSuppressClose(true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE OrderBook (
              OrderId VARCHAR(32) PRIMARY KEY, AccountId INT NOT NULL, Security VARCHAR(16) NOT NULL,
              Side VARCHAR(16) NOT NULL, Quantity INT NOT NULL, RemainingQuantity INT NOT NULL,
              LimitPrice DECIMAL(18,3) NOT NULL, Status VARCHAR(24) NOT NULL,
              CreatedAt TIMESTAMP NOT NULL, UpdatedAt TIMESTAMP NOT NULL,
              LastExecutionPrice DECIMAL(18,3), LastFillQuantity INT)
            """);
        jdbc.execute("""
            CREATE TABLE POSITIONS (
              ACCOUNTID INT NOT NULL, SECURITY VARCHAR(50) NOT NULL, QUANTITY INT,
              AVERAGECOSTBASIS DECIMAL(18,3), UPDATED TIMESTAMP,
              PRIMARY KEY (ACCOUNTID, SECURITY))
            """);
        jdbc.execute("CREATE TABLE TRADES (ID VARCHAR(100) PRIMARY KEY)");
    }

    @Test
    void streamsMultipleFetchWindowsAndComputesTradeCounter() throws Exception {
        int orderCount = 2_050;
        Instant base = Instant.parse("2026-07-16T00:00:00Z");
        List<Object[]> orders = new ArrayList<>(orderCount);
        for (int i = 1; i <= orderCount; i++) {
            Timestamp timestamp = Timestamp.from(base.plusSeconds(i));
            orders.add(new Object[] {
                "ord-013-%04d".formatted(i), 42, "IBM", "Buy", 10, 10,
                new BigDecimal("100.000"), "NEW", timestamp, timestamp, null, null
            });
        }
        jdbc.batchUpdate("""
            INSERT INTO OrderBook
              (OrderId, AccountId, Security, Side, Quantity, RemainingQuantity, LimitPrice,
               Status, CreatedAt, UpdatedAt, LastExecutionPrice, LastFillQuantity)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, orders);
        jdbc.update("INSERT INTO POSITIONS VALUES (?, ?, ?, ?, ?)",
            42, "IBM", 125, new BigDecimal("99.500"), Timestamp.from(base));
        List<Object[]> trades = new ArrayList<>();
        for (int i = 1; i <= 2_100; i++) {
            trades.add(new Object[] {"trd-09b-" + i});
        }
        trades.add(new Object[] {"foreign-trade-id"});
        jdbc.batchUpdate("INSERT INTO TRADES (ID) VALUES (?)", trades);

        AtomicInteger streamedOrders = new AtomicInteger();
        AtomicInteger streamedPositions = new AtomicInteger();
        AtomicReference<String> firstOrder = new AtomicReference<>();
        DbWarmupReader.Result result = new DbWarmupReader(jdbc, 128).stream(record -> {
            firstOrder.compareAndSet(null, record.getOrderId());
            streamedOrders.incrementAndGet();
        }, ignored -> streamedPositions.incrementAndGet());

        assertEquals(orderCount, streamedOrders.get());
        assertEquals("ord-013-2050", firstOrder.get(), "DB rows must stay newest-first");
        assertEquals(1, streamedPositions.get());
        assertEquals(orderCount, result.orderRows());
        assertEquals(1, result.positionRows());
        assertEquals(2_101, result.tradeRows());
        assertEquals(2_100, result.maxTradeSeq());
        assertTrue(dataSource.getConnection().getAutoCommit(), "pooled connection auto-commit must be restored");
        assertFalse(dataSource.getConnection().isReadOnly(), "pooled connection read-only state must be restored");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM POSITIONS", Integer.class));
    }

    @Test
    void newestFirstBootstrapBoundsTerminalRowsButKeepsOpenOrders() {
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel(2);

        assertTrue(readModel.bootstrapNewestFirst(snapshot(3, OrderStatus.FILLED)));
        assertTrue(readModel.bootstrapNewestFirst(snapshot(2, OrderStatus.CANCELED)));
        assertFalse(readModel.bootstrapNewestFirst(snapshot(1, OrderStatus.REJECTED)));
        assertTrue(readModel.bootstrapNewestFirst(snapshot(4, OrderStatus.NEW)));

        assertNull(readModel.get(1));
        assertEquals(OrderStatus.CANCELED, readModel.get(2).status);
        assertEquals(OrderStatus.FILLED, readModel.get(3).status);
        assertEquals(OrderStatus.NEW, readModel.get(4).status);
        assertEquals(3, readModel.totalOrders());
    }

    private static OrderSnapshot snapshot(int orderRef, OrderStatus status) {
        OrderRecord record = new OrderRecord();
        record.setOrderId(OrderSnapshot.orderIdFor(orderRef));
        record.setAccountId(42);
        record.setSecurity("IBM");
        record.setSide(OrderSide.Buy);
        record.setQuantity(10);
        record.setRemainingQuantity(status == OrderStatus.NEW ? 10 : 0);
        record.setLimitPrice(new BigDecimal("100.000"));
        record.setStatus(status);
        record.setCreatedAt(Instant.parse("2026-07-16T00:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-07-16T00:00:00Z").plusSeconds(orderRef));
        return OrderSnapshot.fromRecord(orderRef, record);
    }
}
