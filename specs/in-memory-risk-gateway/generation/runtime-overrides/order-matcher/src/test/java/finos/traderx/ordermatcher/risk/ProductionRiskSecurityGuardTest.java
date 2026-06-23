package finos.traderx.ordermatcher.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionRiskSecurityGuardTest {
    @Test
    void productionRequiresAuthenticatedTlsAndProtectedStorage() {
        assertThrows(IllegalStateException.class,
            () -> new ProductionRiskSecurityGuard("production", "nats://localhost:4222", false).verify());
        assertThrows(IllegalStateException.class,
            () -> new ProductionRiskSecurityGuard("production", "nats://user:pass@host:4222", true).verify());
        assertDoesNotThrow(
            () -> new ProductionRiskSecurityGuard("production", "tls://user:pass@host:4222", true).verify());
        assertDoesNotThrow(
            () -> new ProductionRiskSecurityGuard("demo", "nats://localhost:4222", false).verify());
    }
}
