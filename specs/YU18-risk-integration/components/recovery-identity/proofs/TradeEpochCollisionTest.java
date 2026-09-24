package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import finos.traderx.messaging.Publisher;
import finos.traderx.tradeprocessor.model.*;
import finos.traderx.tradeprocessor.repository.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Diagnostic only: desired fresh-epoch safety assertion MUST FAIL on 66baef75.
 * Real H2 SQL/JPA repositories retained across TradeService reconstruction.
 * Not a live MariaDB/cluster recovery proof. See the paired publisher wire fixture.
 */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"})
@ContextConfiguration(classes=TradeEpochCollisionTest.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class TradeEpochCollisionTest {
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses=Trade.class)
    @EnableJpaRepositories(basePackageClasses=TradeRepository.class)
    static class Config {}

    @Autowired TradeRepository trades;
    @Autowired PositionRepository positions;
    @Autowired PlatformTransactionManager transactions;

    @SuppressWarnings("unchecked")
    private TradeService restartConsumer() {
        return new TradeService(trades, positions, mock(Publisher.class), mock(Publisher.class),
                                1, null, new TransactionTemplate(transactions));
    }
    private TradeOrder event(String id, String sourceOrderId, int account) {
        TradeOrder event = new TradeOrder(id, account, "IBM", TradeSide.Buy, 100);
        event.setPrice(new BigDecimal("136.250000"));
        event.setSourceOrderId(sourceOrderId);
        return event;
    }

    @Test void retainedSameEpochDuplicateIsNotDoubleBooked() {
        restartConsumer().processTrade(event("81-B", "old-81", 1001));
        TradeBookingResult duplicate = restartConsumer().processTrade(event("81-B", "old-81", 1001));
        assertEquals("old-81", duplicate.getTrade().getSourceOrderId());
        assertEquals(100, positions.findByAccountIdAndSecurity(1001, "IBM").getQuantity());
    }

    @Test void differentCounterBooksAgainstRetainedSql() {
        restartConsumer().processTrade(event("82-B", "old-82", 1002));
        restartConsumer().processTrade(event("83-B", "old-83", 1002));
        assertTrue(trades.existsById("82-B"));
        assertTrue(trades.existsById("83-B"));
        assertEquals(200, positions.findByAccountIdAndSecurity(1002, "IBM").getQuantity());
    }

    @Test void freshEpochTradeMustNotBeMistakenForRetainedTrade() {
        restartConsumer().processTrade(event("1-B", "old-1", 1003));
        assertEquals("old-1", trades.findById("1-B").orElseThrow().getSourceOrderId());
        TradeBookingResult fresh = restartConsumer().processTrade(event("1-B", "fresh-1", 1003));
        System.out.println("RI06_COLLISION expectedSource=fresh-1 observedSource="
                           + fresh.getTrade().getSourceOrderId() + " retainedQuantity="
                           + positions.findByAccountIdAndSecurity(1003, "IBM").getQuantity());
        assertEquals("fresh-1", fresh.getTrade().getSourceOrderId(),
                     "Fresh epoch trade was silently returned as the retained old-epoch trade");
    }
}
