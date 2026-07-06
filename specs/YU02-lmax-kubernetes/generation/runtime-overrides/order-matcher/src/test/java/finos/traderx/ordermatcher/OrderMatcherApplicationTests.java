package finos.traderx.ordermatcher;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// State 009b: context test runs broker-free (no-op publisher, pricing subscriber off,
// journal off) so the suite is green without runtime infrastructure; messaging behavior
// is covered by the state smoke tests against the real NATS broker.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ordermatcher;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=sa",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "order.matcher.publisher=noop",
    "order.matcher.pricing-subscriber.enabled=false",
    "journal.enabled=false"
})
class OrderMatcherApplicationTests {
    @Test
    void contextLoads() {
    }
}
