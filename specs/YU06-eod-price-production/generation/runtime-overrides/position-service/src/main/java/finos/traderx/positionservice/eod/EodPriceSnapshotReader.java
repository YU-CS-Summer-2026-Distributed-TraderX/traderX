package finos.traderx.positionservice.eod;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * YU06 (eod-price-production, FR-EOD31): read-only access to the versioned closing-price snapshot.
 * The consumer reads exactly the {@code (session_date, version)} the {@code EOD_PRICES_READY} event
 * named — never live prices — so every downstream job agrees on the same closing prices (the deck's
 * consistency invariant, NFR-EOD01).
 */
@Repository
public class EodPriceSnapshotReader {

    private final JdbcTemplate jdbc;

    public EodPriceSnapshotReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Closing prices for a snapshot version, keyed by security. Empty map if the version is absent. */
    public Map<String, EodSnapshotPrice> read(LocalDate sessionDate, int version) {
        Map<String, EodSnapshotPrice> bySecurity = new HashMap<>();
        jdbc.query(
            "SELECT security, closing_price, quality FROM eod_price_snapshot "
                + "WHERE session_date = ? AND version = ?",
            rs -> {
                String security = rs.getString("security");
                bySecurity.put(security, new EodSnapshotPrice(
                    security, rs.getBigDecimal("closing_price"), rs.getString("quality")));
            },
            sessionDate, version);
        return bySecurity;
    }
}
