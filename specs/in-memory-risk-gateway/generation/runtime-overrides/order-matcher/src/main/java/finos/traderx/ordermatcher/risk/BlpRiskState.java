package finos.traderx.ordermatcher.risk;

/**
 * Preallocated single-writer authoritative risk state. All methods are called by the BLP thread
 * after bootstrap; no locks, boxing, remote calls, or per-event allocation.
 */
public final class BlpRiskState {
    private static final long EMPTY_KEY = Long.MIN_VALUE;
    private static final long TOMBSTONE_KEY = Long.MIN_VALUE + 1L;
    private static final RiskReason[] REASONS = RiskReason.values();

    private final int[] accountIds;
    private final byte[] accountEnabled;
    private final long[] reservedNotional;
    private final long[] reservedBuyNotional;
    private final long[] reservedSellNotional;
    private final long[] executedNotional;
    private final byte[] securityEnabled;
    private final byte[] securityRestricted;
    private final long[] lastPrice;
    private final long[] lastPriceTime;
    private final long[] idempotencyKeys;
    private final int[] idempotencyOrderRefs;
    private final byte[] idempotencyDecisions;
    private final long[] idempotencyRetentionKeys;
    private final long[] entitlementKeys;
    private final byte[] entitlementEnabled;
    private final long[] reservationNotionalByOrderRef;
    private final int[] reservationQtyByOrderRef;
    private final int[] reservationAccountByOrderRef;
    private final int[] reservationSecurityByOrderRef;
    private final byte[] reservationSideByOrderRef;
    private final long[] exposureKeys;
    private final int[] reservedBuyQtyByExposure;
    private final int[] reservedSellQtyByExposure;
    private final long creditLimitTicks;
    private final int maxOrderQuantity;
    private final long maxOrderNotionalTicks;
    private final long priceMaxAgeMillis;
    private final RiskMetrics metrics;
    private long policyVersion;
    private boolean killSwitch;
    private boolean duplicateReplay;
    private int idempotencyRetentionCursor;
    private int idempotencyRetentionSize;
    private long idempotencyInsertions;
    private int maxPositionQuantity;
    private long maxConcentrationNotionalTicks;

    /** Cold-path state image used only during startup/shutdown recovery. */
    public record Image(
        int[] accountIds,
        byte[] accountEnabled,
        long[] reservedNotional,
        long[] reservedBuyNotional,
        long[] reservedSellNotional,
        long[] executedNotional,
        byte[] securityEnabled,
        byte[] securityRestricted,
        long[] lastPrice,
        long[] lastPriceTime,
        long[] idempotencyKeys,
        int[] idempotencyOrderRefs,
        byte[] idempotencyDecisions,
        long[] idempotencyRetentionKeys,
        long[] entitlementKeys,
        byte[] entitlementEnabled,
        long[] reservationNotionalByOrderRef,
        int[] reservationQtyByOrderRef,
        int[] reservationAccountByOrderRef,
        int[] reservationSecurityByOrderRef,
        byte[] reservationSideByOrderRef,
        long[] exposureKeys,
        int[] reservedBuyQtyByExposure,
        int[] reservedSellQtyByExposure,
        long policyVersion,
        boolean killSwitch,
        int idempotencyRetentionCursor,
        int idempotencyRetentionSize,
        long idempotencyInsertions,
        int maxPositionQuantity,
        long maxConcentrationNotionalTicks
    ) {}

