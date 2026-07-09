package finos.traderx.positionservice.eod;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * YU06 (eod-price-production, NFR-EOD05): idempotent writer of the consumer's marks into
 * {@code eod_position_pnl}. Keyed by {@code (session_date, version, account_id, security)} and
 * upserted, so durable at-least-once redelivery of the same {@code EOD_PRICES_READY} event
 * re-computes identical rows — a safe no-op — rather than duplicating or double-counting.
 */
@Repository
public class EodPnlRepository {

    public record Row(LocalDate sessionDate, int version, int accountId, String security,
                      int quantity, BigDecimal closingPrice, BigDecimal marketValue, long markedAtMillis) { }

    private final JdbcTemplate jdbc;

    public EodPnlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertAll(List<Row> rows) {
        for (Row r : rows) {
            jdbc.update(
                "INSERT INTO eod_position_pnl "
                    + "(session_date, version, account_id, security, quantity, closing_price, market_value, marked_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), "
                    + "closing_price = VALUES(closing_price), market_value = VALUES(market_value), "
                    + "marked_at = VALUES(marked_at)",
                r.sessionDate(), r.version(), r.accountId(), r.security(), r.quantity(),
                r.closingPrice(), r.marketValue(), new Timestamp(r.markedAtMillis()));
        }
    }
}
