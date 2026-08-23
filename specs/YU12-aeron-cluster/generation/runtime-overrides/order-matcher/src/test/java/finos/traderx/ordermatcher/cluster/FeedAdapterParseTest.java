package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The feed adapter's NATS handler against the bytes price-publisher actually sends. Until
 * 2026-08-23 nothing exercised this path, and the adapter read {@code price} one level above
 * where the house envelope puts it — every tick for five states hit its catch-all and was
 * dropped as "malformed", with nothing printed and nothing on the members to say so
 * (issues/open/the-feed-adapter-parses-the-wrong-level-of-the-pricing-envelope.md).
 *
 * The envelopes below are captured off the wire, not typed from the publisher's source.
 */
class FeedAdapterParseTest {

    /** Captured 2026-08-23 from kind-traderx-yu12-cluster, subject pricing.DFS. */
    private static final String EQUITY_ENVELOPE =
        "{\"topic\":\"pricing.DFS\",\"payload\":{\"ticker\":\"DFS\",\"price\":127.16,\"openPrice\":126.4,"
        + "\"closePrice\":127.65,\"asOf\":\"2026-08-23T17:14:30.178Z\",\"source\":\"snapshot\"},"
        + "\"date\":\"2026-08-23T17:14:30.178Z\",\"from\":\"price-publisher\",\"type\":\"PriceTick\"}";

    /** A Treasury carries six decimals of fraction-of-par (ADR-057) plus YU16's additive fields. */
    private static final String TREASURY_ENVELOPE =
        "{\"topic\":\"pricing.UST-20290715\",\"payload\":{\"ticker\":\"UST-20290715\",\"instrumentKey\":"
        + "\"UST-20290715\",\"price\":0.99813,\"cleanPrice\":0.99813,\"priceSemantics\":\"CLEAN_FRACTION_OF_PAR\","
        + "\"ytmPercent\":4.07,\"yieldConvention\":\"SEMIANNUAL_BOND\"},\"from\":\"price-publisher\",\"type\":\"PriceTick\"}";

    private static long parse(final String json) {
        return FeedAdapterMain.parsePriceTicks(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsThePriceFromInsideTheHouseEnvelope() {
        // The defect: the old handler returned NO_PRICE here and counted it as a malformed tick.
        assertEquals(127_160_000L, parse(EQUITY_ENVELOPE));
    }

    @Test
    void keepsATreasurysSixDecimals() {
        assertEquals(998_130L, parse(TREASURY_ENVELOPE));
    }

    @Test
    void stillAcceptsABareQuoteWithoutTheEnvelope() {
        assertEquals(127_160_000L, parse("{\"ticker\":\"DFS\",\"price\":127.16}"));
    }

    @Test
    void refusesWhatItCannotPriceInsteadOfThrowing() {
        assertEquals(FeedAdapterMain.NO_PRICE, parse("{\"payload\":{\"ticker\":\"DFS\"}}"));
        assertEquals(FeedAdapterMain.NO_PRICE, parse("{\"payload\":{\"price\":\"127.16\"}}"));
        assertEquals(FeedAdapterMain.NO_PRICE, parse("{\"payload\":{\"price\":0}}"));
        assertEquals(FeedAdapterMain.NO_PRICE, parse("not json"));
        assertEquals(FeedAdapterMain.NO_PRICE, parse(""));
    }
}
