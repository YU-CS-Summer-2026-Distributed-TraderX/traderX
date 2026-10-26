package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntToLongFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EOD-RISK item 3: the byte renderer must produce EXACTLY the bytes the StringBuilder renderer did —
 * a past cut re-created by replay is compared to its stored SHA-256, so one changed byte breaks
 * FR-RXT10 for every cut ever taken — while allocating a fraction of it.
 */
class RiskExtractCutBytesTest {

    @Test
    void bytesEqualTheStringBuilderRendererOnRandomBooks() {
        final SplittableRandom r = new SplittableRandom(7);
        for (int trial = 0; trial < 200; trial++) {
            final int securities = 1 + r.nextInt(40);
            final String[] tickers = new String[securities];
            for (int i = 0; i < securities; i++) {
                tickers[i] = i % 7 == 3 ? "AAPL260116C00190000" : "S" + i;
            }
            final List<long[]> positions = new ArrayList<>();
            final java.util.Set<Long> seen = new java.util.HashSet<>();
            final int rows = r.nextInt(300);
            while (positions.size() < rows) {
                final long acct = r.nextInt(5) == 0 ? -r.nextInt(1000) : r.nextInt(1_000_000);
                final long sec = r.nextInt(securities);
                if (seen.add(acct * 1_000_003L + sec)) {
                    positions.add(new long[] {acct, sec, r.nextLong(-1_000_000_000L, 1_000_000_000L),
                        r.nextInt(3) == 0 ? 0L : r.nextLong(Long.MIN_VALUE / 4, Long.MAX_VALUE / 4)});
                }
            }
            final List<long[]> prices = new ArrayList<>();
            for (int s = 0; s < securities; s++) {
                if (r.nextBoolean()) {
                    prices.add(new long[] {s, r.nextLong(0, 10_000_000_000L)});
                }
            }
            final List<long[]> contracts = new ArrayList<>();
            long id = 0;
            for (int c = r.nextInt(5); c > 0; c--) {
                id += 1 + r.nextInt(1000);
                final long[] t = new long[11];
                t[0] = id;
                for (int k = 1; k < 11; k++) {
                    t[k] = r.nextLong(-5_000_000_000L, 5_000_000_000L);
                }
                contracts.add(t);
            }
            final IntToLongFunction mult = s -> s % 3 == 0 ? 0L : 100L * s;
            final long seq = r.nextLong(1, Long.MAX_VALUE);
            final String suffix = trial % 2 == 0 ? ""
                : " runDescriptorHash=ab12 projectionScope=p eventIdScheme=epoch-v1";
            final byte[] got = RiskExtractCut.renderBytes(seq, 20_000 + trial, trial, positions, prices, tickers,
                mult, contracts, suffix);
            assertArrayEquals(reference(seq, 20_000 + trial, trial, positions, prices, tickers, mult, contracts,
                suffix).getBytes(StandardCharsets.US_ASCII), got, "trial " + trial);
            assertEquals(RiskExtractCut.sha256(new String(got, StandardCharsets.US_ASCII)),
                RiskExtractCut.sha256(got));
        }
    }

    @Test
    void extremesRenderLikeLongToString() {
        for (final long v : new long[] {0, 9, 10, -1, -10, 99, 100, Long.MAX_VALUE, Long.MIN_VALUE,
            Long.MIN_VALUE + 1}) {
            assertEquals(Long.toString(v).length(), RiskExtractCut.width(v), "width " + v);
            final List<long[]> pos = List.<long[]>of(new long[] {1, 0, v, v});
            final List<long[]> px = List.<long[]>of(new long[] {0, v});
            assertEquals(reference(1, 2, 3, pos, px, new String[] {"X"}, s -> v, List.of(), ""),
                new String(RiskExtractCut.renderBytes(1, 2, 3, pos, px, new String[] {"X"}, s -> v, List.of(), ""),
                    StandardCharsets.US_ASCII));
        }
    }

    @Test
    void unregisteredSecurityStillRefuses() {
        assertThrows(IllegalStateException.class, () -> RiskExtractCut.renderBytes(1, 2, 3,
            List.<long[]>of(new long[] {1, 5, 10, 1}), List.of(), new String[] {"X"}, s -> 1, List.of(), ""));
        assertThrows(IllegalStateException.class, () -> RiskExtractCut.renderBytes(1, 2, 3,
            List.<long[]>of(new long[] {1, 0, 10, 1}), List.of(), new String[] {null}, s -> 1, List.of(), ""));
    }

