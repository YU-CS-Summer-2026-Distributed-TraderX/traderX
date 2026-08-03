package finos.traderx.positionservice.eod;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The EOD snapshot read and P&L write, against a REAL MariaDB running the ACTUAL deployed DDL.
 *
 * <p><b>The schema is not copied, it is read.</b> Every statement executed here is extracted at run
 * time from {@code database-init-configmap.yaml} — the one place the deployed schema exists (the
 * per-service {@code schema.sql} files are H2/test-only). A hand-copied {@code .sql} fixture would
 * pass forever after the deployed DDL moved underneath it, which is the failure mode a schema test
 * is supposed to catch. Both keys run in filename order, exactly as MariaDB runs them from
 * {@code docker-entrypoint-initdb.d}, so the migrations file is exercised too.
 *
 * <p><b>What a mock cannot reach here.</b> The two properties under test live in the database, not
 * in application code:
 * <ul>
 *   <li>The reader must return exactly the {@code (session_date, version)} it was asked for. Every
 *       overnight job pricing the same portfolio depends on it — a reader that quietly fell back to
 *       the newest version would make two jobs disagree about a closing price while both looked
 *       correct.</li>
 *   <li>The writer's idempotency is not in its Java at all: it is the {@code ON DUPLICATE KEY}
 *       clause meeting the table's PRIMARY KEY. Against a mocked JdbcTemplate the assertion is that
 *       {@code update} was called, which holds whether or not the key exists — and if that key ever
 *       leaves the DDL, redelivery silently duplicates rows instead of being a safe no-op.</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
class EodSnapshotAndPnlIT {

    private static final LocalDate SESSION = LocalDate.of(2026, 7, 8);

    /**
     * {@code --lower-case-table-names=1} mirrors the deployed server flag (database-deployment.yaml).
     * Running without it is a different database: identifier case sensitivity differs between the
     * container default and Linux deployments, so a schema that works here could fail there.
     */
    @Container
    static final MariaDBContainer<?> DB =
        new MariaDBContainer<>("mariadb:11.4").withCommand("--lower-case-table-names=1");

    private static JdbcTemplate jdbc;
    private EodPriceSnapshotReader reader;
    private EodPnlRepository pnl;

