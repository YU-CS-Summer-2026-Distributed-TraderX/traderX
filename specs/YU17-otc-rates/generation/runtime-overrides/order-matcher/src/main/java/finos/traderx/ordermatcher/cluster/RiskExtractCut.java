package finos.traderx.ordermatcher.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /**
     * Bumped only on an incompatible column change; the reader refuses anything else. Schema 3
     * (YU17 phase 2, ADR-065): the contracts section gains the option wrapper — {@code productType},
     * {@code expiryEpochDay}, {@code exerciseStyle} — so a swaption rides beside a swap. A swap
     * carries {@code 0,0,0} there. Schema 2 (YU17, ADR-064): a second SECTION appends, introduced
     * by the {@code #contracts} marker line, carrying the OTC contracts booked as of N. No position
     * column has ever changed — the netted section above the marker is byte-identical to what
     * schema 1 rendered for the same state.
     */
    static final int SCHEMA = 3;

    static final String HEADER = "accountId,security,quantity,avgCostTicks,contractMultiplier,lastTradePxTicks";

    /**
     * Section marker for the OTC contracts. A marker line inside ONE cut rather than a second
     * message, because the cut is one artifact stamped with one {@code cutSha256} (D3): two
     * messages could be delivered apart, hashed apart and stored apart, which is precisely the
     * "consistent at two instants" failure a consensus-sequenced cut exists to rule out.
     */
    static final String CONTRACTS_MARKER = "#contracts";

    static final String CONTRACTS_HEADER = "contractId,accountId,payFixed,notional,fixedRateTicks,"
        + "conventionIndex,effectiveEpochDay,maturityEpochDay,productType,expiryEpochDay,exerciseStyle";

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
                         final String[] tickerById, final IntToLongFunction multiplierOf,
                         final List<long[]> contractTuples) {
        return new String(renderBytes(seq, sessionDateEpochDay, priceVersion, positionTuples, priceTuples,
            tickerById, multiplierOf, contractTuples, ""), StandardCharsets.US_ASCII);
    }

    /**
     * The same cut as {@link #render}, byte for byte, written straight into one exactly-sized US-ASCII
     * array. {@code headerSuffix} is appended to the {@code #cut} line before its newline (YU18's run
     * identity fields), so no caller has to splice a copy of the whole cut to extend its header.
     *
     * <p>Why not a StringBuilder (EOD-RISK item 3, measured 2026-10-25): at 1M positions the
     * builder's growth, its toString, the header splice and two getBytes cost ~300 MB of allocation
     * per member per extract — under the members' -Xmx512m that is an OutOfMemoryError on all three
     * members at once — and the comparator sort of boxed rows was ~60% of the 450 ms. This writes
     * the bytes once (two passes: measure, then write) and sorts primitive keys.
     */
    static byte[] renderBytes(final long seq, final long sessionDateEpochDay, final int priceVersion,
                              final List<long[]> positionTuples, final List<long[]> priceTuples,
                              final String[] tickerById, final IntToLongFunction multiplierOf,
                              final List<long[]> contractTuples, final String headerSuffix) {
        final long[] lastPxBySecurity = new long[tickerById.length];
        for (final long[] price : priceTuples) {
            final int securityId = (int) price[0];
            if (securityId >= 0 && securityId < lastPxBySecurity.length) {
                lastPxBySecurity[securityId] = price[1];
            }
        }

        // Rows sorted by (accountId, securityId) as signed values, exactly the old comparator's
        // order: the account in the high word, the security with its sign bit flipped in the low.
        final int n = positionTuples.size();
        final long[] keys = new long[n];
        final int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            final long[] row = positionTuples.get(i);
            if (row[0] != (int) row[0] || row[1] != (int) row[1]) {
                throw new IllegalStateException("risk extract: position key out of int range: "
                    + row[0] + "," + row[1]);
            }
            keys[i] = (row[0] << 32) | (((int) row[1] ^ Integer.MIN_VALUE) & 0xFFFFFFFFL);
            order[i] = i;
        }
        argsort(keys, order);

        final byte[][] tickerBytes = new byte[tickerById.length][];
        final long[] multiplierBySecurity = new long[tickerById.length];
        final byte[] header = ("#cut schema=" + SCHEMA + " seq=" + seq
            + " sessionDateEpochDay=" + sessionDateEpochDay + " priceVersion=" + priceVersion
            + " rows=" + n + " contracts=" + contractTuples.size() + headerSuffix + "\n"
            + HEADER + "\n").getBytes(StandardCharsets.US_ASCII);
        final byte[] contractsHead = (CONTRACTS_MARKER + "\n" + CONTRACTS_HEADER + "\n")
            .getBytes(StandardCharsets.US_ASCII);

        // Pass 1: validate and measure.
        long length = header.length + contractsHead.length;
        for (int i = 0; i < n; i++) {
            final long[] row = positionTuples.get(order[i]);
            final int securityId = (int) row[1];
            if (securityId < 0 || securityId >= tickerById.length || tickerById[securityId] == null) {
                throw new IllegalStateException(
                    "risk extract: position on unregistered security id " + securityId);
            }
            if (tickerBytes[securityId] == null) {
                tickerBytes[securityId] = tickerById[securityId].getBytes(StandardCharsets.US_ASCII);
                final long multiplier = multiplierOf.applyAsLong(securityId);
                multiplierBySecurity[securityId] = multiplier == 0L ? 1L : multiplier;
            }
            length += width(row[0]) + tickerBytes[securityId].length + width(row[2]) + width(row[3])
                + width(multiplierBySecurity[securityId]) + width(lastPxBySecurity[securityId]) + 6;
        }
        // Booking order, which is ascending contractId (the id IS the booking's consensus
        // sequence), so this needs no sort to be deterministic — but assert it rather than trust
        // it: an out-of-order store would render a cut two members could still agree on while
        // being wrong about which contract is which.
        long previousId = 0L;
        for (final long[] contract : contractTuples) {
            if (contract.length != 11) {
                throw new IllegalStateException(
                    "risk extract: contract tuple has " + contract.length + " columns, want 11");
            }
            if (contract[0] <= previousId) {
                throw new IllegalStateException("risk extract: contract ids are not ascending ("
                    + contract[0] + " after " + previousId + ")");
            }
            previousId = contract[0];
            for (final long v : contract) {
                length += width(v) + 1;
            }
        }
        if (length > Integer.MAX_VALUE - 8) {
            throw new IllegalStateException("risk extract: cut of " + length + " bytes exceeds one array");
        }

        // Pass 2: write.
        final byte[] out = new byte[(int) length];
        System.arraycopy(header, 0, out, 0, header.length);
        int at = header.length;
        for (int i = 0; i < n; i++) {
            final long[] row = positionTuples.get(order[i]);
            final int securityId = (int) row[1];
            at = put(out, at, row[0]);
            out[at++] = ',';
            System.arraycopy(tickerBytes[securityId], 0, out, at, tickerBytes[securityId].length);
            at += tickerBytes[securityId].length;
            out[at++] = ',';
            at = put(out, at, row[2]);
            out[at++] = ',';
            at = put(out, at, row[3]);
            out[at++] = ',';
            at = put(out, at, multiplierBySecurity[securityId]);
            out[at++] = ',';
            at = put(out, at, lastPxBySecurity[securityId]);
            out[at++] = '\n';
        }
        // The contracts section is ALWAYS emitted, even at zero contracts. A consumer that finds no
        // section cannot tell "this portfolio has no swaps" from "the producer that renders them is
        // an older build" — and those are opposite facts about the same file.
        System.arraycopy(contractsHead, 0, out, at, contractsHead.length);
        at += contractsHead.length;
        for (final long[] contract : contractTuples) {
            for (int i = 0; i < contract.length; i++) {
                at = put(out, at, contract[i]);
                out[at++] = (byte) (i == contract.length - 1 ? '\n' : ',');
            }
        }
        if (at != out.length) {
            throw new IllegalStateException("risk extract: measured " + out.length + " bytes, wrote " + at);
        }
        return out;
    }

    /** Characters {@link Long#toString(long)} produces for {@code v}. */
    static int width(final long v) {
        if (v == Long.MIN_VALUE) {
            return 20;
        }
        long a = Math.abs(v);
        int w = v < 0 ? 2 : 1;
        while (a >= 10) {
            a /= 10;
            w++;
        }
        return w;
    }

    /** Write {@link Long#toString(long)} of {@code v} at {@code at}; returns the next index. */
    private static int put(final byte[] out, final int at, final long v) {
        if (v == Long.MIN_VALUE) {
            final byte[] min = Long.toString(v).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(min, 0, out, at, min.length);
            return at + min.length;
        }
        final int end = at + width(v);
        long a = Math.abs(v);
        int i = end;
        do {
            out[--i] = (byte) ('0' + (a % 10));
            a /= 10;
        } while (a != 0);
        if (v < 0) {
            out[--i] = '-';
        }
        return end;
    }

    /** Sort {@code order} by {@code keys[order[i]]} ascending (keys are unique), bottom-up merge. */
    private static void argsort(final long[] keys, final int[] order) {
        final int n = order.length;
        int[] src = order;
        int[] dst = new int[n];
        for (int width = 1; width < n; width <<= 1) {
            for (int lo = 0; lo < n; lo += width << 1) {
                final int mid = Math.min(lo + width, n), hi = Math.min(lo + (width << 1), n);
                int i = lo, j = mid, k = lo;
                while (i < mid && j < hi) {
                    dst[k++] = keys[src[i]] <= keys[src[j]] ? src[i++] : src[j++];
                }
                while (i < mid) {
                    dst[k++] = src[i++];
                }
                while (j < hi) {
                    dst[k++] = src[j++];
                }
            }
            final int[] t = src;
            src = dst;
            dst = t;
        }
        if (src != order) {
            System.arraycopy(src, 0, order, 0, n);
        }
    }

    /** Lowercase hex SHA-256 of the rendered cut — the cross-member identity check. */
    static String sha256(final String rendered) {
        return sha256(rendered.getBytes(StandardCharsets.US_ASCII));
    }

    /** {@link #sha256(String)} of the cut's bytes, without copying them first. */
    static String sha256(final byte[] rendered) {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(rendered);
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
