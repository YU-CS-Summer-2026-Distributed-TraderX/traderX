package finos.traderx.tradeprocessor.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * YU17 (ADR-069 rule 2): resolves what "the previous close" means, once, server-side.
 *
 * <p><b>Rule 2 verbatim, because the whole value of this class is that exactly one place decides
 * it:</b> the previous close is the <b>latest {@code PUBLISHED} version of the most recent
 * {@code session_date} strictly earlier than the opening date</b>.
 *
 * <p>Both halves are load-bearing and both were arrived at from measured data on this rig:
 *
 * <ul>
 *   <li><b>Never a DRAFT.</b> DRAFTs carry known-bad marks — one measured DRAFT reported
 *       {@code flagged_count=1} where the PUBLISHED version that superseded it reported {@code 0}.
 *       Note where the {@code status} filter sits in the query below: inside the aggregate, so
 *       {@code MAX(version)} is the highest <i>published</i> version rather than the highest
 *       version that happens to be published. A date with v1 PUBLISHED and a later v2 DRAFT
 *       resolves to v1 — a hierarchy that silently preferred the DRAFT would look identical from
 *       every price it produced.</li>
 *   <li><b>Never a same-day cut.</b> {@code session_date < ?}, strictly. Sessions on this rig are
 *       cut on demand — one date carried 50 versions — so a proof run that cuts a close at 23:54
 *       would otherwise become the open for the session already in progress.</li>
 * </ul>
 *
 * <p>"Most recent session_date" means the most recent one that <i>has</i> a published version, not
 * the most recent one that exists. The alternative reading — resolve the newest date, then find
 * nothing published on it — would let a single all-DRAFT date shadow every real close behind it
 * and silently drop the whole feature back to the static seed, which is the exact failure this
 * ADR's trap is about.
 *
 * <p>Separate from {@link EodPriceSnapshotRepository#priorPublishedClose} on purpose: that one is
 * per-security and takes the most recent published row for <i>each</i> instrument independently
 * (the right baseline for the SPIKE check, and it can straddle several dates). This resolves ONE
 * session, which is what an opening price has to come from.
 */
@Repository
public class EodPreviousSessionRepository {

    private final JdbcTemplate jdbc;

    public EodPreviousSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The {@code (session_date, version)} rule 2 resolves to, or empty if nothing qualifies. */
    public record SessionRef(LocalDate sessionDate, int version) { }

    public Optional<SessionRef> resolvePrevious(LocalDate openingDate) {
        List<SessionRef> rows = jdbc.query(
            "SELECT session_date, MAX(version) AS version FROM eod_price_session "
                + "WHERE status = 'PUBLISHED' AND session_date < ? "
                + "GROUP BY session_date ORDER BY session_date DESC LIMIT 1",
            EodPreviousSessionRepository::mapRef, openingDate);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static SessionRef mapRef(ResultSet rs, int rowNum) throws SQLException {
        return new SessionRef(rs.getDate("session_date").toLocalDate(), rs.getInt("version"));
    }
}
