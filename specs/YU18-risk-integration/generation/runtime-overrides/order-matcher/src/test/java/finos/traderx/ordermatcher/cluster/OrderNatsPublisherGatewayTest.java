package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** FR-FFR-14: the booking event names the submitting gateway; every other event does not. */
class OrderNatsPublisherGatewayTest {
    private final OrderNatsPublisher encoder = new OrderNatsPublisher("", "/orders", "7", null, 16, 1);

    private String encode(final String gateway) {
        final OrderNatsPublisher.Rec r = new OrderNatsPublisher.Rec();
        r.orderRef = 5; r.accountId = 11; r.security = "IBM"; r.quantity = 10; r.remainingQty = 10;
        r.limitPx = 50_000_000L; r.lastExecPx = 50_000_000L; r.gateway = gateway;
        return new String(encoder.encode(new StringBuilder(1024), r), StandardCharsets.UTF_8);
    }

    @Test
    void theBookingEventCarriesTheGatewayAndOthersDoNot() {
        assertTrue(encode("cluster-gateway-7c9").contains("\"gateway\":\"cluster-gateway-7c9\""));
        assertFalse(encode(null).contains("gateway"));
    }

    @Test
    void theOutputEventsPrincipalBytesBecomeTheRecordsName() {
        final finos.traderx.ordermatcher.lmax.OutputEvent o = finos.traderx.ordermatcher.lmax.OutputEvent.newInstance();
        o.orderRef = 5; o.accountId = 11; o.quantity = 10; o.remainingQty = 10;
        o.gateway = "gw-a".getBytes(StandardCharsets.UTF_8);
        final String json = new String(encoder.encode(new StringBuilder(1024), OrderNatsPublisher.from(o, "IBM", 0L, 0)), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"gateway\":\"gw-a\""), json);
        o.gateway = null;
        assertFalse(new String(encoder.encode(new StringBuilder(1024), OrderNatsPublisher.from(o, "IBM", 0L, 0)), StandardCharsets.UTF_8).contains("gateway"));
    }
}
