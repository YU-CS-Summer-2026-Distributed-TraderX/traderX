package finos.traderx.ordermatcher.risk;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;

/**
 * In-process Gateway replica: the versioned local validation image used for preliminary
 * admission screening (FR-IMRG02/06). Screening never performs a synchronous remote lookup
 * (FR-IMRG01); every record here was either part of the fixed configuration seed (the same
 * pre-loaded-initial-condition stance as the seed order book), applied through the versioned
 * control path, or installed by the durable control-feed bootstrap that subscribes/snapshots the
 * account/security universe from account-service/reference-data (ADR-019, YU04).
 *
 * <p>Slice-1 forward-port note: this Gateway is folded into the order-matcher edge (same JVM as
 * the BLP). {@code version} (the existing field) stays the internally-assigned, journal-facing
 * apply-order counter (ADR-020) — unchanged from YU03. {@code sourceVersion} (new in YU04) is the
 * account-service/reference-data outbox version that produced this record, used only by {@link
 * ControlFeedBootstrapState}'s gap/epoch detection; it never rides the BLP journal.
 */
@Component
public final class GatewayReplicaStore {
    public record AccountRecord(int accountId, boolean enabled, long version, long sourceVersion) {}
    public record SecurityRecord(int securityId, String ticker, boolean enabled, boolean halted,
                                 long priceTicks, long priceTimeMillis, long version, long sourceVersion) {}
    public record Snapshot(long sourceEpoch, long watermark, long highWatermark, long policyVersion,
                           boolean ready, List<AccountRecord> accounts, List<SecurityRecord> securities) {}

    /** Sentinel: no price observed yet (distinguishable from numeric zero, FR-IMRG09). */
    public static final long PRICE_NONE = Long.MIN_VALUE;

    /** {@code sourceVersion} for records not sourced from a durable feed (e.g. seeds, /risk/control/* admin). */
    private static final long NO_SOURCE_VERSION = 0L;

    private final ConcurrentHashMap<Integer, AccountRecord> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SecurityRecord> securities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> restrictions = new ConcurrentHashMap<>();
    private final AtomicLong sourceVersion = new AtomicLong();
    private final AtomicLong highWatermark = new AtomicLong();
    private final RiskMetrics metrics = new RiskMetrics();

    private final String seedAccounts;
    private final String seedSecurities;
    private final int maxOrderQuantity;
    private final long maxOrderNotionalTicks;
    private final long priceMaxAgeMillis;
    private final long priceCollarBps;
    private final int maxAccounts;
    private final int maxSecurities;
    private volatile boolean ready;
    private volatile boolean killSwitch;
    private volatile long sourceEpoch = 1L;
    private volatile long policyVersion = 1L;
    /** Ticker -> securityId authority (the BLP SymbolTable); installed by LmaxEngine at startup. */
    private volatile ToIntFunction<String> securityIdResolver;

    public GatewayReplicaStore(
        @Value("${risk.seed.accounts:22214,44044,52355,10031,62654}") String seedAccounts,
        @Value("${risk.seed.securities:IBM,MSFT,JPM,GS,NVDA,C,META,MS,BAC,PARITYA,PARITYB,PARITYC,PARITYD,PARITYE,POSBUY,POSSELL,POSMKT}") String seedSecurities,
        @Value("${risk.max-order-quantity:1000000}") int maxOrderQuantity,
        @Value("${risk.max-order-notional-ticks:1000000000000000}") long maxOrderNotionalTicks,
        @Value("${risk.price.max-age-ms:30000}") long priceMaxAgeMillis,
        @Value("${risk.price.collar-bps:5000}") long priceCollarBps,
        @Value("${risk.max-accounts:4096}") int maxAccounts,
        @Value("${blp.books.max-securities:4096}") int maxSecurities
    ) {
        this.seedAccounts = seedAccounts;
        this.seedSecurities = seedSecurities;
        this.maxOrderQuantity = maxOrderQuantity;
        this.maxOrderNotionalTicks = maxOrderNotionalTicks;
        this.priceMaxAgeMillis = priceMaxAgeMillis;
        this.priceCollarBps = priceCollarBps;
        this.maxAccounts = maxAccounts;
        this.maxSecurities = maxSecurities;
    }

