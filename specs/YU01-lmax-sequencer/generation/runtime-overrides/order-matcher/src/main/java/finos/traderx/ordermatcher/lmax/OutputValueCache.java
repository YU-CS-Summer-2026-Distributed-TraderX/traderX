package finos.traderx.ordermatcher.lmax;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Small output-edge value cache for deterministic renderings used by matcher-local
 * publishers/projectors. Misses allocate at the edge; steady-state repeated values do not.
 */
final class OutputValueCache {
    private static final int DEFAULT_ACCOUNT_CAPACITY = 65_536;
    private static final int DEFAULT_TRADE_CAPACITY = 1_048_576;
    private static final int DEFAULT_INTEGER_CAPACITY = 1_048_576;
    private static final int DEFAULT_PRICE_CAPACITY = 16_384;
    private static final int DEFAULT_TIME_CAPACITY = 16_384;

    private final String[] orderIds;
    private final String[] tradeIds;
    private final Integer[] integers;
    private final Long[] longs;
    private final long[] priceKeys;
    private final BigDecimal[] prices;
    private final long[] priceOrZeroKeys;
    private final BigDecimal[] pricesOrZero;
    private final long[] instantKeys;
    private final Instant[] instants;

    OutputValueCache() {
        this(DEFAULT_ACCOUNT_CAPACITY, DEFAULT_TRADE_CAPACITY, DEFAULT_INTEGER_CAPACITY,
            DEFAULT_PRICE_CAPACITY, DEFAULT_TIME_CAPACITY);
    }

    OutputValueCache(int accountCapacity, int tradeCapacity, int integerCapacity, int priceCapacity, int timeCapacity) {
        this.orderIds = new String[accountCapacity];
        this.tradeIds = new String[tradeCapacity];
        this.integers = new Integer[integerCapacity];
        this.longs = new Long[tradeCapacity];
        this.priceKeys = new long[priceCapacity];
        this.prices = new BigDecimal[priceCapacity];
        this.priceOrZeroKeys = new long[priceCapacity];
        this.pricesOrZero = new BigDecimal[priceCapacity];
        this.instantKeys = new long[timeCapacity];
        this.instants = new Instant[timeCapacity];
    }

    String orderIdFor(int orderRef) {
        if (orderRef < 0 || orderRef >= orderIds.length) {
            return OrderSnapshot.orderIdFor(orderRef);
        }
        String id = orderIds[orderRef];
        if (id == null) {
            id = OrderSnapshot.orderIdFor(orderRef);
            orderIds[orderRef] = id;
        }
        return id;
    }

    Integer integerFor(int value) {
        if (value < 0 || value >= integers.length) {
            return Integer.valueOf(value);
        }
        Integer boxed = integers[value];
        if (boxed == null) {
            boxed = Integer.valueOf(value);
            integers[value] = boxed;
        }
        return boxed;
    }

    Long longFor(long value) {
        if (value < 0 || value >= longs.length) {
            return Long.valueOf(value);
        }
        int index = (int) value;
        Long boxed = longs[index];
        if (boxed == null) {
            boxed = Long.valueOf(value);
            longs[index] = boxed;
        }
        return boxed;
    }

    String tradeIdFor(long tradeSeq) {
        if (tradeSeq < 0 || tradeSeq >= tradeIds.length) {
            return OrderSnapshot.tradeIdFor(tradeSeq);
        }
        int index = (int) tradeSeq;
        String id = tradeIds[index];
        if (id == null) {
            id = OrderSnapshot.tradeIdFor(tradeSeq);
            tradeIds[index] = id;
        }
        return id;
    }

    BigDecimal priceFor(long ticks) {
        if (ticks == Px.NONE) {
            return null;
        }
        int slot = slot(ticks, prices.length);
        BigDecimal cached = prices[slot];
        if (cached == null || priceKeys[slot] != ticks) {
            cached = Px.toBigDecimal(ticks);
            priceKeys[slot] = ticks;
            prices[slot] = cached;
        }
        return cached;
    }

    BigDecimal priceOrZeroFor(long ticks) {
        int slot = slot(ticks, pricesOrZero.length);
        BigDecimal cached = pricesOrZero[slot];
        if (cached == null || priceOrZeroKeys[slot] != ticks) {
            cached = Px.toDecimalOrZero(ticks);
            priceOrZeroKeys[slot] = ticks;
            pricesOrZero[slot] = cached;
        }
        return cached;
    }

    Instant instantFor(long epochMillis) {
        int slot = slot(epochMillis, instants.length);
        Instant cached = instants[slot];
        if (cached == null || instantKeys[slot] != epochMillis) {
            cached = Instant.ofEpochMilli(epochMillis);
            instantKeys[slot] = epochMillis;
            instants[slot] = cached;
        }
        return cached;
    }

    private static int slot(long value, int length) {
        return ((int) (value ^ (value >>> 32))) & (length - 1);
    }
}
