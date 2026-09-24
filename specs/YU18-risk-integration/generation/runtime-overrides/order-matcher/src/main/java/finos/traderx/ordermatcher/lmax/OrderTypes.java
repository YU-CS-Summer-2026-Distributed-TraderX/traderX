package finos.traderx.ordermatcher.lmax;

/**
 * YU18 order-types component (spec {@code components/order-types}, FR-OT01..41): the vocabulary and
 * the STATELESS validation every boundary shares.
 *
 * <p>One table, three callers. REST answers 422 with {@link #reasonText(int)}, FIX answers an
 * ExecutionReport whose Text is the same string, and the engine re-runs {@link #validate} on
 * whatever the log carries (a log entry is data, never trusted) and rejects INVALID. The console
 * mirrors the codes and wording in {@code web-front-end-console/src/app/order-types.ts}.
 *
 * <p>Allocation-free and concat-free: the engine calls {@link #validate} on the apply thread.
 */
public final class OrderTypes {
    private OrderTypes() {
    }

    /** Wire order-type byte. 0 = an untyped (legacy) request, inferred from its price (FR-OT02). */
    public static final byte LEGACY = 0;
    public static final byte MARKET = 1;
    public static final byte LIMIT = 2;
    public static final byte STOP = 3;
    public static final byte STOP_LIMIT = 4;
    public static final byte ICEBERG = 5;
    public static final byte PEGGED = 6;
    public static final byte TRAILING_STOP = 7;

    /** Wire TIF byte. 0 = absent: legacy inference, or the typed default of FR-OT06. */
    public static final byte TIF_NONE = 0;
    public static final byte DAY = 1;
    public static final byte GTC = 2;
    public static final byte IOC = 3;
    public static final byte FOK = 4;

    public static final byte PEG_PRIMARY = 1;
    public static final byte PEG_MIDPOINT = 2;

    public static final byte TRAIL_AMOUNT = 1;
    public static final byte TRAIL_BPS = 2;

    /** FR-OT41: every price, stop, cap, trail and DERIVED price lies in (0, MAX_PRICE_TICKS]. */
    public static final long MAX_PRICE_TICKS = Long.MAX_VALUE / 10_000L;
    public static final int MAX_PEG_OFFSET_TICKS = 10_000;
    public static final int MIN_TRAIL_BPS = 1;
    public static final int MAX_TRAIL_BPS = 5_000;

    /**
     * Consensus limits (review r3 item 1). COMPILE-TIME constants, like
     * {@code InputEvent.REPLAY_ACCOUNT_BASE}: a member holding a different value would compute a
     * different function of the same log. Tests construct engines with smaller limits through
     * {@code MatchingEngine.setOrderTypeLimits}; production never calls it. The values in force are
     * written into every format-11 snapshot and a restore under different values is refused.
     */
    public static final int DEFAULT_PENDING_CAPACITY = 4_096;
    public static final int DEFAULT_PEG_CAPACITY = 1_024;
    /** 0 = no override: the per-step proven bound of FR-OT26 is the only limit. */
    public static final int DEFAULT_ROUND_LIMIT = 0;

    // ----- validation codes (0 = valid). Append-only: the console mirrors them. ----------------
    public static final int OK = 0;
    public static final int BAD_TYPE = 1;
    public static final int BAD_TIF = 2;
    public static final int FIELD_NOT_ALLOWED = 3;
    public static final int MISSING_LIMIT = 4;
    public static final int MISSING_STOP = 5;
    public static final int BAD_PRICE = 6;
    public static final int BAD_QUANTITY = 7;
    public static final int BAD_DISPLAY = 8;
    public static final int BAD_PEG_REFERENCE = 9;
    public static final int BAD_PEG_OFFSET = 10;
    public static final int BAD_TRAIL = 11;
    public static final int LEGACY_NEGATIVE_PRICE = 12;

    private static final String[] REASON_TEXT = {
        "ok",
        "unknown orderType",
        "timeInForce not allowed for this orderType",
        "field not allowed for this orderType",
        "limitPrice required",
        "stopPrice required",
        "price must be positive and within range",
        "quantity must be positive",
        "displayQuantity must be positive and below quantity",
        "pegReference must be PRIMARY or MIDPOINT",
        "pegOffset must not be more aggressive than the reference (buy <= 0, sell >= 0) and within 10000 ticks",
        "exactly one of trailAmount (positive) or trailPercentBps (1-5000) required",
        "negative limitPrice without orderType",
    };

    public static String reasonText(final int code) {
        return code >= 0 && code < REASON_TEXT.length ? REASON_TEXT[code] : "invalid";
    }

    private static final String[] TYPE_NAMES = {
        "LEGACY", "MARKET", "LIMIT", "STOP", "STOP_LIMIT", "ICEBERG", "PEGGED", "TRAILING_STOP"
    };
    private static final String[] TIF_NAMES = { "", "DAY", "GTC", "IOC", "FOK" };

    public static String typeName(final byte type) {
        return type >= 0 && type < TYPE_NAMES.length ? TYPE_NAMES[type] : "LEGACY";
    }

