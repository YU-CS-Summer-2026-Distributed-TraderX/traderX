package finos.traderx.ordermatcher.cluster;

import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-GMR-11: the gateway driver's idle strategy, by {@code GATEWAY_IDLE_STRATEGY}. */
class GatewayDriverIdleTest {

    @Test
    void unsetIsLowparkAndBackoffKeepsAeronsDefault() {
        for (final String unset : new String[] {null, "", "  "}) {
            final BackoffIdleStrategy s = assertInstanceOf(BackoffIdleStrategy.class,
                ClusterGatewayMain.driverIdleStrategy(unset));
            assertTrue(s.toString().endsWith("maxParkPeriodNs=1000}"), "1 us max park, not Aeron's 1 ms: " + s);
        }
        assertNull(ClusterGatewayMain.driverIdleStrategy("backoff"), "today's driver: Aeron's own default");
        assertNull(ClusterGatewayMain.driverIdleStrategy(" BACKOFF "));
    }

    @Test
    void theMembersNamesParseAndAnUnknownOneIsRefused() {
        assertInstanceOf(BackoffIdleStrategy.class, ClusterGatewayMain.driverIdleStrategy("LowPark"));
        assertInstanceOf(YieldingIdleStrategy.class, ClusterGatewayMain.driverIdleStrategy("yielding"));
        assertInstanceOf(NoOpIdleStrategy.class, ClusterGatewayMain.driverIdleStrategy("busyspin"));
        assertThrows(IllegalArgumentException.class, () -> ClusterGatewayMain.driverIdleStrategy("spin"));
    }
}
