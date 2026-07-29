package finos.traderx.tradeprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import finos.traderx.messaging.Publisher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts the real trade-processor Spring context against a REAL MariaDB and a REAL NATS broker.
 *
 * <p><b>Why this exists.</b> The inherited {@code TradeProcessorApplicationTests.contextLoads()}
 * could never run here: the {@code tradePublisher} bean dials {@code nats.address} during bean
 * creation and there is no flag to disable it, so the context cannot start without a broker. That
 * test sat in the unit tier passing by never running (no {@code sourceSets} override, so Gradle
 * reported NO-SOURCE). Rather than leave the wiring unverified, this covers the same property —
 * and more, because it asserts against infrastructure of the type production actually uses instead
 * of an in-memory substitute.
 *
 * <p>Tagged {@code integration} and bound to the {@code integrationTest} task so the
 * infrastructure-free unit job stays Docker-free — the same split as the template's
 * {@code TradeProcessorPersistenceIT}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class TradeProcessorContextIT {

  @Container
  static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4");

  @Container
  static final GenericContainer<?> NATS =
      new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine")).withExposedPorts(4222);

  @DynamicPropertySource
  static void wireContainers(final DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DB::getJdbcUrl);
    registry.add("spring.datasource.username", DB::getUsername);
    registry.add("spring.datasource.password", DB::getPassword);
    registry.add("spring.datasource.driverClassName", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MariaDBDialect");
    // "create", NOT "create-drop": Testcontainers stops the DB before Spring's shutdown hook runs,
    // so a drop-on-shutdown spends 30s timing out against a container that is already gone. The
    // container is discarded either way, so there is nothing to clean up.
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    registry.add("nats.address",
        () -> "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
    registry.add("server.port", () -> "0");
  }

  @Autowired private ApplicationContext context;

  @Test
  void contextStartsAgainstRealMariaDbAndNats() {
    assertThat(context).isNotNull();
  }

  @Test
  void theNatsPublisherThatBlockedTheInheritedSmokeTestIsWired() {
    // This is the exact bean whose construction-time dial made contextLoads() impossible without a
    // broker. Asserting it is present proves the wiring works rather than merely that nothing threw.
    assertThat(context.getBeanNamesForType(Publisher.class)).isNotEmpty();
  }
}
