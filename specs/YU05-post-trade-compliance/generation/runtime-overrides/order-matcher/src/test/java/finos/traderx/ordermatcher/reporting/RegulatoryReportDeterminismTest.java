package finos.traderx.ordermatcher.reporting;

import finos.traderx.ordermatcher.api.OrderCreateRequest;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Exact journal-backed regulatory-report reproducibility property (FR-PTC21). */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:regulatory-report;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=sa",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "order.matcher.publisher=noop",
    "order.matcher.pricing-subscriber.enabled=false",
    "order.matcher.seed-enabled=true",
    "order.matcher.trade-service-url=http://localhost:1/trade/",
    "risk.bootstrap.enabled=false",
    "journal.enabled=true",
    "journal.batch.records=1",
    "journal.path=${java.io.tmpdir}/traderx-regulatory-report-${random.uuid}"
})
class RegulatoryReportDeterminismTest {
    @Autowired private OrderMatcherService service;
    @Autowired private LmaxEngine engine;

    @Test
    void frPtc21SameJournalAndRangeProducesIdenticalRecordListTwice() throws Exception {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setClientOrderId("regulatory-determinism-1");
        request.setAccountId(22214);
        request.setSecurity("PARITYA");
        request.setSide(OrderSide.Buy);
        request.setQuantity(100);
        request.setLimitPrice(new BigDecimal("100.000"));
        String orderId = service.createOrder(request).getOrderId();
        service.cancelOrder(orderId);

        List<AuditRecord> first = engine.generateRegulatoryReport(0L, 0L);
        List<AuditRecord> second = engine.generateRegulatoryReport(0L, 0L);

        assertFalse(first.isEmpty(), "fixture must produce reportable journal outputs");
        assertEquals(first, second);
    }
}