    @PostConstruct
    public void seed() {
        long version = 0;
        for (String value : split(seedAccounts)) {
            int accountId = Integer.parseInt(value);
            accounts.put(accountId, new AccountRecord(accountId, true, ++version, NO_SOURCE_VERSION));
        }
        for (String value : split(seedSecurities)) {
            String ticker = normalize(value);
            // securityId -1 until alignSecurityIds installs the SymbolTable authority.
            securities.put(ticker, new SecurityRecord(-1, ticker, true, false, PRICE_NONE, 0L, ++version, NO_SOURCE_VERSION));
        }
        sourceVersion.set(version);
        highWatermark.set(version);
        metrics.sourceVersion(version);
        metrics.highWatermark(version);
        metrics.policyVersion(policyVersion);
        // NOT ready yet: readiness is granted once BOTH durable control feeds (account, security)
        // install a valid snapshot and catch up to their observed high watermark (FR-IMRG05,
        // ReplicaBootstrap). The config seeds above are a safe known initial condition while that
        // catch-up is in progress; names outside the installed image fail closed as
        // UNKNOWN_SECURITY / UNKNOWN_ACCOUNT until admitted through the control stream.
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Install the ticker->securityId authority (the journal-persistent BLP SymbolTable) and
     * re-key every replica record to it. The stale-branch design let the replica assign its own
     * ids; on this branch the SymbolTable already persists ids across restarts (symbols.tab), so
     * it must stay the single authority or replayed journals would disagree with replica state.
     */
    public synchronized void alignSecurityIds(ToIntFunction<String> resolver) {
        this.securityIdResolver = resolver;
        for (var entry : securities.entrySet()) {
            SecurityRecord r = entry.getValue();
            int id = resolver.applyAsInt(r.ticker());
            if (id != r.securityId()) {
                entry.setValue(new SecurityRecord(id, r.ticker(), r.enabled(), r.halted(),
                    r.priceTicks(), r.priceTimeMillis(), r.version(), r.sourceVersion()));
            }
        }
    }

    // ----- preliminary screening (local state only; never final acceptance — FR-IMRG06/07) -----

    public RiskReason screen(int accountId, String ticker, int quantity, BigDecimal limitPrice,
                             boolean marketTrade, long nowMillis) {
        if (!ready) return reject(RiskReason.CONTROL_STATE_STALE);
        if (killSwitch) return reject(RiskReason.KILL_SWITCH);
        AccountRecord account = accounts.get(accountId);
        if (account == null) return reject(RiskReason.UNKNOWN_ACCOUNT);
        if (!account.enabled()) return reject(RiskReason.ACCOUNT_DISABLED);
        SecurityRecord security = securities.get(normalize(ticker));
        if (security == null) return reject(RiskReason.UNKNOWN_SECURITY);
        if (!security.enabled() || security.halted()) return reject(RiskReason.SECURITY_DISABLED);
        if (restrictions.getOrDefault(normalize(ticker), false)) return reject(RiskReason.RESTRICTED);
        if (quantity <= 0) return reject(RiskReason.INVALID);
        if (quantity > maxOrderQuantity) return reject(RiskReason.ORDER_SIZE);

        long validationPrice;
        if (marketTrade) {
            if (security.priceTicks() == PRICE_NONE) return reject(RiskReason.PRICE_MISSING);
            if (nowMillis - security.priceTimeMillis() > priceMaxAgeMillis) return reject(RiskReason.PRICE_STALE);
            validationPrice = security.priceTicks();
        } else {
            if (limitPrice == null || limitPrice.signum() <= 0) return reject(RiskReason.INVALID);
            validationPrice = limitPrice.movePointRight(6).longValueExact();
            if (security.priceTicks() != PRICE_NONE && nowMillis - security.priceTimeMillis() <= priceMaxAgeMillis) {
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

    // ----- replica feeds (edge threads; versions assigned internally in slice 1) ---------------

    /** Price freshness feed: rides the existing pricing NATS path at the edge (FR-IMRG09). */
    public void recordPrice(String ticker, long priceTicks, long sourceTimeMillis) {
        securities.computeIfPresent(normalize(ticker), (key, old) ->
            new SecurityRecord(old.securityId(), old.ticker(), old.enabled(), old.halted(),
                priceTicks, sourceTimeMillis, nextVersion(), old.sourceVersion()));
    }

    /** Used by the durable control-feed bootstrap (YU04, ADR-019) — carries the source's own version. */
    public synchronized long applyAccount(int accountId, boolean enabled, long sourceVersionValue) {
        requireCapacity(accounts.containsKey(accountId), accounts.size(), maxAccounts, "account");
        long version = nextVersion();
        accounts.put(accountId, new AccountRecord(accountId, enabled, version, sourceVersionValue));
        return version;
    }

    /** Used by the {@code /risk/control/*} admin API path (no source version — YU03 behavior, unchanged). */
    public long applyAccount(int accountId, boolean enabled) {
        return applyAccount(accountId, enabled, NO_SOURCE_VERSION);
    }

    /** Used by the durable control-feed bootstrap (YU04, ADR-019) — carries the source's own version. */
    public synchronized long applySecurity(String ticker, boolean enabled, boolean halted, long sourceVersionValue) {
        String normalized = normalize(ticker);
        if (normalized.isEmpty()) throw new IllegalArgumentException("empty ticker");
        SecurityRecord current = securities.get(normalized);
        requireCapacity(current != null, securities.size(), maxSecurities, "security");
        ToIntFunction<String> resolver = securityIdResolver;
        int id = current != null ? current.securityId()
            : resolver != null ? resolver.applyAsInt(normalized) : -1;
        long px = current == null ? PRICE_NONE : current.priceTicks();
        long pxTime = current == null ? 0L : current.priceTimeMillis();
        long version = nextVersion();
        securities.put(normalized, new SecurityRecord(id, normalized, enabled, halted, px, pxTime, version, sourceVersionValue));
        return version;
    }

    /** Used by the {@code /risk/control/*} admin API path (no source version — YU03 behavior, unchanged). */
    public long applySecurity(String ticker, boolean enabled, boolean halted) {
        return applySecurity(ticker, enabled, halted, NO_SOURCE_VERSION);
    }

    public synchronized long applyPolicy(long newPolicyVersion, boolean newKillSwitch) {
        policyVersion = newPolicyVersion;
        killSwitch = newKillSwitch;
        metrics.policyVersion(newPolicyVersion);
        return nextVersion();
    }

    public synchronized long applyRestriction(String ticker, boolean restricted) {
        String normalized = normalize(ticker);
        if (!securities.containsKey(normalized)) {
            throw new IllegalArgumentException("unknown authoritative security: " + normalized);
        }
        restrictions.put(normalized, restricted);
        return nextVersion();
    }

    // ----- recovery-boundary alignment (cold path; BLP journal/snapshot state is authoritative) --

    /** Re-align the replica's policy view to the recovered BLP state so a restart does not
     *  silently forget a journaled kill switch at the edge (BLP would still reject — ADR-018 —
     *  but the Gateway should fail fast too). No version bump: this is not a new control fact. */
    public void overridePolicyFromAuthority(long authoritativePolicyVersion, boolean authoritativeKillSwitch) {
        this.policyVersion = authoritativePolicyVersion;
        this.killSwitch = authoritativeKillSwitch;
        metrics.policyVersion(authoritativePolicyVersion);
    }

    /** Recovery-boundary alignment of one security's control state from the recovered BLP. */
    public synchronized void overrideSecurityFromAuthority(String ticker, boolean enabled, boolean restricted) {
        String normalized = normalize(ticker);
        SecurityRecord current = securities.get(normalized);
        if (current == null) {
            ToIntFunction<String> resolver = securityIdResolver;
            int id = resolver != null ? resolver.applyAsInt(normalized) : -1;
            current = new SecurityRecord(id, normalized, enabled, false, PRICE_NONE, 0L, sourceVersion.get(), NO_SOURCE_VERSION);
        }
        securities.put(normalized, new SecurityRecord(current.securityId(), normalized, enabled,
            current.halted(), current.priceTicks(), current.priceTimeMillis(), current.version(), current.sourceVersion()));
        restrictions.put(normalized, restricted);
    }

    /** Recovery-boundary alignment of one account's control state from the recovered BLP. */
    public synchronized void overrideAccountFromAuthority(int accountId, boolean enabled) {
        AccountRecord current = accounts.get(accountId);
        long version = current == null ? sourceVersion.get() : current.version();
        long existingSourceVersion = current == null ? NO_SOURCE_VERSION : current.sourceVersion();
        accounts.put(accountId, new AccountRecord(accountId, enabled, version, existingSourceVersion));
    }

    // ----- readiness (fail closed: FR-IMRG05-lite for the in-process replica) -------------------

    /** Granted by {@link ReplicaBootstrap} once BOTH durable control feeds catch up (FR-IMRG05). */
    public void markReady() {
        ready = true;
    }

    public void markNotReady() {
        ready = false;
    }

    public boolean ready() { return ready; }
    public boolean killSwitch() { return killSwitch; }
    public long policyVersion() { return policyVersion; }
    public long sourceVersion() { return sourceVersion.get(); }
    public long sourceEpoch() { return sourceEpoch; }
    public int accountCount() { return accounts.size(); }
    public int securityCount() { return securities.size(); }
    public RiskMetrics metrics() { return metrics; }

    public int securityId(String ticker) {
        SecurityRecord security = securities.get(normalize(ticker));
        return security == null ? -1 : security.securityId();
    }

    public Snapshot snapshot() {
        return new Snapshot(sourceEpoch, sourceVersion.get(), highWatermark.get(), policyVersion, ready,
            new ArrayList<>(accounts.values()), new ArrayList<>(securities.values()));
    }

    private long nextVersion() {
        long version = sourceVersion.incrementAndGet();
        highWatermark.set(version);
        metrics.sourceVersion(version);
        metrics.highWatermark(version);
        return version;
    }

    private static void requireCapacity(boolean existing, int size, int capacity, String type) {
        if (!existing && size >= capacity) throw new IllegalStateException(type + " capacity exceeded");
    }

    public static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }
}
