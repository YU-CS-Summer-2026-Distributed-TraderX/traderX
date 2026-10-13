package finos.traderx.tradeprocessor.service;

import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Run the same rollback, restart and concurrent-writer contracts on the deployed SQL engine. */
@Tag("integration")
@Testcontainers
class SettlementMariaDbIT extends SettlementServiceTest {
    @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>(
            "mariadb@sha256:f2f5e00999bd335343e09091e27e39c9386eb1822efcd0e0bac40b0003fa47e3")
            .withCommand("--lower-case-table-names=1");
    @Override DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    }
}