    public BlpRiskState(int maxAccounts, int maxSecurities, int maxOrders, int idempotencyCapacity,
                        long creditLimitTicks, int maxOrderQuantity, long maxOrderNotionalTicks,
                        long priceMaxAgeMillis, RiskMetrics metrics) {
        int accountsCapacity = tableSize(maxAccounts * 2);
        int keysCapacity = tableSize(idempotencyCapacity * 2);
        this.accountIds = new int[accountsCapacity];
        java.util.Arrays.fill(this.accountIds, -1);
        this.accountEnabled = new byte[accountsCapacity];
        this.reservedNotional = new long[accountsCapacity];
        this.reservedBuyNotional = new long[accountsCapacity];
        this.reservedSellNotional = new long[accountsCapacity];
        this.executedNotional = new long[accountsCapacity];
        this.securityEnabled = new byte[maxSecurities];
        this.securityRestricted = new byte[maxSecurities];
        this.lastPrice = new long[maxSecurities];
        this.lastPriceTime = new long[maxSecurities];
        this.idempotencyKeys = new long[keysCapacity];
        java.util.Arrays.fill(this.idempotencyKeys, EMPTY_KEY);
        this.idempotencyOrderRefs = new int[keysCapacity];
        this.idempotencyDecisions = new byte[keysCapacity];
        java.util.Arrays.fill(this.idempotencyDecisions, (byte) -1);
        this.idempotencyRetentionKeys = new long[Math.max(1, idempotencyCapacity)];
        java.util.Arrays.fill(this.idempotencyRetentionKeys, EMPTY_KEY);
        this.entitlementKeys = new long[tableSize(maxAccounts * 16)];
        java.util.Arrays.fill(this.entitlementKeys, EMPTY_KEY);
        this.entitlementEnabled = new byte[this.entitlementKeys.length];
        this.reservationNotionalByOrderRef = new long[maxOrders + 1];
        this.reservationQtyByOrderRef = new int[maxOrders + 1];
        this.reservationAccountByOrderRef = new int[maxOrders + 1];
        this.reservationSecurityByOrderRef = new int[maxOrders + 1];
        this.reservationSideByOrderRef = new byte[maxOrders + 1];
        this.exposureKeys = new long[tableSize(maxOrders * 2)];
        java.util.Arrays.fill(this.exposureKeys, EMPTY_KEY);
        this.reservedBuyQtyByExposure = new int[this.exposureKeys.length];
        this.reservedSellQtyByExposure = new int[this.exposureKeys.length];
        this.creditLimitTicks = creditLimitTicks;
        this.maxOrderQuantity = maxOrderQuantity;
        this.maxOrderNotionalTicks = maxOrderNotionalTicks;
        this.priceMaxAgeMillis = priceMaxAgeMillis;
        this.maxPositionQuantity = Math.max(maxOrderQuantity, 1);
        this.maxConcentrationNotionalTicks = creditLimitTicks;
        this.metrics = metrics;
    }

    public void bootstrap(GatewayReplicaStore.Snapshot snapshot) {
        policyVersion = snapshot.policyVersion();
        for (GatewayReplicaStore.AccountRecord account : snapshot.accounts()) {
            putAccount(account.accountId(), account.enabled());
        }
        for (GatewayReplicaStore.SecurityRecord security : snapshot.securities()) {
            if (security.securityId() >= 0 && security.securityId() < securityEnabled.length) {
                securityEnabled[security.securityId()] = (byte) (security.enabled() && !security.halted() ? 1 : 0);
                lastPrice[security.securityId()] = security.priceTicks() == Long.MIN_VALUE ? 0L : security.priceTicks();
                lastPriceTime[security.securityId()] = security.priceTimeMillis();
            }
        }
    }

    public void putAccount(int accountId, boolean enabled) {
        int slot = accountSlot(accountId, true);
        accountIds[slot] = accountId;
        accountEnabled[slot] = (byte) (enabled ? 1 : 0);
    }

    public void putSecurity(int securityId, boolean enabled) {
        if (securityId >= 0 && securityId < securityEnabled.length) {
            securityEnabled[securityId] = (byte) (enabled ? 1 : 0);
        }
    }

    public void putRestriction(int securityId, boolean restricted) {
        if (securityId >= 0 && securityId < securityRestricted.length) {
            securityRestricted[securityId] = (byte) (restricted ? 1 : 0);
        }
    }

    public void putPolicy(long version, boolean kill) {
        policyVersion = version;
        killSwitch = kill;
    }

    public void putLimits(int maxPositionQuantity, long maxConcentrationNotionalTicks) {
        this.maxPositionQuantity = maxPositionQuantity;
        this.maxConcentrationNotionalTicks = maxConcentrationNotionalTicks;
    }

