package finos.traderx.ordermatcher.risk;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Versioned local validation image. In the demo profile it bootstraps from explicit configuration;
 * the same apply/snapshot contract is used by durable control consumers and tests.
 */
@Component
public final class GatewayReplicaStore {
    public record AccountRecord(int accountId, boolean enabled, long version) {}
    public record EntitlementRecord(String principal, int accountId, boolean enabled, long version) {}
    public record SecurityRecord(int securityId, String ticker, boolean enabled, boolean halted,
                                 long priceTicks, long priceTimeMillis, long version) {}
    public record Snapshot(long sourceEpoch, long watermark, long highWatermark, long policyVersion,
                           boolean ready, List<AccountRecord> accounts, List<SecurityRecord> securities) {}

    private final ConcurrentHashMap<Integer, AccountRecord> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EntitlementRecord> entitlements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SecurityRecord> securities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> restrictions = new ConcurrentHashMap<>();
    private final AtomicInteger nextSecurityId = new AtomicInteger();
    private final AtomicLong sourceVersion = new AtomicLong();
    private final AtomicLong highWatermark = new AtomicLong();
    private final RiskMetrics metrics = new RiskMetrics();

    private final String seedAccounts;
    private final String seedSecurities;
    private final int maxOrderQuantity;
    private final long maxOrderNotionalTicks;
    private final long priceMaxAgeMillis;
    private final long priceCollarBps;
    private volatile boolean ready;
    private volatile boolean killSwitch;
    private volatile long sourceEpoch = 1L;
    private volatile long policyVersion = 1L;
    private volatile boolean externalMode;
    private volatile boolean accountReady = true;
    private volatile boolean securityReady = true;
    private volatile long accountEpoch = 1L;
    private volatile long accountVersion;
    private volatile long accountHighWatermark;
    private volatile long securityEpoch = 1L;
    private volatile long securityVersion;
    private volatile long securityHighWatermark;

    public GatewayReplicaStore(
        @Value("${risk.seed.accounts:22214,44044,52355,10031,62654}") String seedAccounts,
        @Value("${risk.seed.securities:IBM,MSFT,JPM,GS,NVDA,C,META,MS,UBS,DB,COF,DFS,FIS,FNF,PARITYA,PARITYB,PARITYC,PARITYD,POSBUY,POSSELL,POSMKT}") String seedSecurities,
        @Value("${risk.max-order-quantity:1000000}") int maxOrderQuantity,
        @Value("${risk.max-order-notional-ticks:1000000000000000}") long maxOrderNotionalTicks,
        @Value("${risk.price.max-age-ms:30000}") long priceMaxAgeMillis,
        @Value("${risk.price.collar-bps:5000}") long priceCollarBps
    ) {
        this.seedAccounts = seedAccounts;
        this.seedSecurities = seedSecurities;
        this.maxOrderQuantity = maxOrderQuantity;
        this.maxOrderNotionalTicks = maxOrderNotionalTicks;
        this.priceMaxAgeMillis = priceMaxAgeMillis;
        this.priceCollarBps = priceCollarBps;
    }

