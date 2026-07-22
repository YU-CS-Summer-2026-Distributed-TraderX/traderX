package finos.traderx.ordermatcher.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntToLongFunction;

/**
 * Canonical serialisation of the position cut taken at a consensus sequence N (YU15, ADR-055).
 *
 * <p>This is a pure function of replicated state, so every member that applies the extract marker
 * renders byte-identical text and any replay to the same sequence renders it again. That property
 * is the whole point: it is what lets the downstream fixture be byte-reproducible forever
 * (FR-RXT10), and it is verified directly by comparing {@link #sha256} across members.
 *
 * <p>Determinism rules, all deliberate:
 * <ul>
 *   <li>rows sorted by {@code (accountId, securityId)} — never map/probe iteration order, which is
 *       only <em>incidentally</em> identical across members;</li>
 *   <li>fixed column order, no timestamps, no wall-clock, no locale-sensitive formatting
 *       ({@code Long.toString} only — no {@code String.format}, no {@code BigDecimal});</li>
 *   <li>integer ticks throughout ({@link finos.traderx.ordermatcher.lmax.Px#SCALE}), so no float
 *       rounding can differ between JVMs;</li>
 *   <li>{@code \n} line endings, US-ASCII bytes.</li>
 * </ul>
 *
 * <p>Prices are carried as the engine's last-trade price at N. The mark a row is finally valued at
 * is chosen downstream (ADR-056) — the cut states what the state machine knows, not what the
 * valuation policy is.
 */
final class RiskExtractCut {

    /** Bumped only on an incompatible column change; the reader refuses anything else. */
    static final int SCHEMA = 1;

    static final String HEADER = "accountId,security,quantity,avgCostTicks,contractMultiplier,lastTradePxTicks";

    private RiskExtractCut() {
    }

    /**
     * Render the cut. {@code positionTuples} and {@code priceTuples} are the engine's own
     * {@code {accountId, securityId, quantity, avgCostTicks}} and {@code {securityId, ticks}}
     * shapes; {@code multiplierOf} is the YU14 contract multiplier by security id.
     *
     * @throws IllegalStateException if a held security has no registered ticker — a position
     *     against an unnameable instrument must never be silently dropped from a risk extract.
     */
    static String render(final long seq, final long sessionDateEpochDay, final int priceVersion,
                         final List<long[]> positionTuples, final List<long[]> priceTuples,
                         final String[] tickerById, final IntToLongFunction multiplierOf) {
        final long[] lastPxBySecurity = new long[tickerById.length];
        for (final long[] price : priceTuples) {
            final int securityId = (int) price[0];
            if (securityId >= 0 && securityId < lastPxBySecurity.length) {
                lastPxBySecurity[securityId] = price[1];
            }
        }

        final List<long[]> rows = new ArrayList<>(positionTuples);
        rows.sort(Comparator.<long[]>comparingLong(r -> r[0]).thenComparingLong(r -> r[1]));

        final StringBuilder sb = new StringBuilder(64 + rows.size() * 64);
        sb.append("#cut schema=").append(SCHEMA)
            .append(" seq=").append(seq)
            .append(" sessionDateEpochDay=").append(sessionDateEpochDay)
            .append(" priceVersion=").append(priceVersion)
            .append(" rows=").append(rows.size())
            .append('\n');
        sb.append(HEADER).append('\n');
        for (final long[] row : rows) {
            final int securityId = (int) row[1];
            final String ticker = securityId >= 0 && securityId < tickerById.length
                ? tickerById[securityId] : null;
            if (ticker == null) {
                throw new IllegalStateException(
                    "risk extract: position on unregistered security id " + securityId);
            }
            final long multiplier = multiplierOf.applyAsLong(securityId);
            sb.append(row[0]).append(',')
                .append(ticker).append(',')
                .append(row[2]).append(',')
                .append(row[3]).append(',')
                .append(multiplier == 0L ? 1L : multiplier).append(',')
                .append(lastPxBySecurity[securityId]).append('\n');
        }
        return sb.toString();
    }

    /** Lowercase hex SHA-256 of the rendered cut — the cross-member identity check. */
    static String sha256(final String rendered) {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                .digest(rendered.getBytes(StandardCharsets.US_ASCII));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        final StringBuilder hex = new StringBuilder(64);
        for (final byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