    public void putEntitlement(long principalKey, int accountId, boolean enabled) {
        int slot = entitlementSlot(principalKey, accountId, true);
        if (slot < 0) throw new IllegalStateException("entitlement capacity exceeded");
        entitlementKeys[slot] = entitlementComposite(principalKey, accountId);
        entitlementEnabled[slot] = (byte) (enabled ? 1 : 0);
    }

    public void onPrice(int securityId, long priceTicks, long sourceTimeMillis) {
        if (securityId >= 0 && securityId < lastPrice.length) {
            lastPrice[securityId] = priceTicks;
            lastPriceTime[securityId] = sourceTimeMillis;
        }
    }

    public RiskReason decideAndReserve(long clientOrderKey, int orderRef, int accountId, int securityId,
                                       int quantity, long validationPrice, long eventTimeMillis) {
        return decideAndReserve(clientOrderKey, 0L, orderRef, accountId, securityId, quantity,
            validationPrice, eventTimeMillis);
    }

    public RiskReason decideAndReserve(long clientOrderKey, long principalKey, int orderRef, int accountId,
                                       int securityId, int quantity, long validationPrice,
                                       long eventTimeMillis) {
        return decideAndReserve(clientOrderKey, principalKey, orderRef, accountId, securityId, (byte) 0,
            0, quantity, validationPrice, eventTimeMillis);
    }

    public RiskReason decideAndReserve(long clientOrderKey, long principalKey, int orderRef, int accountId,
                                       int securityId, byte side, int currentPosition, int quantity,
                                       long validationPrice, long eventTimeMillis) {
        duplicateReplay = false;
        RiskReason previous = previousDecision(clientOrderKey);
        if (previous != null) return previous;
        if (killSwitch) return remember(clientOrderKey, 0, RiskReason.KILL_SWITCH);
        int accountSlot = accountSlot(accountId, false);
        if (accountSlot < 0) return remember(clientOrderKey, 0, RiskReason.UNKNOWN_ACCOUNT);
        if (accountEnabled[accountSlot] == 0) return remember(clientOrderKey, 0, RiskReason.ACCOUNT_DISABLED);
        if (principalKey != 0L && !isEntitled(principalKey, accountId)) {
            return remember(clientOrderKey, 0, RiskReason.NOT_ENTITLED);
        }
        if (securityId < 0 || securityId >= securityEnabled.length || securityEnabled[securityId] == 0) {
            return remember(clientOrderKey, 0, RiskReason.UNKNOWN_SECURITY);
        }
        if (securityRestricted[securityId] != 0) return remember(clientOrderKey, 0, RiskReason.RESTRICTED);
        if (quantity <= 0) return remember(clientOrderKey, 0, RiskReason.INVALID);
        if (quantity > maxOrderQuantity) return remember(clientOrderKey, 0, RiskReason.ORDER_SIZE);
        if (validationPrice <= 0) return remember(clientOrderKey, 0, RiskReason.PRICE_MISSING);
        if (lastPriceTime[securityId] > 0 && eventTimeMillis - lastPriceTime[securityId] > priceMaxAgeMillis) {
            return remember(clientOrderKey, 0, RiskReason.PRICE_STALE);
        }
        long notional;
        try {
            notional = Math.multiplyExact((long) quantity, validationPrice);
        } catch (ArithmeticException ex) {
            return remember(clientOrderKey, 0, RiskReason.ORDER_NOTIONAL);
        }
        if (notional > maxOrderNotionalTicks) return remember(clientOrderKey, 0, RiskReason.ORDER_NOTIONAL);
        if (executedNotional[accountSlot] + reservedNotional[accountSlot] > creditLimitTicks - notional) {
            return remember(clientOrderKey, 0, RiskReason.CREDIT_LIMIT);
        }
        int exposureSlot = exposureSlot(accountId, securityId, true);
        if (exposureSlot < 0) return remember(clientOrderKey, 0, RiskReason.CAPACITY);
        long projected = (long) currentPosition + reservedBuyQtyByExposure[exposureSlot]
            - reservedSellQtyByExposure[exposureSlot] + (side == 0 ? quantity : -quantity);
        if (Math.abs(projected) > maxPositionQuantity) {
            return remember(clientOrderKey, 0, RiskReason.POSITION_LIMIT);
        }
        if (multiplyExceeds(Math.abs(projected), validationPrice, maxConcentrationNotionalTicks)) {
            return remember(clientOrderKey, 0, RiskReason.CONCENTRATION_LIMIT);
        }
        if (orderRef <= 0 || orderRef >= reservationNotionalByOrderRef.length) {
            return remember(clientOrderKey, 0, RiskReason.CAPACITY);
        }
        if (!canRemember(clientOrderKey)) return decision(RiskReason.CAPACITY);
        reservedNotional[accountSlot] += notional;
        if (side == 0) {
            reservedBuyNotional[accountSlot] += notional;
            reservedBuyQtyByExposure[exposureSlot] += quantity;
        } else {
            reservedSellNotional[accountSlot] += notional;
            reservedSellQtyByExposure[exposureSlot] += quantity;
        }
        reservationNotionalByOrderRef[orderRef] = notional;
        reservationQtyByOrderRef[orderRef] = quantity;
        reservationAccountByOrderRef[orderRef] = accountId;
        reservationSecurityByOrderRef[orderRef] = securityId;
        reservationSideByOrderRef[orderRef] = side;
        return remember(clientOrderKey, orderRef, RiskReason.ACCEPTED);
    }

