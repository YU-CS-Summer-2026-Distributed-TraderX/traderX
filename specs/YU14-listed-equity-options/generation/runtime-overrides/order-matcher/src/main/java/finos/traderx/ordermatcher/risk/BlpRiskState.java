package finos.traderx.ordermatcher.risk;

import java.util.ArrayList;
import java.util.List;

/**
 * Preallocated single-writer authoritative risk state (in-memory-risk-gateway forward-port).
 * All decision-path methods are called by the BLP thread after bootstrap; no locks, boxing,
 * remote calls, or per-event allocation (NFR-IMRG02/04). The ordered validation pipeline and
 * stable rejection precedence are part of the contract (FR-IMRG12).
 *
 * <p>Forward-port adaptation vs. the stale-branch original: per-order reservations live on the
 * order entry itself ({@link ReservationHolder}) instead of dense orderRef-indexed arrays,
 * because orderRef is monotonic/unbounded on this branch. Per-account and per-(account,security)
 * aggregates remain here and are rebuilt from open-order reservations at snapshot restore.
 */
public final class BlpRiskState {
    private static final long EMPTY_KEY = Long.MIN_VALUE;
    private static final RiskReason[] REASONS = RiskReason.values();

    // account table (open addressing; -1 = empty slot)
    private final int[] accountIds;
    private final byte[] accountEnabled;
    private final long[] reservedNotional;
    private final long[] reservedBuyNotional;
    private final long[] reservedSellNotional;
    private final long[] executedNotional;
    // security control/price state (dense by securityId; SymbolTable is the id authority)
    private final byte[] securityEnabled;
    private final byte[] securityRestricted;
    private final long[] lastPrice;
    private final long[] lastPriceTime;
    // YU14 (ADR-053): contract multiplier per security; 0 = never set, effective 1. Written on
    // the cold registration/bootstrap paths, read once per decision.
    private final long[] contractMultiplier;
    // idempotency (bounded; oldest key evicted at capacity — the retention frontier, FR-IMRG14)
    private final long[] idempotencyKeys;
    private final int[] idempotencyOrderRefs;
    private final byte[] idempotencyDecisions;
    private final long[] idempotencyRetentionKeys;
    // entitlements (unused until the auth roadmap item feeds principals; principalKey 0 skips)
    private final long[] entitlementKeys;
    private final byte[] entitlementEnabled;
    // reserved open-order quantity per (account, security) pair for position projection
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