    /** Measured and guarded: a 1M-position render allocates its output plus the sort index, not 5x it. */
    @Test
    void millionPositionRenderAllocatesLittleBeyondItsOutput() {
        final int n = 1_000_000;
        final String[] tickers = new String[5000];
        final List<long[]> prices = new ArrayList<>();
        for (int i = 0; i < tickers.length; i++) {
            tickers[i] = "SYM" + i;
            prices.add(new long[] {i, 1_000_000L + i});
        }
        final List<long[]> positions = new ArrayList<>(n);
        final SplittableRandom r = new SplittableRandom(42);
        for (int i = 0; i < n; i++) {
            positions.add(new long[] {i / 20 + 1, i % 20 * 250 + r.nextInt(250), r.nextInt(1, 1000),
                1_000_000L + r.nextInt(100_000_000)});
        }
        java.util.Collections.shuffle(positions, new java.util.Random(1));
        final com.sun.management.ThreadMXBean mx =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int rep = 0; rep < 3; rep++) {
            final long a0 = mx.getCurrentThreadAllocatedBytes();
            final long t0 = System.nanoTime();
            final byte[] cut = RiskExtractCut.renderBytes(1, 20000, 1, positions, prices, tickers, s -> 1L,
                List.of(), "");
            final long t1 = System.nanoTime();
            final String sha = RiskExtractCut.sha256(cut);
            final long t2 = System.nanoTime();
            final long alloc = mx.getCurrentThreadAllocatedBytes() - a0;
            System.out.printf("RISK-EXTRACT-RENDER n=%d rep=%d renderMs=%.1f shaMs=%.1f allocMB=%d bytes=%d sha=%s%n",
                n, rep, (t1 - t0) / 1e6, (t2 - t1) / 1e6, alloc >> 20, cut.length, sha.substring(0, 12));
            // output + keys (8n) + two order arrays (8n) + slack; the StringBuilder path was ~5x output
            assertTrue(alloc < cut.length + 16L * n + (8L << 20), "allocated " + alloc + " for " + cut.length);
        }
    }

    /** The pre-2026-10-25 StringBuilder renderer, verbatim, plus YU18's header splice: the oracle. */
    private static String reference(final long seq, final long sessionDateEpochDay, final int priceVersion,
                                    final List<long[]> positionTuples, final List<long[]> priceTuples,
                                    final String[] tickerById, final IntToLongFunction multiplierOf,
                                    final List<long[]> contractTuples, final String suffix) {
        final long[] lastPxBySecurity = new long[tickerById.length];
        for (final long[] price : priceTuples) {
            final int securityId = (int) price[0];
            if (securityId >= 0 && securityId < lastPxBySecurity.length) {
                lastPxBySecurity[securityId] = price[1];
            }
        }
        final List<long[]> rows = new ArrayList<>(positionTuples);
        rows.sort(Comparator.<long[]>comparingLong(r -> r[0]).thenComparingLong(r -> r[1]));
        final StringBuilder sb = new StringBuilder();
        sb.append("#cut schema=").append(RiskExtractCut.SCHEMA).append(" seq=").append(seq)
            .append(" sessionDateEpochDay=").append(sessionDateEpochDay).append(" priceVersion=")
            .append(priceVersion).append(" rows=").append(rows.size()).append(" contracts=")
            .append(contractTuples.size()).append('\n');
        sb.append(RiskExtractCut.HEADER).append('\n');
        for (final long[] row : rows) {
            final int securityId = (int) row[1];
            final long multiplier = multiplierOf.applyAsLong(securityId);
            sb.append(row[0]).append(',').append(tickerById[securityId]).append(',').append(row[2]).append(',')
                .append(row[3]).append(',').append(multiplier == 0L ? 1L : multiplier).append(',')
                .append(lastPxBySecurity[securityId]).append('\n');
        }
        sb.append(RiskExtractCut.CONTRACTS_MARKER).append('\n').append(RiskExtractCut.CONTRACTS_HEADER).append('\n');
        for (final long[] contract : contractTuples) {
            for (int i = 0; i < contract.length; i++) {
                sb.append(contract[i]).append(i == contract.length - 1 ? '\n' : ',');
            }
        }
        String cut = sb.toString();
        if (!suffix.isEmpty()) {
            final int newline = cut.indexOf('\n');
            cut = cut.substring(0, newline) + suffix + cut.substring(newline);
        }
        return cut;
    }
}