    public RiskReason decideMarketTrade(long clientOrderKey, int accountId, int securityId, int quantity,
                                        long priceTicks, long eventTimeMillis) {
        return decideMarketTrade(clientOrderKey, 0L, accountId, securityId, quantity, priceTicks,
            eventTimeMillis);
    }

    public RiskReason decideMarketTrade(long clientOrderKey, long principalKey, int accountId,
                                        int securityId, int quantity, long priceTicks,
                                        long eventTimeMillis) {
        return decideMarketTrade(clientOrderKey, principalKey, accountId, securityId, (byte) 0,
            0, quantity, priceTicks, eventTimeMillis);
    }

    public RiskReason decideMarketTrade(long clientOrderKey, long principalKey, int accountId,
                                        int securityId, byte side, int currentPosition, int quantity,
                                        long priceTicks, long eventTimeMillis) {
        duplicateReplay = false;
        RiskReason previous = previousDecision(clientOrderKey);
        if (previous != null) return previous;
        if (killSwitch) return remember(clientOrderKey, 0, RiskReason.KILL_SWITCH);
        int accountSlot = accountSlot(accountId, false);
        if (accountSlot < 0) return remember(clientOrderKey, 0, RiskReason.UNKNOWN_ACCOUNT);
        if (accountEnabled[accountSlot] == 0) return remember(clientOrderKey, 0, RiskReason.ACCOUNT_DISABLED);
        if (principalKey != 0L && !isEntitled(principalKey, accountId)) {
            return remember(clientOrderKey, 0, RiskReason.NOT_ENTITLED);
        }
        if (securityId < 0 || securityId >= securityEnabled.length || securityEnabled[securityId] == 0) {
            return remember(clientOrderKey, 0, RiskReason.UNKNOWN_SECURITY);
        }
        if (securityRestricted[securityId] != 0) return remember(clientOrderKey, 0, RiskReason.RESTRICTED);
        if (quantity <= 0) return remember(clientOrderKey, 0, RiskReason.INVALID);
        if (quantity > maxOrderQuantity) return remember(clientOrderKey, 0, RiskReason.ORDER_SIZE);
        if (priceTicks <= 0) return remember(clientOrderKey, 0, RiskReason.PRICE_MISSING);
        if (lastPriceTime[securityId] > 0 && eventTimeMillis - lastPriceTime[securityId] > priceMaxAgeMillis) {
            return remember(clientOrderKey, 0, RiskReason.PRICE_STALE);
        }
        long notional;
        try {
            notional = Math.multiplyExact((long) quantity, priceTicks);
        } catch (ArithmeticException ex) {
            return remember(clientOrderKey, 0, RiskReason.ORDER_NOTIONAL);
        }
        if (notional > maxOrderNotionalTicks) return remember(clientOrderKey, 0, RiskReason.ORDER_NOTIONAL);
        if (executedNotional[accountSlot] + reservedNotional[accountSlot] > creditLimitTicks - notional) {
            return remember(clientOrderKey, 0, RiskReason.CREDIT_LIMIT);
        }
        int exposureSlot = exposureSlot(accountId, securityId, true);
        if (exposureSlot < 0) return remember(clientOrderKey, 0, RiskReason.CAPACITY);
        long projected = (long) currentPosition + reservedBuyQtyByExposure[exposureSlot]
            - reservedSellQtyByExposure[exposureSlot] + (side == 0 ? quantity : -quantity);
        if (Math.abs(projected) > maxPositionQuantity) {
            return remember(clientOrderKey, 0, RiskReason.POSITION_LIMIT);
        }
        if (multiplyExceeds(Math.abs(projected), priceTicks, maxConcentrationNotionalTicks)) {
            return remember(clientOrderKey, 0, RiskReason.CONCENTRATION_LIMIT);
        }
        if (!canRemember(clientOrderKey)) return decision(RiskReason.CAPACITY);
        executedNotional[accountSlot] += notional;
        return remember(clientOrderKey, 0, RiskReason.ACCEPTED);
    }

