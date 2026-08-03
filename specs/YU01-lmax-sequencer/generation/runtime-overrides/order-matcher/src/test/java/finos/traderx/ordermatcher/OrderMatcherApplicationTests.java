package finos.traderx.ordermatcher;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// state YU01: context test runs broker-free (no-op publisher, pricing subscriber off,
// journal off) so the suite is green without runtime infrastructure; messaging behavior
// is covered by the state smoke tests against the real NATS broker.
@SpringBootTest(properties = {
    // MySQL compat mode (not PostgreSQL): the projector's batch upserts use MariaDB dialect
    // (INSERT IGNORE / ON DUPLICATE KEY UPDATE) since the read-model DB moved off Postgres.
    "spring.datasource.url=jdbc:h2:mem:ordermatcher;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=sa",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    // Use the entity's @Table/@Column names verbatim. Spring Boot's default physical naming
    // strategy splits camel humps, so @Table(name = "OrderBook") becomes order_book — while the
    // projector writes to `orderbook`, the name the deployed MariaDB schema actually uses
    // (mariadb-init: CREATE TABLE orderbook). Without this the flush fails with
    // Table "ORDERBOOK" not found, and because the flush is one transaction the trades and
    // positions roll back with it, so nothing is projected at all. TRADES/POSITIONS have no
    // camel hump, which is why orderbook was the only statement that failed.
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
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