    @PostConstruct
    public void bootstrap() {
        long version = 0;
        for (String value : split(seedAccounts)) {
            int accountId = Integer.parseInt(value);
            accounts.put(accountId, new AccountRecord(accountId, true, ++version));
            entitlements.put(entitlementKey("*", accountId),
                new EntitlementRecord("*", accountId, true, version));
            entitlements.put(entitlementKey("demo", accountId),
                new EntitlementRecord("demo", accountId, true, version));
        }
        for (String value : split(seedSecurities)) {
            String ticker = normalize(value);
            int securityId = nextSecurityId.getAndIncrement();
            securities.put(ticker, new SecurityRecord(securityId, ticker, true, false, Long.MIN_VALUE, 0L, ++version));
        }
        sourceVersion.set(version);
        highWatermark.set(version);
        metrics.sourceVersion(version);
        metrics.highWatermark(version);
        metrics.policyVersion(policyVersion);
        ready = version >= highWatermark.get();
        accountVersion = version;
        accountHighWatermark = version;
        securityVersion = version;
        securityHighWatermark = version;
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public RiskReason screen(int accountId, String ticker, int quantity, BigDecimal limitPrice,
                             boolean marketTrade, long nowMillis) {
        return screen("*", accountId, ticker, quantity, limitPrice, marketTrade, nowMillis);
    }

    public RiskReason screen(String principal, int accountId, String ticker, int quantity,
                             BigDecimal limitPrice, boolean marketTrade, long nowMillis) {
        if (!ready) return reject(RiskReason.CONTROL_STATE_STALE);
        if (killSwitch) return reject(RiskReason.KILL_SWITCH);
        AccountRecord account = accounts.get(accountId);
        if (account == null) return reject(RiskReason.UNKNOWN_ACCOUNT);
        if (!account.enabled()) return reject(RiskReason.ACCOUNT_DISABLED);
        EntitlementRecord entitlement = entitlements.get(entitlementKey(principal, accountId));
        if (entitlement == null) entitlement = entitlements.get(entitlementKey("*", accountId));
        if (entitlement == null || !entitlement.enabled()) return reject(RiskReason.NOT_ENTITLED);
        SecurityRecord security = securities.get(normalize(ticker));
        if (security == null) return reject(RiskReason.UNKNOWN_SECURITY);
        if (!security.enabled() || security.halted()) return reject(RiskReason.SECURITY_DISABLED);
        if (restrictions.getOrDefault(normalize(ticker), false)) return reject(RiskReason.RESTRICTED);
        if (quantity <= 0) return reject(RiskReason.INVALID);
        if (quantity > maxOrderQuantity) return reject(RiskReason.ORDER_SIZE);

        long validationPrice;
        if (marketTrade) {
            if (security.priceTicks() == Long.MIN_VALUE) return reject(RiskReason.PRICE_MISSING);
            if (nowMillis - security.priceTimeMillis() > priceMaxAgeMillis) return reject(RiskReason.PRICE_STALE);
            validationPrice = security.priceTicks();
        } else {
            if (limitPrice == null || limitPrice.signum() <= 0) return reject(RiskReason.INVALID);
            validationPrice = limitPrice.movePointRight(6).longValueExact();
            if (security.priceTicks() != Long.MIN_VALUE && nowMillis - security.priceTimeMillis() <= priceMaxAgeMillis) {
                long delta = Math.abs(validationPrice - security.priceTicks());
                if (delta * 10_000L > Math.max(1L, security.priceTicks()) * priceCollarBps) {
                    return reject(RiskReason.PRICE_COLLAR);
                }
            }
        }
        try {
            if (Math.multiplyExact((long) quantity, validationPrice) > maxOrderNotionalTicks) {
                return reject(RiskReason.ORDER_NOTIONAL);
            }
        } catch (ArithmeticException ex) {
            return reject(RiskReason.ORDER_NOTIONAL);
        }
        return RiskReason.ACCEPTED;
    }

    private RiskReason reject(RiskReason reason) {
        metrics.gatewayRejected(reason);
        return reason;
    }

    public void recordPrice(String ticker, long priceTicks, long sourceTimeMillis) {
        String normalized = normalize(ticker);
        securities.computeIfPresent(normalized, (key, old) -> {
            long version = nextVersion();
            return new SecurityRecord(old.securityId(), old.ticker(), old.enabled(), old.halted(),
                priceTicks, sourceTimeMillis, version);
        });
    }

    public synchronized void applyAccount(long epoch, long version, int accountId, boolean enabled) {
        requireNext(epoch, version);
        accounts.put(accountId, new AccountRecord(accountId, enabled, version));
        applied(version);
    }

    public synchronized void applySecurity(long epoch, long version, String ticker, boolean enabled, boolean halted) {
        requireNext(epoch, version);
        String normalized = normalize(ticker);
        SecurityRecord current = securities.get(normalized);
        int id = current == null ? nextSecurityId.getAndIncrement() : current.securityId();
        long px = current == null ? Long.MIN_VALUE : current.priceTicks();
        long pxTime = current == null ? 0L : current.priceTimeMillis();
        securities.put(normalized, new SecurityRecord(id, normalized, enabled, halted, px, pxTime, version));
        applied(version);
    }

    public synchronized void applyPolicy(long epoch, long version, long newPolicyVersion, boolean newKillSwitch) {
        requireNext(epoch, version);
        policyVersion = newPolicyVersion;
        killSwitch = newKillSwitch;
        metrics.policyVersion(newPolicyVersion);
        applied(version);
    }

    public synchronized void applyRestriction(String ticker, boolean restricted) {
        String normalized = normalize(ticker);
        if (!securities.containsKey(normalized)) {
            throw new IllegalArgumentException("unknown authoritative security: " + normalized);
        }
        restrictions.put(normalized, restricted);
    }

    public synchronized void beginExternalBootstrap() {
        externalMode = true;
        accountReady = false;
        securityReady = false;
        ready = false;
    }

    public synchronized void installAccountSnapshot(long epoch, long watermark, long observedHighWatermark,
                                                     List<AccountRecord> image,
                                                     List<EntitlementRecord> entitlementImage) {
        validateWatermark(epoch, watermark, observedHighWatermark, "account");
        accounts.clear();
        entitlements.clear();
        for (AccountRecord account : image) accounts.put(account.accountId(), account);
        for (EntitlementRecord entitlement : entitlementImage) {
            entitlements.put(entitlementKey(entitlement.principal(), entitlement.accountId()), entitlement);
        }
        accountEpoch = epoch;
        accountVersion = watermark;
        accountHighWatermark = observedHighWatermark;
        accountReady = watermark == observedHighWatermark;
        updateExternalReadiness();
    }

    public synchronized void installSecuritySnapshot(long epoch, long watermark, long observedHighWatermark,
                                                      List<SecurityRecord> image) {
        validateWatermark(epoch, watermark, observedHighWatermark, "security");
        securities.clear();
        int highestSecurityId = -1;
        for (SecurityRecord security : image) {
            securities.put(normalize(security.ticker()), security);
            highestSecurityId = Math.max(highestSecurityId, security.securityId());
        }
        nextSecurityId.set(highestSecurityId + 1);
        securityEpoch = epoch;
        securityVersion = watermark;
        securityHighWatermark = observedHighWatermark;
        securityReady = watermark == observedHighWatermark;
        updateExternalReadiness();
    }

    public synchronized boolean applyExternalAccount(long epoch, long version, int accountId,
                                                     boolean enabled) {
        if (epoch != accountEpoch) return invalidateExternal("account epoch changed");
        if (version <= accountVersion) return false;
        if (version != accountVersion + 1L) return invalidateExternal("account version gap");
        accounts.put(accountId, new AccountRecord(accountId, enabled, version));
        accountVersion = version;
        accountHighWatermark = Math.max(accountHighWatermark, version);
        accountReady = accountVersion >= accountHighWatermark;
        updateExternalReadiness();
        return true;
    }

    public synchronized boolean applyExternalEntitlement(long epoch, long version, String principal,
                                                          int accountId, boolean enabled) {
        if (epoch != accountEpoch) return invalidateExternal("account epoch changed");
        if (version <= accountVersion) return false;
        if (version != accountVersion + 1L) return invalidateExternal("account version gap");
        EntitlementRecord record = new EntitlementRecord(normalizePrincipal(principal), accountId,
            enabled, version);
        entitlements.put(entitlementKey(record.principal(), accountId), record);
        accountVersion = version;
        accountHighWatermark = Math.max(accountHighWatermark, version);
        accountReady = accountVersion >= accountHighWatermark;
        updateExternalReadiness();
        return true;
    }

    public synchronized boolean applyExternalSecurity(long epoch, long version, int securityId,
                                                      String ticker, boolean enabled, boolean halted) {
        if (epoch != securityEpoch) return invalidateExternal("security epoch changed");
        if (version <= securityVersion) return false;
        if (version != securityVersion + 1L) return invalidateExternal("security version gap");
        String normalized = normalize(ticker);
        SecurityRecord old = securities.get(normalized);
        long price = old == null ? Long.MIN_VALUE : old.priceTicks();
        long priceTime = old == null ? 0L : old.priceTimeMillis();
        securities.put(normalized, new SecurityRecord(securityId, normalized, enabled, halted,
            price, priceTime, version));
        securityVersion = version;
        securityHighWatermark = Math.max(securityHighWatermark, version);
        securityReady = securityVersion >= securityHighWatermark;
        updateExternalReadiness();
        return true;
    }

    private boolean invalidateExternal(String reason) {
        ready = false;
        metrics.gap();
        throw new IllegalArgumentException(reason);
    }

    private static void validateWatermark(long epoch, long watermark, long observedHighWatermark,
                                          String source) {
        if (epoch <= 0 || watermark < 0 || observedHighWatermark < watermark) {
            throw new IllegalArgumentException("invalid " + source + " snapshot watermark");
        }
    }

    private void updateExternalReadiness() {
        if (externalMode) ready = accountReady && securityReady;
        long applied = Math.min(accountVersion, securityVersion);
        long high = Math.min(accountHighWatermark, securityHighWatermark);
        metrics.sourceVersion(applied);
        metrics.highWatermark(high);
    }

    /** Atomic install point for subscribe-buffer-snapshot bootstrap at watermark W. */
    public synchronized void installSnapshot(Snapshot snapshot) {
        ready = false;
        if (snapshot.sourceEpoch() <= 0 || snapshot.watermark() < 0
            || snapshot.highWatermark() < snapshot.watermark()) {
            throw new IllegalArgumentException("invalid replica snapshot watermark");
        }
        accounts.clear();
        securities.clear();
        int highestSecurityId = -1;
        for (AccountRecord account : snapshot.accounts()) {
            accounts.put(account.accountId(), account);
        }
        for (SecurityRecord security : snapshot.securities()) {
            String ticker = normalize(security.ticker());
            if (ticker.isEmpty() || security.securityId() < 0) {
                throw new IllegalArgumentException("invalid security in replica snapshot");
            }
            securities.put(ticker, security);
            highestSecurityId = Math.max(highestSecurityId, security.securityId());
        }
        nextSecurityId.set(highestSecurityId + 1);
        sourceEpoch = snapshot.sourceEpoch();
        sourceVersion.set(snapshot.watermark());
        highWatermark.set(snapshot.highWatermark());
        policyVersion = snapshot.policyVersion();
        metrics.sourceVersion(snapshot.watermark());
        metrics.highWatermark(snapshot.highWatermark());
        metrics.policyVersion(snapshot.policyVersion());
        ready = snapshot.ready() && snapshot.watermark() == snapshot.highWatermark();
    }

    private void requireNext(long epoch, long version) {
        if (epoch != sourceEpoch || version != sourceVersion.get() + 1L) {
            ready = false;
            metrics.gap();
            throw new IllegalArgumentException("control version gap: epoch=" + epoch + " version=" + version
                + " expected=" + (sourceVersion.get() + 1L));
        }
    }

    private long nextVersion() {
        long version = sourceVersion.incrementAndGet();
        highWatermark.set(version);
        metrics.sourceVersion(version);
        metrics.highWatermark(version);
        return version;
    }

    private void applied(long version) {
        sourceVersion.set(version);
        highWatermark.accumulateAndGet(version, Math::max);
        metrics.sourceVersion(version);
        metrics.highWatermark(highWatermark.get());
        ready = version >= highWatermark.get();
    }

    public int securityId(String ticker) {
        SecurityRecord security = securities.get(normalize(ticker));
        return security == null ? -1 : security.securityId();
    }

    public Snapshot snapshot() {
        return new Snapshot(sourceEpoch, sourceVersion.get(), highWatermark.get(), policyVersion, ready,
            new ArrayList<>(accounts.values()), new ArrayList<>(securities.values()));
    }

    public List<EntitlementRecord> entitlementSnapshot() {
        return new ArrayList<>(entitlements.values());
    }

    public boolean ready() { return ready; }
    public long policyVersion() { return policyVersion; }
    public long sourceVersion() { return sourceVersion.get(); }
    public long accountVersion() { return accountVersion; }
    public long securityVersion() { return securityVersion; }
    public RiskMetrics metrics() { return metrics; }

    public static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static String entitlementKey(String principal, int accountId) {
        return normalizePrincipal(principal) + '\u0000' + accountId;
    }

    private static String normalizePrincipal(String principal) {
        return principal == null ? "" : principal.trim().toLowerCase(Locale.ROOT);
    }
}