    public int existingOrderRef(long clientOrderKey) {
        int slot = idempotencySlot(clientOrderKey, false);
        if (slot < 0) return -1;
        if (idempotencyDecisions[slot] != (byte) RiskReason.ACCEPTED.ordinal()
            || idempotencyOrderRefs[slot] <= 0) return -1;
        metrics.duplicate();
        duplicateReplay = true;
        decision(RiskReason.ACCEPTED);
        return idempotencyOrderRefs[slot];
    }

    public void consume(int accountId, int orderRef, int fillQty, long execPrice) {
        if (orderRef <= 0 || orderRef >= reservationQtyByOrderRef.length) return;
        int reservedQty = reservationQtyByOrderRef[orderRef];
        if (reservedQty <= 0) return;
        int appliedQty = Math.min(fillQty, reservedQty);
        long release = reservationNotionalByOrderRef[orderRef] * appliedQty / reservedQty;
        int slot = accountSlot(accountId, false);
        if (slot >= 0) {
            reservedNotional[slot] = Math.max(0L, reservedNotional[slot] - release);
            if (reservationSideByOrderRef[orderRef] == 0) {
                reservedBuyNotional[slot] = Math.max(0L, reservedBuyNotional[slot] - release);
            } else {
                reservedSellNotional[slot] = Math.max(0L, reservedSellNotional[slot] - release);
            }
            try {
                executedNotional[slot] = Math.addExact(executedNotional[slot],
                    Math.multiplyExact((long) appliedQty, execPrice));
            } catch (ArithmeticException ex) {
                executedNotional[slot] = Long.MAX_VALUE;
            }
        }
        int exposureSlot = exposureSlot(reservationAccountByOrderRef[orderRef],
            reservationSecurityByOrderRef[orderRef], false);
        if (exposureSlot >= 0) {
            if (reservationSideByOrderRef[orderRef] == 0) {
                reservedBuyQtyByExposure[exposureSlot] = Math.max(0,
                    reservedBuyQtyByExposure[exposureSlot] - appliedQty);
            } else {
                reservedSellQtyByExposure[exposureSlot] = Math.max(0,
                    reservedSellQtyByExposure[exposureSlot] - appliedQty);
            }
        }
        reservationNotionalByOrderRef[orderRef] = Math.max(0L, reservationNotionalByOrderRef[orderRef] - release);
        reservationQtyByOrderRef[orderRef] -= appliedQty;
    }