    public static String tifName(final byte tif) {
        return tif >= 0 && tif < TIF_NAMES.length ? TIF_NAMES[tif] : "";
    }

    /** Parse a type name (REST); -1 when unknown. */
    public static byte parseType(final String name) {
        for (byte i = 1; i < TYPE_NAMES.length; i++) {
            if (TYPE_NAMES[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Parse a TIF name (REST); -1 when unknown. */
    public static byte parseTif(final String name) {
        for (byte i = 1; i < TIF_NAMES.length; i++) {
            if (TIF_NAMES[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** FR-OT06 default when a typed request omits TIF. */
    public static byte defaultTif(final byte type) {
        return type == MARKET ? IOC : GTC;
    }

    /** FR-OT06 eligibility matrix. */
    public static boolean tifAllowed(final byte type, final byte tif) {
        switch (type) {
            case MARKET:
                return tif == IOC || tif == FOK;
            case LIMIT:
                return tif == DAY || tif == GTC || tif == IOC || tif == FOK;
            case STOP:
            case STOP_LIMIT:
            case TRAILING_STOP:
            case ICEBERG:
            case PEGGED:
                return tif == DAY || tif == GTC;
            default:
                return false;
        }
    }

    public static boolean rests(final byte type) {
        return type != MARKET;
    }

    public static boolean isTriggered(final byte type) {
        return type == STOP || type == STOP_LIMIT || type == TRAILING_STOP;
    }

    private static boolean priceOk(final long px) {
        return px > 0L && px <= MAX_PRICE_TICKS;
    }

    /**
     * FR-OT03 / FR-OT41 stateless validation of a TYPED order. {@code tif} must already be resolved
     * (a typed request that omitted it has {@link #defaultTif} applied first). A field value of 0
     * means "absent" for every optional field; {@code side} is {@link InputEvent#SIDE_BUY}/SELL.
     * Grid alignment and the collar need the book and are checked by the engine.
     */
    public static int validate(final byte type, final byte tif, final byte side, final int qty,
                               final long limitPx, final long stopPx, final int displayQty,
                               final byte pegRef, final int pegOffset, final byte trailMode,
                               final long trailValue) {
        if (type < MARKET || type > TRAILING_STOP) {
            return BAD_TYPE;
        }
        if (qty <= 0) {
            return BAD_QUANTITY;
        }
        if (!tifAllowed(type, tif)) {
            return BAD_TIF;
        }
        final boolean hasLimit = limitPx != 0L;
        final boolean hasStop = stopPx != 0L;
        final boolean hasDisplay = displayQty != 0;
        final boolean hasPeg = pegRef != 0 || pegOffset != 0;
        final boolean hasTrail = trailMode != 0 || trailValue != 0L;
        switch (type) {
            case MARKET:
                if (hasLimit || hasStop || hasDisplay || hasPeg || hasTrail) {
                    return FIELD_NOT_ALLOWED;
                }
                return OK;
            case LIMIT:
                if (hasStop || hasDisplay || hasPeg || hasTrail) {
                    return FIELD_NOT_ALLOWED;
                }
                if (!hasLimit) {
                    return MISSING_LIMIT;
                }
                return priceOk(limitPx) ? OK : BAD_PRICE;
            case STOP:
                if (hasLimit || hasDisplay || hasPeg || hasTrail) {
                    return FIELD_NOT_ALLOWED;
                }
                if (!hasStop) {
                    return MISSING_STOP;
                }
                return priceOk(stopPx) ? OK : BAD_PRICE;
            case STOP_LIMIT:
                if (hasDisplay || hasPeg || hasTrail) {
                    return FIELD_NOT_ALLOWED;
                }
                if (!hasStop) {
                    return MISSING_STOP;
                }
                if (!hasLimit) {
                    return MISSING_LIMIT;
                }
                return priceOk(stopPx) && priceOk(limitPx) ? OK : BAD_PRICE;
            case ICEBERG:
                if (hasStop || hasPeg || hasTrail) {
                    return FIELD_NOT_ALLOWED;
                }
                if (!hasLimit) {
                    return MISSING_LIMIT;
                }
                if (!priceOk(limitPx)) {
                    return BAD_PRICE;
                }
                return displayQty > 0 && displayQty < qty ? OK : BAD_DISPLAY;
            case PEGGED:
                if (hasStop || hasDisplay || hasTrail) {
                    return FIELD_NOT_ALLOWED;
                }
                if (pegRef != PEG_PRIMARY && pegRef != PEG_MIDPOINT) {
                    return BAD_PEG_REFERENCE;
                }
                if (!hasLimit) {
                    return MISSING_LIMIT;   // the cap is required (FR-OT23)
                }
                if (!priceOk(limitPx)) {
                    return BAD_PRICE;
                }
                if (pegOffset < -MAX_PEG_OFFSET_TICKS || pegOffset > MAX_PEG_OFFSET_TICKS
                    || (side == InputEvent.SIDE_BUY ? pegOffset > 0 : pegOffset < 0)) {
                    return BAD_PEG_OFFSET;
                }
                return OK;
            case TRAILING_STOP:
                if (hasLimit || hasStop || hasDisplay || hasPeg) {
                    return FIELD_NOT_ALLOWED;
                }
                if (trailMode == TRAIL_AMOUNT) {
                    return priceOk(trailValue) ? OK : BAD_TRAIL;
                }
                if (trailMode == TRAIL_BPS) {
                    return trailValue >= MIN_TRAIL_BPS && trailValue <= MAX_TRAIL_BPS ? OK : BAD_TRAIL;
                }
                return BAD_TRAIL;
            default:
                return BAD_TYPE;
        }
    }

    // ----- boundary presence and exact numbers (FR-OT03, review I3). Gateway threads only: the
    // BigDecimal helper allocates, and the engine never calls it. ------------------------------

    public static final int F_LIMIT = 1;
    public static final int F_STOP = 2;
    public static final int F_DISPLAY = 4;
    public static final int F_PEG = 8;
    public static final int F_TRAIL = 16;
    private static final int[] ALLOWED_FIELDS = {
        0, 0, F_LIMIT, F_STOP, F_STOP | F_LIMIT, F_LIMIT | F_DISPLAY, F_LIMIT | F_PEG, F_TRAIL,
    };

    /**
     * FR-OT03 by PRESENCE: a field the type does not use is refused even when it is zero or null,
     * which {@link #validate}'s zero-means-absent convention cannot see. REST and FIX call this
     * before {@link #validate}.
     */
    public static boolean fieldsAllowed(final byte type, final int present) {
        return type >= MARKET && type <= TRAILING_STOP && (present & ~ALLOWED_FIELDS[type]) == 0;
    }

    /** {@link #exactScaled}: the value has more precision than the field carries. */
    public static final long NOT_EXACT = Long.MIN_VALUE;

    /**
     * {@code value x 10^places} exactly, or {@link #NOT_EXACT} when that has a fractional part, so
     * 1.9 bps or 3.8 shares is refused rather than truncated. A magnitude beyond MAX_PRICE_TICKS
     * saturates at +/-(MAX_PRICE_TICKS + 1): every range check downstream refuses it, nothing wraps.
     */
    public static long exactScaled(final java.math.BigDecimal value, final int places) {
        final java.math.BigDecimal scaled = value.movePointRight(places);
        if (scaled.signum() == 0) {
            return 0L;
        }
        if (scaled.stripTrailingZeros().scale() > 0) {
            return NOT_EXACT;
        }
        if (scaled.abs().compareTo(java.math.BigDecimal.valueOf(MAX_PRICE_TICKS)) > 0) {
            return scaled.signum() * (MAX_PRICE_TICKS + 1L);
        }
        return scaled.longValueExact();
    }

    /** A saturated exact value narrowed to int without wrapping (range checks then refuse it). */
    public static int saturatedInt(final long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    // ----- derived prices (FR-OT19, FR-OT23, FR-OT41). Pure integer arithmetic. ----------------

    /** Floor {@code px} to the grid {@code tick} (px >= 0). */
    public static long floorToGrid(final long px, final long tick) {
        return px - Math.floorMod(px, tick);
    }

    /** Ceil {@code px} to the grid {@code tick}; -1 on overflow. */
    public static long ceilToGrid(final long px, final long tick) {
        final long r = Math.floorMod(px, tick);
        if (r == 0L) {
            return px;
        }
        final long up = tick - r;
        return px > Long.MAX_VALUE - up ? -1L : px + up;
    }

    /**
     * FR-OT19 effective trail {@code t}: the amount, or floor(watermark x bps / 10000) Px, raised to
     * at least one grid tick. The watermark is bounded by MAX_PRICE_TICKS and bps by 5000, so the
     * product cannot overflow; multiplyExact makes that an asserted fact rather than a hope.
     */
    public static long effectiveTrail(final byte trailMode, final long trailValue,
                                      final long watermark, final long tick) {
        final long t = trailMode == TRAIL_BPS
            ? Math.multiplyExact(watermark, trailValue) / 10_000L
            : trailValue;
        return Math.max(t, tick);
    }

    /**
     * FR-OT19 stop level, rounded away from the market (sell down, buy up). Returns -1 when the
     * derived stop is outside (0, MAX_PRICE_TICKS] — the caller rejects TRAIL_INVALID.
     */
    public static long trailingStop(final byte side, final byte trailMode, final long trailValue,
                                    final long watermark, final long tick) {
        final long t = effectiveTrail(trailMode, trailValue, watermark, tick);
        if (side == InputEvent.SIDE_SELL) {
            final long raw = watermark - t;
            if (raw <= 0L) {
                return -1L;
            }
            final long stop = floorToGrid(raw, tick);
            return stop > 0L ? stop : -1L;
        }
        if (watermark > MAX_PRICE_TICKS - t) {
            return -1L;
        }
        final long stop = ceilToGrid(watermark + t, tick);
        return stop > 0L && stop <= MAX_PRICE_TICKS ? stop : -1L;
    }
}
