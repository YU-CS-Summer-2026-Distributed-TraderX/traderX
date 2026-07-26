package finos.traderx.tradeprocessor.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import finos.traderx.messaging.Publisher;
import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.model.TradeState;
import finos.traderx.tradeprocessor.repository.PositionRepository;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import finos.traderx.tradeprocessor.service.TradeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * CROSS-SERVICE INTEGRATION TEST — the trade-processor persistence seam against a REAL MariaDB
 * (Testcontainers) loaded with the actual deployed schema (deployed-schema.sql, copied verbatim
 * from database-init-configmap.yaml). This is the seam the rest of the platform reads: the rows
 * trade-processor writes here are exactly what position-service serves.
 *
 * <p>Why a real DB and not the mocked unit test: the deployed services run {@code ddl-auto=none}
 * against a schema with tight VARCHAR widths, CHECK constraints, and a {@code trades.accountid ->
 * accounts(id)} foreign key. Those constraints are invisible to a mocked repository but are exactly
 * where this project has shipped silent row drops. The load-bearing assertion is the FK path: an
 * order for an account that does not exist must FAIL LOUDLY at persistence, not vanish.
 *
 * <p>Boots only the JPA slice ({@code @DataJpaTest}) — not the web/pubsub context, whose socket.io
 * subscriber connects on startup — and drives the real {@link TradeService} with mocked publishers.
 */
@Tag("integration")
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TradeProcessorPersistenceIT {

  @Container
  static final MariaDBContainer<?> DB =
      new MariaDBContainer<>("mariadb:11.4").withInitScript("deployed-schema.sql");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DB::getJdbcUrl);
    registry.add("spring.datasource.username", DB::getUsername);
    registry.add("spring.datasource.password", DB::getPassword);
    // Must override the driver too — the baseline properties pin org.h2.Driver, which would reject
    // the mariadb:// URL.
    registry.add("spring.datasource.driverClassName", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MariaDBDialect");
  }

  @Autowired private PositionRepository positionRepository;
  @Autowired private TradeRepository tradeRepository;
  @Autowired private TestEntityManager em;

  private TradeService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    service =
        new TradeService(
            tradeRepository, positionRepository, mock(Publisher.class), mock(Publisher.class));
    // Seed the FK parent. The deployed positions/trades tables both reference accounts(id).
    em.getEntityManager()
        .createNativeQuery("INSERT INTO accounts(id, displayname) VALUES (1, 'Desk 1')")
        .executeUpdate();
    em.flush();
  }

  private static TradeOrder order(int account, String security, TradeSide side, int qty) {
    return new TradeOrder("ord-" + security + "-" + qty, account, security, side, qty);
  }

  @Test
  void buy_persistsPositionAndTrade_againstTheDeployedSchema() {
    service.processTrade(order(1, "AAPL", TradeSide.Buy, 100));
    em.flush();
    em.clear();

    Position position = positionRepository.findByAccountIdAndSecurity(1, "AAPL");
    assertThat(position).isNotNull();
    assertThat(position.getQuantity()).isEqualTo(100);

    List<Trade> trades = tradeRepository.findByAccountId(1);
    assertThat(trades).hasSize(1);
    // The enum columns are VARCHAR(_) CHECK (... in ('Buy','Sell') / ('New'..'Settled')): this
    // asserts the enum->string mapping survives a real round trip through the constrained columns.
    assertThat(trades.get(0).getSide()).isEqualTo(TradeSide.Buy);
    assertThat(trades.get(0).getState()).isEqualTo(TradeState.Settled);
    assertThat(trades.get(0).getSecurity()).isEqualTo("AAPL");
  }

  @Test
  void subsequentTrades_accumulateOntoTheSamePositionRow() {
    service.processTrade(order(1, "AAPL", TradeSide.Buy, 100));
    service.processTrade(order(1, "AAPL", TradeSide.Sell, 30));
    em.flush();
    em.clear();

    assertThat(positionRepository.findByAccountIdAndSecurity(1, "AAPL").getQuantity()).isEqualTo(70);
  }

  @Test
  void orderForUnknownAccount_failsLoudly_ratherThanSilentlyDropping() {
    // Account 999 was never seeded. The accounts FK must reject the write. The value of this test
    // is that the drop is SIGNALLED (an exception), not swallowed into a 0-row no-op.
    assertThatThrownBy(
            () -> {
              service.processTrade(order(999, "AAPL", TradeSide.Buy, 100));
              em.flush();
            })
        .isInstanceOf(Exception.class);
  }
}