    public void release(int accountId, int orderRef) {
        if (orderRef <= 0 || orderRef >= reservationNotionalByOrderRef.length) return;
        int slot = accountSlot(accountId, false);
        if (slot >= 0) {
            reservedNotional[slot] = Math.max(0L,
                reservedNotional[slot] - reservationNotionalByOrderRef[orderRef]);
            if (reservationSideByOrderRef[orderRef] == 0) {
                reservedBuyNotional[slot] = Math.max(0L,
                    reservedBuyNotional[slot] - reservationNotionalByOrderRef[orderRef]);
            } else {
                reservedSellNotional[slot] = Math.max(0L,
                    reservedSellNotional[slot] - reservationNotionalByOrderRef[orderRef]);
            }
        }
        int exposureSlot = exposureSlot(reservationAccountByOrderRef[orderRef],
            reservationSecurityByOrderRef[orderRef], false);
        if (exposureSlot >= 0) {
            if (reservationSideByOrderRef[orderRef] == 0) {
                reservedBuyQtyByExposure[exposureSlot] = Math.max(0,
                    reservedBuyQtyByExposure[exposureSlot] - reservationQtyByOrderRef[orderRef]);
            } else {
                reservedSellQtyByExposure[exposureSlot] = Math.max(0,
                    reservedSellQtyByExposure[exposureSlot] - reservationQtyByOrderRef[orderRef]);
            }
        }
        clearReservation(orderRef);
    }

    private void clearReservation(int orderRef) {
        reservationNotionalByOrderRef[orderRef] = 0L;
        reservationQtyByOrderRef[orderRef] = 0;
        reservationAccountByOrderRef[orderRef] = 0;
        reservationSecurityByOrderRef[orderRef] = 0;
        reservationSideByOrderRef[orderRef] = 0;
    }

    private RiskReason decision(RiskReason reason) {
        metrics.decided(reason);
        return reason;
    }

    private RiskReason previousDecision(long key) {
        int slot = idempotencySlot(key, false);
        if (slot < 0) return null;
        metrics.duplicate();
        duplicateReplay = true;
        return decision(REASONS[idempotencyDecisions[slot]]);
    }

    private boolean canRemember(long key) {
        if (idempotencySlot(key, true) >= 0) return true;
        evictOldestIdempotencyKey();
        return idempotencySlot(key, true) >= 0;
    }

    private RiskReason remember(long key, int orderRef, RiskReason reason) {
        if (idempotencySlot(key, false) < 0 && idempotencyRetentionSize == idempotencyRetentionKeys.length) {
            evictOldestIdempotencyKey();
        }
        int slot = idempotencySlot(key, true);
        if (slot < 0) return decision(RiskReason.CAPACITY);
        boolean fresh = idempotencyKeys[slot] != key;
        idempotencyKeys[slot] = key;
        idempotencyOrderRefs[slot] = orderRef;
        idempotencyDecisions[slot] = (byte) reason.ordinal();
        if (fresh) {
            idempotencyRetentionKeys[idempotencyRetentionCursor] = key;
            idempotencyRetentionCursor = (idempotencyRetentionCursor + 1) % idempotencyRetentionKeys.length;
            if (idempotencyRetentionSize < idempotencyRetentionKeys.length) idempotencyRetentionSize++;
            idempotencyInsertions++;
        }
        return decision(reason);
    }

    private void evictOldestIdempotencyKey() {
        if (idempotencyRetentionSize == 0) return;
        int oldest = idempotencyRetentionCursor - idempotencyRetentionSize;
        if (oldest < 0) oldest += idempotencyRetentionKeys.length;
        long key = idempotencyRetentionKeys[oldest];
        int slot = idempotencySlot(key, false);
        if (slot >= 0) {
            idempotencyKeys[slot] = TOMBSTONE_KEY;
            idempotencyOrderRefs[slot] = 0;
            idempotencyDecisions[slot] = -1;
        }
        idempotencyRetentionKeys[oldest] = EMPTY_KEY;
        idempotencyRetentionSize--;
    }