    @BeforeAll
    static void applyDeployedSchema() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
            DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        ds.setDriverClassName("org.mariadb.jdbc.Driver");
        jdbc = new JdbcTemplate((DataSource) ds);
        for (String statement : deployedSchemaStatements()) {
            jdbc.execute(statement);
        }
    }

    /**
     * Pulls every {@code *.sql} value out of the ConfigMap's {@code data:} block, in key order, and
     * splits it into statements. The file has no procedures or DELIMITER blocks, so splitting on
     * ';' is safe — asserted below rather than assumed, because a future trigger would break this
     * quietly and the schema would be applied in fragments.
     */
    private static List<String> deployedSchemaStatements() throws IOException {
        Path configmap = Path.of("..", "kubernetes-runtime", "manifests", "base",
            "database-init-configmap.yaml");
        if (!Files.exists(configmap)) {
            // A missing schema must FAIL, never skip: a skipped schema test reports success having
            // verified nothing, which is indistinguishable from the schema being correct.
            throw new IllegalStateException("deployed schema ConfigMap not found at "
                + configmap.toAbsolutePath() + " — run pipeline/generate-state.sh first");
        }
        List<String> lines = Files.readAllLines(configmap, StandardCharsets.UTF_8);
        StringBuilder sql = new StringBuilder();
        boolean inBlock = false;
        for (String line : lines) {
            if (line.matches("^ {2}[\\w.\\-]+\\.sql: \\|\\s*$")) {
                inBlock = true;
                continue;
            }
            if (inBlock) {
                if (line.isBlank()) {
                    sql.append('\n');
                } else if (line.startsWith("    ")) {
                    sql.append(line.substring(4)).append('\n');
                } else {
                    inBlock = false;
                }
            }
        }
        String body = sql.toString();
        assertThat(body).as("deployed DDL was located and de-indented").contains("eod_position_pnl");
        assertThat(body.toUpperCase())
            .as("naive ';' splitting is only safe while the DDL has no procedural blocks")
            .doesNotContain("DELIMITER").doesNotContain("CREATE TRIGGER").doesNotContain("CREATE PROCEDURE");

        // Strip comments BEFORE splitting, not after. A line comment in this DDL contains a
        // semicolon, so splitting first cuts the comment in half and the orphaned tail -- no longer
        // starting with "--" -- survives stripping and is prepended to the next CREATE TABLE.
        // Observed exactly that: "needed here so the\\n\\nCREATE TABLE account_control_outbox ...".
        String withoutComments = body.replaceAll("(?m)--.*$", "");
        List<String> statements = new ArrayList<>();
        for (String raw : withoutComments.split(";")) {
            String stripped = raw.trim();
            if (!stripped.isEmpty()) {
                statements.add(stripped);
            }
        }
        return statements;
    }

    @BeforeEach
    void freshTables() {
        jdbc.execute("DELETE FROM eod_position_pnl");
        jdbc.execute("DELETE FROM eod_price_snapshot");
        jdbc.execute("DELETE FROM eod_price_session");
        reader = new EodPriceSnapshotReader(jdbc);
        pnl = new EodPnlRepository(jdbc);
    }

    private void seedSession(LocalDate date, int version) {
        jdbc.update("INSERT INTO eod_price_session (session_date, version, status, instrument_count,"
            + " flagged_count) VALUES (?, ?, 'PUBLISHED', 0, 0)", date, version);
    }

    private void seedPrice(LocalDate date, int version, String security, String price, String quality) {
        jdbc.update("INSERT INTO eod_price_snapshot (session_date, version, security, closing_price,"
            + " quality) VALUES (?, ?, ?, ?, ?)", date, version, security, new BigDecimal(price), quality);
    }

    // ---------- the reader: exactly the named (date, version) ----------

    @Test
    void readsTheNamedVersionRatherThanTheNewestOne() {
        seedSession(SESSION, 1);
        seedSession(SESSION, 2);
        seedPrice(SESSION, 1, "AAPL", "150.000000", "OK");
        seedPrice(SESSION, 2, "AAPL", "175.500000", "OK");

        assertThat(reader.read(SESSION, 1).get("AAPL").closingPrice())
            .isEqualByComparingTo("150.000000");
        assertThat(reader.read(SESSION, 2).get("AAPL").closingPrice())
            .isEqualByComparingTo("175.500000");
    }

    @Test
    void readsOnlyTheNamedSessionDate() {
        LocalDate other = SESSION.minusDays(1);
        seedSession(SESSION, 1);
        seedSession(other, 1);
        seedPrice(SESSION, 1, "AAPL", "150.000000", "OK");
        seedPrice(other, 1, "IBM", "99.000000", "OK");

        assertThat(reader.read(SESSION, 1)).containsOnlyKeys("AAPL");
        assertThat(reader.read(other, 1)).containsOnlyKeys("IBM");
    }

    /**
     * An absent version must read EMPTY, never fall back to a version that does exist. A silent
     * fallback would price the portfolio off a cut nobody named, and every row would look valid.
     */
    @Test
    void anAbsentVersionReadsEmptyRatherThanFallingBack() {
        seedSession(SESSION, 1);
        seedPrice(SESSION, 1, "AAPL", "150.000000", "OK");

        assertThat(reader.read(SESSION, 99)).isEmpty();
    }

    /** DECIMAL(18,6): the sixth decimal place survives the round trip rather than being rounded. */
    @Test
    void closingPriceKeepsFullDecimalScale() {
        seedSession(SESSION, 1);
        seedPrice(SESSION, 1, "AAPL", "123.456789", "OK");

        assertThat(reader.read(SESSION, 1).get("AAPL").closingPrice())
            .isEqualByComparingTo(new BigDecimal("123.456789"));
    }

    /** The quality flag drives the consumer's fail-safe halt, so it must survive the read. */
    @Test
    void carriesTheQualityFlagThroughUnchanged() {
        seedSession(SESSION, 1);
        seedPrice(SESSION, 1, "AAPL", "150.000000", "STALE");

        assertThat(reader.read(SESSION, 1).get("AAPL").quality()).isEqualTo("STALE");
    }

    // ---------- the writer: idempotent under redelivery ----------

    private EodPnlRepository.Row row(int account, String security, int qty, String price, String value) {
        return new EodPnlRepository.Row(SESSION, 1, account, security, qty,
            new BigDecimal(price), new BigDecimal(value), 1_754_000_000_000L);
    }

    private Map<String, Object> onlyPnlRow() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM eod_position_pnl");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /**
     * The at-least-once property. JetStream can redeliver the same EOD_PRICES_READY event, so the
     * consumer reprocesses and writes the identical rows again; that must be a no-op, not a
     * duplicate. This is the test that fails if the PRIMARY KEY ever leaves the deployed DDL —
     * ON DUPLICATE KEY has nothing to collide with and every redelivery appends.
     */
    @Test
    void reprocessingTheSameSessionDoesNotDuplicateRows() {
        List<EodPnlRepository.Row> rows = List.of(row(44044, "AAPL", 100, "150.000000", "15000.000000"));

        pnl.upsertAll(rows);
        pnl.upsertAll(rows);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eod_position_pnl", Integer.class)).isEqualTo(1);
    }

    @Test
    void areprocessWithNewMarksUpdatesInPlace() {
        pnl.upsertAll(List.of(row(44044, "AAPL", 100, "150.000000", "15000.000000")));
        pnl.upsertAll(List.of(row(44044, "AAPL", 120, "151.250000", "18150.000000")));

        Map<String, Object> only = onlyPnlRow();
        assertThat(only.get("quantity")).isEqualTo(120);
        assertThat((BigDecimal) only.get("closing_price")).isEqualByComparingTo("151.250000");
        assertThat((BigDecimal) only.get("market_value")).isEqualByComparingTo("18150.000000");
    }

    /** Version is part of the key, so a re-cut of the same day coexists rather than overwriting. */
    @Test
    void twoVersionsOfTheSameDayCoexist() {
        pnl.upsertAll(List.of(row(44044, "AAPL", 100, "150.000000", "15000.000000")));
        pnl.upsertAll(List.of(new EodPnlRepository.Row(SESSION, 2, 44044, "AAPL", 100,
            new BigDecimal("152.000000"), new BigDecimal("15200.000000"), 1_754_000_000_001L)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eod_position_pnl", Integer.class)).isEqualTo(2);
    }

    /**
     * Short and written-option positions are negative, and so is their market value. An unsigned
     * column or a truncating cast would turn a liability into an asset — silently, and only at
     * month end.
     */
    @Test
    void negativePositionsAndTheirMarketValuePersistAsNegative() {
        pnl.upsertAll(List.of(row(44044, "AAPL  260918C00190000", -5, "2.500000", "-1250.000000")));

        Map<String, Object> only = onlyPnlRow();
        assertThat(only.get("quantity")).isEqualTo(-5);
        assertThat((BigDecimal) only.get("market_value")).isEqualByComparingTo("-1250.000000");
    }

    /** DECIMAL(20,6) on market_value: six places survive on the wide column too. */
    @Test
    void marketValueKeepsFullDecimalScale() {
        pnl.upsertAll(List.of(row(44044, "AAPL", 3, "1.234567", "3.703701")));

        assertThat((BigDecimal) onlyPnlRow().get("market_value")).isEqualByComparingTo("3.703701");
    }

    /** A full-length OCC symbol fits the deployed VARCHAR(32) rather than being truncated. */
    @Test
    void aFullLengthOccSymbolIsStoredWhole() {
        String occ = "AAPL  260918C00190000";
        pnl.upsertAll(List.of(row(44044, occ, 1, "2.500000", "250.000000")));

        assertThat(onlyPnlRow().get("security")).isEqualTo(occ);
    }
}