    public BlpRiskState(int maxAccounts, int maxSecurities, int maxOpenOrders, int idempotencyCapacity,
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
        this.contractMultiplier = new long[maxSecurities];
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
        this.exposureKeys = new long[tableSize(maxOpenOrders * 2)];
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

    /** Fixed initial condition: install the Gateway replica's seeded accounts/securities
     *  (the same pre-loaded stance as the seed order book; deterministic across replays). */
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

    // ----- control-state writers (BLP thread; sequenced control events + bootstrap) ------------

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

    /** YU14: install a security's contract multiplier (cold path: registration/bootstrap). */
    public void putContractMultiplier(int securityId, long multiplier) {
        if (securityId >= 0 && securityId < contractMultiplier.length) {
            contractMultiplier[securityId] = multiplier;
        }
    }

    /** Stored multiplier (0 = never set); snapshot capture reads this raw. */
    public long contractMultiplier(int securityId) {
        return securityId >= 0 && securityId < contractMultiplier.length
            ? contractMultiplier[securityId] : 0L;
    }

    private long effectiveMultiplier(int securityId) {
        final long m = contractMultiplier[securityId];
        return m == 0L ? 1L : m;
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

    // ----- authoritative decisions (ordered pipeline; stable precedence — FR-IMRG12) ------------

    /**
     * Check every control in order and, on acceptance, atomically reserve the order's exposure
     * into the account/exposure aggregates and onto {@code target} (FR-IMRG13). Never partially
     * reserves. clientOrderKey 0 = no idempotency key (pre-state journals, key-less clients).
     */
    public RiskReason decideAndReserve(long clientOrderKey, long principalKey, int orderRef,
                                       int accountId, int securityId, byte side, int currentPosition,
                                       int quantity, long validationPrice, long eventTimeMillis,
                                       ReservationHolder target) {
        duplicateReplay = false;
        if (clientOrderKey != 0L) {
            RiskReason previous = previousDecision(clientOrderKey);
            if (previous != null) return previous;
        }
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
        final long multiplier = effectiveMultiplier(securityId);
        long notional;
        try {
            notional = Math.multiplyExact(Math.multiplyExact((long) quantity, validationPrice), multiplier);
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
        // |projected| <= maxPositionQuantity (int) here, so x multiplier cannot overflow a long.
        if (multiplyExceeds(Math.abs(projected) * multiplier, validationPrice, maxConcentrationNotionalTicks)) {
            return remember(clientOrderKey, 0, RiskReason.CONCENTRATION_LIMIT);
        }
        if (clientOrderKey != 0L && !canRemember(clientOrderKey)) return decision(RiskReason.CAPACITY);
        reservedNotional[accountSlot] += notional;
        if (side == 0) {
            reservedBuyNotional[accountSlot] += notional;
            reservedBuyQtyByExposure[exposureSlot] += quantity;
        } else {
            reservedSellNotional[accountSlot] += notional;
            reservedSellQtyByExposure[exposureSlot] += quantity;
        }
        target.setReservation(notional, quantity);
        return remember(clientOrderKey, orderRef, RiskReason.ACCEPTED);
    }

    /** Market trades execute immediately: same pipeline, executed (not reserved) on acceptance. */
    public RiskReason decideMarketTrade(long clientOrderKey, long principalKey, int accountId,
                                        int securityId, byte side, int currentPosition, int quantity,
                                        long priceTicks, long eventTimeMillis) {
        duplicateReplay = false;
        if (clientOrderKey != 0L) {
            RiskReason previous = previousDecision(clientOrderKey);
            if (previous != null) return previous;
        }
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
        final long multiplier = effectiveMultiplier(securityId);
        long notional;
        try {
            notional = Math.multiplyExact(Math.multiplyExact((long) quantity, priceTicks), multiplier);
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
        if (multiplyExceeds(Math.abs(projected) * multiplier, priceTicks, maxConcentrationNotionalTicks)) {
            return remember(clientOrderKey, 0, RiskReason.CONCENTRATION_LIMIT);
        }
        if (clientOrderKey != 0L && !canRemember(clientOrderKey)) return decision(RiskReason.CAPACITY);
        executedNotional[accountSlot] += notional;
        return remember(clientOrderKey, 0, RiskReason.ACCEPTED);
    }

    /** Original accepted orderRef for a retried key, or -1. Sets {@link #duplicateReplay}. */
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

    /** Fill: convert part of the order's reservation into executed exposure, exactly once. */
    public void consume(int accountId, int securityId, byte side, ReservationHolder order,
                        int fillQty, long execPrice) {
        int reservedQty = order.reservedQty();
        if (reservedQty <= 0) return;
        int appliedQty = Math.min(fillQty, reservedQty);
        long reservedNotionalLeft = order.reservedNotional();
        long release = reservedNotionalLeft * appliedQty / reservedQty;
        int slot = accountSlot(accountId, false);
        if (slot >= 0) {
            reservedNotional[slot] = Math.max(0L, reservedNotional[slot] - release);
            if (side == 0) {
                reservedBuyNotional[slot] = Math.max(0L, reservedBuyNotional[slot] - release);
            } else {
                reservedSellNotional[slot] = Math.max(0L, reservedSellNotional[slot] - release);
            }
            try {
                executedNotional[slot] = Math.addExact(executedNotional[slot],
                    Math.multiplyExact(Math.multiplyExact((long) appliedQty, execPrice),
                        effectiveMultiplier(securityId)));
            } catch (ArithmeticException ex) {
                executedNotional[slot] = Long.MAX_VALUE;
            }
        }
        int exposureSlot = exposureSlot(accountId, securityId, false);
        if (exposureSlot >= 0) {
            if (side == 0) {
                reservedBuyQtyByExposure[exposureSlot] = Math.max(0,
                    reservedBuyQtyByExposure[exposureSlot] - appliedQty);
            } else {
                reservedSellQtyByExposure[exposureSlot] = Math.max(0,
                    reservedSellQtyByExposure[exposureSlot] - appliedQty);
            }
        }
        order.setReservation(Math.max(0L, reservedNotionalLeft - release), reservedQty - appliedQty);
    }

    /** Cancel/evict: release the order's remaining reservation exactly once (never negative). */
    public void release(int accountId, int securityId, byte side, ReservationHolder order) {
        long notionalLeft = order.reservedNotional();
        int qtyLeft = order.reservedQty();
        if (notionalLeft <= 0 && qtyLeft <= 0) return;
        int slot = accountSlot(accountId, false);
        if (slot >= 0) {
            reservedNotional[slot] = Math.max(0L, reservedNotional[slot] - notionalLeft);
            if (side == 0) {
                reservedBuyNotional[slot] = Math.max(0L, reservedBuyNotional[slot] - notionalLeft);
            } else {
                reservedSellNotional[slot] = Math.max(0L, reservedSellNotional[slot] - notionalLeft);
            }
        }
        int exposureSlot = exposureSlot(accountId, securityId, false);
        if (exposureSlot >= 0) {
            if (side == 0) {
                reservedBuyQtyByExposure[exposureSlot] = Math.max(0,
                    reservedBuyQtyByExposure[exposureSlot] - qtyLeft);
            } else {
                reservedSellQtyByExposure[exposureSlot] = Math.max(0,
                    reservedSellQtyByExposure[exposureSlot] - qtyLeft);
            }
        }
        order.setReservation(0L, 0);
    }

    // ----- snapshot capture/restore (cold path; FR-IMRG21) -------------------------------------
    // Reservation aggregates are NOT captured: they are rebuilt from the snapshotted open orders
    // via reaccumulateReservation, so aggregates and per-order reservations can never disagree.

    /** {policyVersion, killSwitch, maxPositionQuantity, maxConcentrationNotionalTicks}. */
    public long[] policyTuple() {
        return new long[] { policyVersion, killSwitch ? 1 : 0, maxPositionQuantity,
            maxConcentrationNotionalTicks };
    }

    /** Occupied account slots as {accountId, enabled, executedNotional}. */
    public List<long[]> accountTuples() {
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < accountIds.length; i++) {
            if (accountIds[i] != -1) {
                out.add(new long[] { accountIds[i], accountEnabled[i], executedNotional[i] });
            }
        }
        return out;
    }

    /** Non-default security rows as {securityId, enabled, restricted, lastPrice, lastPriceTime}.
     *  YU14: a set contract multiplier also makes a row non-default, so a registered-but-not-yet
     *  -enabled option's multiplier reaches the snapshot (the cluster service appends the
     *  multiplier column to this tuple). */
    public List<long[]> securityTuples() {
        List<long[]> out = new ArrayList<>();
        for (int s = 0; s < securityEnabled.length; s++) {
            if (securityEnabled[s] != 0 || securityRestricted[s] != 0 || lastPrice[s] != 0L
                || lastPriceTime[s] != 0L || contractMultiplier[s] != 0L) {
                out.add(new long[] { s, securityEnabled[s], securityRestricted[s], lastPrice[s],
                    lastPriceTime[s] });
            }
        }
        return out;
    }

    /** Live idempotency entries in retention (insertion) order as {key, orderRef, decision}. */
    public List<long[]> idempotencyTuples() {
        List<long[]> out = new ArrayList<>(idempotencyRetentionSize);
        for (int i = 0; i < idempotencyRetentionSize; i++) {
            int index = idempotencyRetentionCursor - idempotencyRetentionSize + i;
            if (index < 0) index += idempotencyRetentionKeys.length;
            long key = idempotencyRetentionKeys[index];
            if (key == EMPTY_KEY) continue;
            int slot = idempotencySlot(key, false);
            if (slot < 0) continue;
            out.add(new long[] { key, idempotencyOrderRefs[slot], idempotencyDecisions[slot] });
        }
        return out;
    }

    public void bootstrapPolicy(long[] policyTuple) {
        policyVersion = policyTuple[0];
        killSwitch = policyTuple[1] != 0;
        maxPositionQuantity = (int) policyTuple[2];
        maxConcentrationNotionalTicks = policyTuple[3];
    }

    public void bootstrapAccount(int accountId, boolean enabled, long executed) {
        int slot = accountSlot(accountId, true);
        accountIds[slot] = accountId;
        accountEnabled[slot] = (byte) (enabled ? 1 : 0);
        executedNotional[slot] = executed;
    }

    public void bootstrapSecurity(int securityId, boolean enabled, boolean restricted,
                                  long price, long priceTimeMillis) {
        if (securityId < 0 || securityId >= securityEnabled.length) return;
        securityEnabled[securityId] = (byte) (enabled ? 1 : 0);
        securityRestricted[securityId] = (byte) (restricted ? 1 : 0);
        lastPrice[securityId] = price;
        lastPriceTime[securityId] = priceTimeMillis;
    }

    /** Re-insert a snapshotted idempotency entry; call in retention order to preserve eviction. */
    public void bootstrapIdempotency(long key, int orderRef, byte decisionOrdinal) {
        if (key == 0L || key == EMPTY_KEY) return;
        if (!canRemember(key)) return;
        int slot = idempotencySlot(key, true);
        if (slot < 0) return;
        boolean fresh = idempotencyKeys[slot] != key;
        idempotencyKeys[slot] = key;
        idempotencyOrderRefs[slot] = orderRef;
        idempotencyDecisions[slot] = decisionOrdinal;
        if (fresh) {
            idempotencyRetentionKeys[idempotencyRetentionCursor] = key;
            idempotencyRetentionCursor = (idempotencyRetentionCursor + 1) % idempotencyRetentionKeys.length;
            if (idempotencyRetentionSize < idempotencyRetentionKeys.length) idempotencyRetentionSize++;
            idempotencyInsertions++;
        }
    }

    /** Restore an open order's reservation into the account/exposure aggregates (snapshot load). */
    public void reaccumulateReservation(int accountId, int securityId, byte side,
                                        long notional, int qty) {
        if (notional <= 0 && qty <= 0) return;
        int slot = accountSlot(accountId, true);
        accountIds[slot] = accountId;
        reservedNotional[slot] += notional;
        if (side == 0) {
            reservedBuyNotional[slot] += notional;
        } else {
            reservedSellNotional[slot] += notional;
        }
        int exposureSlot = exposureSlot(accountId, securityId, true);
        if (exposureSlot >= 0) {
            if (side == 0) {
                reservedBuyQtyByExposure[exposureSlot] += qty;
            } else {
                reservedSellQtyByExposure[exposureSlot] += qty;
            }
        }
    }

    // ----- internals ----------------------------------------------------------------------------

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
        if (key == 0L) {
            return decision(reason);   // key-less command: decided but not replay-mapped
        }
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
            deleteIdempotencySlot(slot);
        }
        idempotencyRetentionKeys[oldest] = EMPTY_KEY;
        idempotencyRetentionSize--;
    }

    /**
     * Backward-shift deletion (Knuth TAOCP 6.4 algorithm R). Removing the entry at {@code hole}
     * pulls any following cluster member whose home slot is at or behind the hole back into it,
     * until an EMPTY slot is reached — so no tombstone is ever written and the miss-path probe
     * {@code idempotencySlot} runs stays bounded by the 0.5 load factor forever, instead of
     * degrading toward a full-table scan as tombstones saturate. A pure function of the table
     * contents and the deleted slot: deterministic, identical on every member and on replay, and
     * zero-allocation (in-place array moves only).
     */
    private void deleteIdempotencySlot(int hole) {
        int mask = idempotencyKeys.length - 1;
        int j = hole;
        while (true) {
            j = (j + 1) & mask;
            long candidate = idempotencyKeys[j];
            if (candidate == EMPTY_KEY) break;                 // end of the cluster: done
            int home = mix((int) (candidate ^ (candidate >>> 32))) & mask;
            // If the candidate's home lies cyclically in (hole, j], pulling it back would place
            // it before its home and make it unfindable — leave it and keep scanning.
            boolean homeInGap = (hole <= j) ? (hole < home && home <= j) : (hole < home || home <= j);
            if (homeInGap) continue;
            idempotencyKeys[hole] = candidate;                 // pull the candidate back
            idempotencyOrderRefs[hole] = idempotencyOrderRefs[j];
            idempotencyDecisions[hole] = idempotencyDecisions[j];
            hole = j;                                          // j is the new hole
        }
        idempotencyKeys[hole] = EMPTY_KEY;
        idempotencyOrderRefs[hole] = 0;
        idempotencyDecisions[hole] = -1;
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
        for (int i = 0; i < idempotencyKeys.length; i++) {
            long value = idempotencyKeys[slot];
            if (value == key) return slot;
            if (value == EMPTY_KEY) return create ? slot : -1;
            slot = (slot + 1) & mask;
        }
        return -1;
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

    // ----- edge-readable accessors (cold path / tests) ------------------------------------------

    public long policyVersion() { return policyVersion; }

    public boolean killSwitch() { return killSwitch; }

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

    public long executedNotional(int accountId) {
        int slot = accountSlot(accountId, false);
        return slot < 0 ? 0L : executedNotional[slot];
    }

    public long totalReservedNotional() {
        long total = 0L;
        for (long value : reservedNotional) {
            if (Long.MAX_VALUE - total < value) return Long.MAX_VALUE;
            total += value;
        }
        return total;
    }
}
