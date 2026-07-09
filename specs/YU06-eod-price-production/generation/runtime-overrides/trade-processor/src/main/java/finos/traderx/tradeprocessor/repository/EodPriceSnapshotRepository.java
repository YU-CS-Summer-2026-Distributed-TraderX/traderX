package finos.traderx.tradeprocessor.repository;

import finos.traderx.tradeprocessor.model.EodPrice;
import finos.traderx.tradeprocessor.model.EodQuality;
import finos.traderx.tradeprocessor.model.EodReport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * YU06 (eod-price-production, ADR-026, FR-EOD20): versioned, append-only access to the
 * {@code eod_price_session} header and {@code eod_price_snapshot} rows. Published price rows are
 * never updated — a correction writes a new version (see {@code EodPriceService.override}). The
 * only mutation is the header's {@code DRAFT -> PUBLISHED} transition (the priced values are
 * immutable).
 */
@Repository
public class EodPriceSnapshotRepository {

    private final JdbcTemplate jdbc;

    public EodPriceSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<EodPrice> PRICE_MAPPER = (rs, i) -> new EodPrice(
        rs.getString("security"),
        rs.getBigDecimal("closing_price"),
        EodQuality.valueOf(rs.getString("quality")),
        (Long) rs.getObject("source_tick_millis"),
        rs.getString("override_reason"));

    /** Highest existing version for a date, or empty if none produced yet. */
    public Optional<Integer> latestVersion(LocalDate date) {
        Integer v = jdbc.queryForObject(
            "SELECT MAX(version) FROM eod_price_session WHERE session_date = ?",
            Integer.class, date);
        return Optional.ofNullable(v);
    }

    /** Next version to write for a date (1-based). */
    public int nextVersion(LocalDate date) {
        return latestVersion(date).orElse(0) + 1;
    }

    public Optional<EodReport> findLatest(LocalDate date) {
        return latestVersion(date).flatMap(v -> find(date, v));
    }

    public Optional<EodReport> find(LocalDate date, int version) {
        List<String> status = jdbc.query(
            "SELECT status FROM eod_price_session WHERE session_date = ? AND version = ?",
            (rs, i) -> rs.getString("status"), date, version);
        if (status.isEmpty()) {
            return Optional.empty();
        }
        List<EodPrice> prices = jdbc.query(
            "SELECT security, closing_price, quality, source_tick_millis, override_reason "
                + "FROM eod_price_snapshot WHERE session_date = ? AND version = ? ORDER BY security",
            PRICE_MAPPER, date, version);
        return Optional.of(EodReport.of(date, version, status.get(0), prices));
    }

    /**
     * Persist a new DRAFT version: the header plus every priced instrument, in one transaction.
     * Callers pass a version from {@link #nextVersion}; the report's status is written as-is
     * (always {@code DRAFT} at write time — publication is a separate step via {@link #markPublished}).
     */
    @Transactional
    public void write(EodReport report) {
        jdbc.update(
            "INSERT INTO eod_price_session "
                + "(session_date, version, status, instrument_count, flagged_count, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            report.sessionDate(), report.version(), report.status(),
            report.instrumentCount(), report.flaggedCount(), new Timestamp(System.currentTimeMillis()));
        for (EodPrice p : report.instruments()) {
            jdbc.update(
                "INSERT INTO eod_price_snapshot "
                    + "(session_date, version, security, closing_price, quality, source_tick_millis, override_reason) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                report.sessionDate(), report.version(), p.security(), p.closingPrice(),
                p.quality().name(), p.sourceTickMillis(), p.overrideReason());
        }
    }

    /** DRAFT -> PUBLISHED for a version's header. The priced rows are untouched (immutable). */
    public void markPublished(LocalDate date, int version, long publishedAtMillis) {
        jdbc.update(
            "UPDATE eod_price_session SET status = 'PUBLISHED', published_at = ? "
                + "WHERE session_date = ? AND version = ?",
            new Timestamp(publishedAtMillis), date, version);
    }

    /**
     * The most recent PUBLISHED, non-null closing price for a security strictly before {@code date}
     * — the baseline the SPIKE check compares against (FR-EOD11). Empty if the instrument has never
     * had a published close (first session → no spike baseline, treated as not-a-spike).
     */
    public Optional<BigDecimal> priorPublishedClose(String security, LocalDate date) {
        List<BigDecimal> rows = jdbc.query(
            "SELECT s.closing_price FROM eod_price_snapshot s "
                + "JOIN eod_price_session h ON h.session_date = s.session_date AND h.version = s.version "
                + "WHERE s.security = ? AND h.status = 'PUBLISHED' AND s.session_date < ? "
                + "AND s.closing_price IS NOT NULL "
                + "ORDER BY s.session_date DESC, s.version DESC LIMIT 1",
            (rs, i) -> rs.getBigDecimal("closing_price"), security, date);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
