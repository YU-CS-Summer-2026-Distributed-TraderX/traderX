package finos.traderx.accountservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finos.traderx.accountservice.model.Account;
import finos.traderx.accountservice.service.AccountService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * CROSS-SERVICE INTEGRATION TEST — the account-service transactional outbox (ADR-021) against a
 * REAL MariaDB (Testcontainers) running the actual deployed DDL and the actual deployed server
 * flags.
 *
 * <p><b>Why this exists.</b> {@code AccountOutboxAtomicityTest} covers the same code path with
 * {@code @MockitoBean AccountControlOutboxRepository}. A transactional outbox exists to guarantee
 * that the business write and the outbox row commit as one unit; with the repository mocked, that
 * guarantee is the one thing the test cannot observe — it asserts the repository was <em>called</em>
 * and that Spring rolled back an H2 transaction around a stub. It would still pass if the two
 * writes ran on different connections, in different transactions, or against a database that never
 * saw them. This test asserts the invariant where it actually lives: in the database.
 *
 * <p>Every assertion below reads through an <b>independent JDBC connection</b>, opened outside the
 * application's pool and outside its transaction. That is the whole point — reading back through
 * the same connection that did the writing proves nothing about what was committed.
 *
 * <p><b>Each case was falsified before it was trusted</b>, since a test that has never failed is a
 * test whose failure mode is unknown. Removing {@code @Transactional} from {@code upsertAccount}
 * fails only {@link #aRejectedOutboxInsertRollsBackTheAccountsWriteInTheDatabase()}; making the
 * outbox insert {@code REQUIRES_NEW} — the classic way an outbox quietly stops being atomic — fails
 * only {@link #neitherWriteIsVisibleUntilTheSharedTransactionCommits()}. The two guard different
 * regressions and neither is redundant.
 *
 * <p>Tagged {@code integration} and bound to the {@code integrationTest} task so the
 * infrastructure-free unit job stays Docker-free — the same split as the trade-processor's
 * {@code TradeProcessorPersistenceIT} and {@code TradeProcessorContextIT}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class AccountOutboxAtomicityIT {

  /**
   * {@code --lower-case-table-names=1} is NOT a test convenience: it is copied from the deployed
   * database container's args in database-deployment.yaml, and the account path depends on it.
   * The deployed DDL creates {@code accounts} while {@link
   * finos.traderx.accountservice.repository.AccountRepository} queries {@code Accounts}, and Linux
   * MariaDB is case-sensitive on table names by default. Running the container the way the cluster
   * runs it is what makes this test exercise the deployed configuration rather than a friendlier
   * one.
   */
  @Container
  static final MariaDBContainer<?> DB =
      new MariaDBContainer<>("mariadb:11.4")
          .withCommand("--lower-case-table-names=1")
          .withInitScript("deployed-account-schema.sql");

  @DynamicPropertySource
  static void wireContainer(final DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DB::getJdbcUrl);
    registry.add("spring.datasource.username", DB::getUsername);
    registry.add("spring.datasource.password", DB::getPassword);
    registry.add("spring.datasource.driverClassName", () -> "org.mariadb.jdbc.Driver");
    // The schema comes from the init script above (the deployed DDL). Spring's own schema.sql
    // initialisation is embedded-only and would not run here anyway; pinned off so it can never
    // quietly shadow the deployed shape this test is here to check.
    registry.add("spring.sql.init.mode", () -> "never");
    // Keeps the scheduled poller from firing: it would try to reach a NATS broker that does not
    // exist here. The publisher connects lazily, so nothing dials on startup either.
    registry.add("outbox.publisher.enabled", () -> "false");
    registry.add("server.port", () -> "0");
  }

  @Autowired private AccountService accountService;
  @Autowired private PlatformTransactionManager transactionManager;

  private static Account account(final int id, final String displayName) {
    Account account = new Account();
    account.setId(id);
    account.setDisplayName(displayName);
    return account;
  }

  // --- reads on a connection the application knows nothing about -----------------------------

  private static Connection independentConnection() throws SQLException {
    return java.sql.DriverManager.getConnection(
        DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
  }

  private static int countRows(final String sql, final int accountId) {
    try (Connection connection = independentConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, accountId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("independent read failed", ex);
    }
  }

  private static int committedAccounts(final int accountId) {
    return countRows("select count(*) from accounts where id = ?", accountId);
  }

  private static int committedOutboxRows(final int accountId) {
    return countRows("select count(*) from account_control_outbox where account_id = ?", accountId);
  }

  private static void execute(final String ddl) {
    try (Connection connection = independentConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(ddl);
    } catch (SQLException ex) {
      throw new IllegalStateException("ddl failed: " + ddl, ex);
    }
  }

  // --- the invariant ---------------------------------------------------------------------------

  @Test
  void bothRowsAreCommittedTogether() throws SQLException {
    accountService.upsertAccount(account(930001, "Committed Together"));

    assertThat(committedAccounts(930001)).isEqualTo(1);
    assertThat(committedOutboxRows(930001)).isEqualTo(1);

    // Also proves the AUTO_INCREMENT / RETURN_GENERATED_KEYS path in recordChange works against
    // the real driver and the real BIGINT key column, not just against H2.
    try (Connection connection = independentConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select version, display_name, published from account_control_outbox "
                    + "where account_id = ?")) {
      statement.setInt(1, 930001);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong("version")).isPositive();
        assertThat(rs.getString("display_name")).isEqualTo("Committed Together");
        assertThat(rs.getBoolean("published")).isFalse();
      }
    }
  }

  @Test
  void neitherWriteIsVisibleUntilTheSharedTransactionCommits() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              accountService.upsertAccount(account(930002, "Mid Flight"));

              // Both writes have happened on the application's connection, and that transaction is
              // still open. An outside reader must see NEITHER row. If the accounts write had its
              // own transaction — a separate connection, or an autocommit escape — it would already
              // be visible here, and the outbox would no longer be atomic with it.
              assertThat(committedAccounts(930002)).isZero();
              assertThat(committedOutboxRows(930002)).isZero();
            });

    // ...and after the commit, both appear. Together the two halves pin the writes to one
    // transaction boundary rather than to two that merely happen to succeed in sequence.
    assertThat(committedAccounts(930002)).isEqualTo(1);
    assertThat(committedOutboxRows(930002)).isEqualTo(1);
  }

  @Test
  void aRejectedOutboxInsertRollsBackTheAccountsWriteInTheDatabase() {
    // A real, database-enforced rejection of the outbox insert only — a CHECK constraint, not a
    // stubbed repository. The accounts write has already succeeded on the connection by the time
    // this fires, so the accounts row can only disappear if the database itself rolled it back.
    execute(
        "ALTER TABLE account_control_outbox ADD CONSTRAINT ck_outbox_it_reject "
            + "CHECK (display_name <> 'REJECT ME')");
    try {
      assertThatThrownBy(() -> accountService.upsertAccount(account(930003, "REJECT ME")))
          // Names the constraint deliberately. Asserting only DataAccessException would let this
          // test pass on ANY data-access failure -- including one that never reached the outbox
          // insert, in which case the empty accounts table below would prove nothing.
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_outbox_it_reject");

      assertThat(committedAccounts(930003)).isZero();
      assertThat(committedOutboxRows(930003)).isZero();
    } finally {
      execute("ALTER TABLE account_control_outbox DROP CONSTRAINT ck_outbox_it_reject");
    }
  }
}
