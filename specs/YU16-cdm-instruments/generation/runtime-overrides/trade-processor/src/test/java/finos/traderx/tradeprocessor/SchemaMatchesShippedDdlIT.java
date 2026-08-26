package finos.traderx.tradeprocessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves that a database missing schema this service reads STOPS the service instead of being
 * discovered one rejected query at a time.
 *
 * <p><b>Why this exists.</b> Schema ships in the {@code database-init-sql} configmap. Its
 * {@code 001-initialSchema.sql} key runs only against an EMPTY data directory, so a long-lived
 * volume silently diverges from what the pack declares. That divergence has bitten twice: the
 * {@code orderbook.status} CHECK constraint, and {@code orderbook.traceid} — which rejected 271
 * order writes and surfaced as a failing proof about session phases, two subsystems away from the
 * cause. Both needed a human to run the statement by hand.
 *
 * <p><b>What makes it a real check.</b> The schema under test is read from the shipped configmap
 * itself, not from a copy that could drift and not from Hibernate's own {@code create} output —
 * validating against a schema Hibernate generated would be a tautology. The {@code ddl-auto} value
 * is read from the production {@code application.properties} rather than hardcoded, so reverting
 * that setting to {@code none} fails the missing-column arm instead of quietly disarming it.
 *
 * <p>Both arms run: one boots the real application against the shipped schema and expects it up,
 * the other removes a single shipped column and expects the boot to be refused naming that column.
 */
@Tag("integration")
@Testcontainers
class SchemaMatchesShippedDdlIT {

  /** The shipped schema, a sibling of this module in the generated tree. */
  private static final Path CONFIGMAP =
      Path.of("../kubernetes-runtime/manifests/base/database-init-configmap.yaml");

  private static final Path PRODUCTION_PROPERTIES =
      Path.of("src/main/resources/application.properties");

  /** The column the second incident turned on; dropped and restored by the failing arm. */
  private static final String DRIFTED_COLUMN = "traceid";

  @Container
  static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4");

  @Container
  static final GenericContainer<?> NATS =
      new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine")).withExposedPorts(4222);

  private static String productionDdlAuto;

  @BeforeAll
  static void loadTheShippedSchema() throws Exception {
    final String initial = sqlFromConfigMap("001-initialSchema.sql");
    final String migrations = sqlFromConfigMap("900-migrations.sql");

    // Self-test the probe before arming it: an extractor that silently returned the wrong text
    // would leave the database empty and BOTH arms would then "fail on a missing column" for a
    // reason that has nothing to do with drift.
    assertThat(initial).contains("CREATE TABLE orderbook");
    assertThat(migrations).contains(DRIFTED_COLUMN);

    // Fresh-volume order, exactly as the database pod's entrypoint runs the two keys.
    execute(initial);
    execute(migrations);

    final Properties p = new Properties();
    try (InputStream in = Files.newInputStream(PRODUCTION_PROPERTIES)) {
      p.load(in);
    }
    productionDdlAuto = p.getProperty("spring.jpa.hibernate.ddl-auto");
  }

  @Test
  void productionValidatesItsSchemaAtStartup() {
    assertThat(productionDdlAuto)
        .as("trade-processor must validate its mappings at startup; 'none' is what let the "
            + "orderbook.traceid drift reach production undetected")
        .isEqualTo("validate");
  }

  @Test
  void theServiceStartsAgainstTheShippedSchema() {
    try (ConfigurableApplicationContext context = boot()) {
      assertThat(context.isRunning()).isTrue();
    }
  }

  @Test
  void theServiceRefusesToStartWhenAShippedColumnIsMissing() throws SQLException {
    execute("ALTER TABLE orderbook DROP COLUMN " + DRIFTED_COLUMN);
    try {
      assertThatThrownBy(this::boot)
          .as("a database missing a column the entities read must stop startup, not be "
              + "discovered per-query")
          .hasStackTraceContaining(DRIFTED_COLUMN)
          .hasStackTraceContaining("orderbook");
    } finally {
      execute("ALTER TABLE orderbook ADD COLUMN IF NOT EXISTS " + DRIFTED_COLUMN + " VARCHAR(32)");
    }
  }

  /** Boots the real application; only infrastructure endpoints are redirected at the containers. */
  private ConfigurableApplicationContext boot() {
    // Passed as command-line ARGS, not builder.properties(): the latter becomes Spring's
    // defaultProperties, the LOWEST-precedence source, which application.properties then overrides
    // -- and src/main/test/resources/application.properties shadows the production one on the test
    // classpath, so these redirects would silently not apply.
    return new SpringApplicationBuilder(TradeProcessorApplication.class)
        .run(
            "--spring.datasource.url=" + DB.getJdbcUrl(),
            "--spring.datasource.username=" + DB.getUsername(),
            "--spring.datasource.password=" + DB.getPassword(),
            "--spring.datasource.driverClassName=org.mariadb.jdbc.Driver",
            "--spring.jpa.database-platform=org.hibernate.dialect.MariaDBDialect",
            "--spring.data.jpa.database-platform=org.hibernate.dialect.MariaDBDialect",
            // Deliberately the shipped value, not a literal: see the class comment.
            "--spring.jpa.hibernate.ddl-auto=" + productionDdlAuto,
            "--nats.address=nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222),
            "--server.port=0");
  }

  private static void execute(final String sql) throws SQLException {
    final String url =
        DB.getJdbcUrl() + (DB.getJdbcUrl().contains("?") ? "&" : "?") + "allowMultiQueries=true";
    try (Connection connection = DriverManager.getConnection(url, DB.getUsername(), DB.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /** Reads one block-scalar key out of the configmap, so the test can never drift from it. */
  private static String sqlFromConfigMap(final String key) throws IOException {
    final List<String> lines = Files.readAllLines(CONFIGMAP);
    final StringBuilder sql = new StringBuilder();
    boolean inBlock = false;
    for (final String line : lines) {
      if (line.equals("  " + key + ": |")) {
        inBlock = true;
        continue;
      }
      if (inBlock) {
        if (!line.isBlank() && !line.startsWith("    ")) {
          break;
        }
        sql.append(line.length() > 4 ? line.substring(4) : "").append('\n');
      }
    }
    if (sql.isEmpty()) {
      throw new IllegalStateException(
          "no '" + key + "' block found in " + CONFIGMAP.toAbsolutePath()
              + " — the configmap layout changed and this test is no longer reading shipped DDL");
    }
    return sql.toString();
  }
}