    private int accountSlot(int accountId, boolean create) {
        int mask = accountIds.length - 1;
        int slot = mix(accountId) & mask;
        for (int i = 0; i < accountIds.length; i++) {
            int value = accountIds[slot];
            if (value == accountId) return slot;
            if (value == -1) return create ? slot : -1;
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private int idempotencySlot(long key, boolean create) {
        int mask = idempotencyKeys.length - 1;
        int slot = mix((int) (key ^ (key >>> 32))) & mask;
        int firstTombstone = -1;
        for (int i = 0; i < idempotencyKeys.length; i++) {
            long value = idempotencyKeys[slot];
            if (value == key) return slot;
            if (value == TOMBSTONE_KEY && firstTombstone < 0) firstTombstone = slot;
            if (value == EMPTY_KEY) return create ? (firstTombstone >= 0 ? firstTombstone : slot) : -1;
            slot = (slot + 1) & mask;
        }
        return create ? firstTombstone : -1;
    }

    private boolean isEntitled(long principalKey, int accountId) {
        int slot = entitlementSlot(principalKey, accountId, false);
        return slot >= 0 && entitlementEnabled[slot] != 0;
    }

    private int entitlementSlot(long principalKey, int accountId, boolean create) {
        long key = entitlementComposite(principalKey, accountId);
        int mask = entitlementKeys.length - 1;
        int slot = mix((int) (key ^ (key >>> 32))) & mask;
        for (int i = 0; i < entitlementKeys.length; i++) {
            long value = entitlementKeys[slot];
            if (value == key) return slot;
            if (value == EMPTY_KEY) return create ? slot : -1;
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private int exposureSlot(int accountId, int securityId, boolean create) {
        long key = ((long) accountId << 32) | (securityId & 0xffffffffL);
        if (key == EMPTY_KEY) key++;
        int mask = exposureKeys.length - 1;
        int slot = mix((int) (key ^ (key >>> 32))) & mask;
        for (int i = 0; i < exposureKeys.length; i++) {
            long value = exposureKeys[slot];
            if (value == key) return slot;
            if (value == EMPTY_KEY) {
                if (create) exposureKeys[slot] = key;
                return create ? slot : -1;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private static boolean multiplyExceeds(long left, long right, long limit) {
        if (left == 0 || right == 0) return false;
        return left > limit / right;
    }

    private static long entitlementComposite(long principalKey, int accountId) {
        long value = principalKey ^ (0x9e3779b97f4a7c15L * accountId);
        return value == EMPTY_KEY ? EMPTY_KEY + 1L : value;
    }

    private static int tableSize(int requested) {
        int value = 1;
        while (value < Math.max(2, requested)) value <<= 1;
        return value;
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }

    public Image captureImage() {
        return new Image(
            accountIds.clone(), accountEnabled.clone(), reservedNotional.clone(),
            reservedBuyNotional.clone(), reservedSellNotional.clone(), executedNotional.clone(),
            securityEnabled.clone(), securityRestricted.clone(), lastPrice.clone(), lastPriceTime.clone(),
            idempotencyKeys.clone(), idempotencyOrderRefs.clone(), idempotencyDecisions.clone(),
            idempotencyRetentionKeys.clone(), entitlementKeys.clone(), entitlementEnabled.clone(),
            reservationNotionalByOrderRef.clone(), reservationQtyByOrderRef.clone(),
            reservationAccountByOrderRef.clone(), reservationSecurityByOrderRef.clone(),
            reservationSideByOrderRef.clone(), exposureKeys.clone(), reservedBuyQtyByExposure.clone(),
            reservedSellQtyByExposure.clone(), policyVersion, killSwitch, idempotencyRetentionCursor,
            idempotencyRetentionSize, idempotencyInsertions, maxPositionQuantity,
            maxConcentrationNotionalTicks);
    }

    public void restoreImage(Image image) {
        copy(image.accountIds(), accountIds, "accounts");
        copy(image.accountEnabled(), accountEnabled, "account status");
        copy(image.reservedNotional(), reservedNotional, "reserved notional");
        copy(image.reservedBuyNotional(), reservedBuyNotional, "reserved buy notional");
        copy(image.reservedSellNotional(), reservedSellNotional, "reserved sell notional");
        copy(image.executedNotional(), executedNotional, "executed notional");
        copy(image.securityEnabled(), securityEnabled, "security status");
        copy(image.securityRestricted(), securityRestricted, "security restrictions");
        copy(image.lastPrice(), lastPrice, "prices");
        copy(image.lastPriceTime(), lastPriceTime, "price timestamps");
        copy(image.idempotencyKeys(), idempotencyKeys, "idempotency keys");
        copy(image.idempotencyOrderRefs(), idempotencyOrderRefs, "idempotency order refs");
        copy(image.idempotencyDecisions(), idempotencyDecisions, "idempotency decisions");
        copy(image.idempotencyRetentionKeys(), idempotencyRetentionKeys, "idempotency retention");
        copy(image.entitlementKeys(), entitlementKeys, "entitlement keys");
        copy(image.entitlementEnabled(), entitlementEnabled, "entitlements");
        copy(image.reservationNotionalByOrderRef(), reservationNotionalByOrderRef, "reservation notional");
        copy(image.reservationQtyByOrderRef(), reservationQtyByOrderRef, "reservation quantity");
        copy(image.reservationAccountByOrderRef(), reservationAccountByOrderRef, "reservation account");
        copy(image.reservationSecurityByOrderRef(), reservationSecurityByOrderRef, "reservation security");
        copy(image.reservationSideByOrderRef(), reservationSideByOrderRef, "reservation side");
        copy(image.exposureKeys(), exposureKeys, "exposure keys");
        copy(image.reservedBuyQtyByExposure(), reservedBuyQtyByExposure, "buy exposure");
        copy(image.reservedSellQtyByExposure(), reservedSellQtyByExposure, "sell exposure");
        if (image.idempotencyRetentionCursor() < 0
            || image.idempotencyRetentionCursor() >= idempotencyRetentionKeys.length
            || image.idempotencyRetentionSize() < 0
            || image.idempotencyRetentionSize() > idempotencyRetentionKeys.length) {
            throw new IllegalArgumentException("invalid idempotency retention metadata");
        }
        policyVersion = image.policyVersion();
        killSwitch = image.killSwitch();
        duplicateReplay = false;
        idempotencyRetentionCursor = image.idempotencyRetentionCursor();
        idempotencyRetentionSize = image.idempotencyRetentionSize();
        idempotencyInsertions = image.idempotencyInsertions();
        maxPositionQuantity = image.maxPositionQuantity();
        maxConcentrationNotionalTicks = image.maxConcentrationNotionalTicks();
    }

    private static void copy(int[] source, int[] target, String name) {
        if (source.length != target.length) throw new IllegalArgumentException("incompatible " + name + " capacity");
        System.arraycopy(source, 0, target, 0, target.length);
    }

    private static void copy(long[] source, long[] target, String name) {
        if (source.length != target.length) throw new IllegalArgumentException("incompatible " + name + " capacity");
        System.arraycopy(source, 0, target, 0, target.length);
    }

    private static void copy(byte[] source, byte[] target, String name) {
        if (source.length != target.length) throw new IllegalArgumentException("incompatible " + name + " capacity");
        System.arraycopy(source, 0, target, 0, target.length);
    }

    public long policyVersion() { return policyVersion; }
    public boolean duplicateReplay() { return duplicateReplay; }
    public long idempotencyFrontier() { return Math.max(0L, idempotencyInsertions - idempotencyRetentionSize); }
    public long reservedNotional(int accountId) {
        int slot = accountSlot(accountId, false);
        return slot < 0 ? 0L : reservedNotional[slot];
    }
    public long reservedBuyNotional(int accountId) {
        int slot = accountSlot(accountId, false);
        return slot < 0 ? 0L : reservedBuyNotional[slot];
    }
    public long reservedSellNotional(int accountId) {
        int slot = accountSlot(accountId, false);
        return slot < 0 ? 0L : reservedSellNotional[slot];
    }
}
