package finos.traderx.tradeprocessor.model;

import java.time.LocalDate;
import java.util.List;

/**
 * YU06 (eod-price-production): a produced snapshot version — the {@code eod_price_session} header
 * plus its priced instruments — as returned by the {@code /eod/*} endpoints and persisted by
 * {@code EodPriceSnapshotRepository}. Immutable once {@code PUBLISHED} (a correction produces a new
 * version, never an in-place edit — ADR-026).
 */
public record EodReport(
    LocalDate sessionDate,
    int version,
    String status,
    int instrumentCount,
    int flaggedCount,
    List<EodPrice> instruments) {

    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";

    public static int countFlagged(List<EodPrice> prices) {
        return (int) prices.stream().filter(EodPrice::isFlagged).count();
    }

    /** Report for a version's price list, computing header counts from the prices themselves. */
    public static EodReport of(LocalDate date, int version, String status, List<EodPrice> prices) {
        return new EodReport(date, version, status, prices.size(), countFlagged(prices), prices);
    }
}
