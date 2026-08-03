package finos.traderx.algoengine.volume;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * research.md Decision 7 / ADR-031: queries YU07's unified {@code ticks} Parquet store for
 * historical intraday trade-volume distribution, bucketing it into {@code bucketCount} equal
 * intraday time slices. Falls back to {@link SyntheticVolumeProfileSource}'s weights whenever the
 * query returns no matching rows, or fails outright (e.g. the store has no files yet) — VWAP never
 * blocks on data availability. Only active when {@code algo.volume-profile.source=duckdb}; the
 * default {@code synthetic} never constructs this bean's query path.
 *
 * <p>TD-AE01 resolution: the store is Hive-partitioned {@code source/dt/symbol}, so a recursive
 * {@code <root>/**}{@code /*.parquet} glob forces DuckDB to list <em>every</em> object in the store
 * (all symbols, all dates) before it can prune — ~224s wall-clock for a liquid symbol, paid
 * synchronously on the {@code POST /algo/orders} thread. Instead this builds explicit
 * {@code <root>/source=taq/dt=<date>/symbol=<SYM>/*.parquet} globs for a bounded lookback window and
 * resolves them with DuckDB's {@code glob()} (which, unlike {@code read_parquet}, tolerates patterns
 * that match nothing — so non-trading days are simply skipped). Only the symbol's own files are ever
 * listed or read. The per-(symbol,bucketCount) result is cached for the process lifetime since an
 * intraday volume profile is a slow-moving average, so the scan is paid once, not per order.
 */
@Component
@Primary
public class DuckDbVolumeProfileSource implements VolumeProfileSource {
  private static final Logger log = LoggerFactory.getLogger(DuckDbVolumeProfileSource.class);

  // Symbol is interpolated into the GCS glob path (a trust boundary — it arrives on the order
  // request), so it is constrained to the ticker charset before it can reach the query. Anything
  // else is treated as no data and falls back to synthetic.
  private static final Pattern SAFE_SYMBOL = Pattern.compile("[A-Za-z0-9._-]{1,32}");

  private final String storePath;
  private final boolean enabled;
  private final int lookbackDays;
  private final LocalDate asOf;
  private final SyntheticVolumeProfileSource fallback;
  private final String gcsHmacKeyId;
  private final String gcsHmacSecret;
  // ponytail: unbounded, no TTL — profiles are cached for the process lifetime. Fine for a bounded
  // symbol universe and a slow-moving intraday shape; add size/time eviction if the universe or
  // intraday freshness ever demands it. The first order per symbol pays the scan; the rest are free.
  private final ConcurrentMap<String, List<Double>> cache = new ConcurrentHashMap<>();

  public DuckDbVolumeProfileSource(
      @Value("${algo.volume-profile.source:synthetic}") String source,
      @Value("${algo.volume-profile.duckdb.path:gs://traderx-501015-tick-store/ticks}") String storePath,
      @Value("${algo.volume-profile.lookback-days:30}") int lookbackDays,
      @Value("${algo.volume-profile.as-of:}") String asOf,
      @Value("${GCS_HMAC_KEY_ID:}") String gcsHmacKeyId,
      @Value("${GCS_HMAC_SECRET_ACCESS_KEY:}") String gcsHmacSecret,
      SyntheticVolumeProfileSource fallback) {
    this.enabled = "duckdb".equalsIgnoreCase(source);
    this.storePath = storePath;
    this.lookbackDays = Math.max(1, lookbackDays);
    // Window end date. Defaults to today; overridable so a frozen historical store (or a backfill)
    // can point the window at where its data actually lives, independent of wall-clock.
    this.asOf = asOf.isBlank() ? LocalDate.now() : LocalDate.parse(asOf.trim());
    this.gcsHmacKeyId = gcsHmacKeyId;
    this.gcsHmacSecret = gcsHmacSecret;
    this.fallback = fallback;
  }

  @Override
  public List<Double> bucketWeights(String security, int bucketCount) {
    if (!enabled) {
      return fallback.bucketWeights(security, bucketCount);
    }
    List<Double> historical = cache.computeIfAbsent(security + ":" + bucketCount,
        k -> queryHistoricalWeights(security, bucketCount));
    if (historical != null) {
      return historical;
    }
    log.info("no historical volume for {} at {} -- falling back to synthetic profile (ADR-031)",
        security, storePath);
    return fallback.bucketWeights(security, bucketCount);
  }

  /** Returns normalized per-bucket weights from real trade volume, or {@code null} if there is no
   * usable historical data (zero matching rows, or the query itself failed) — the caller falls
   * back to the synthetic profile in either case. */
  List<Double> queryHistoricalWeights(String security, int bucketCount) {
    if (!SAFE_SYMBOL.matcher(security).matches()) {
      log.warn("volume-profile: symbol {} outside ticker charset -- treating as no data", security);
      return null;
    }
    try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
        Statement stmt = conn.createStatement()) {
      if (storePath.startsWith("gs://")) {
        stmt.execute("INSTALL httpfs; LOAD httpfs;");
        stmt.execute("CREATE SECRET (TYPE gcs, KEY_ID '" + gcsHmacKeyId.replace("'", "")
            + "', SECRET '" + gcsHmacSecret.replace("'", "") + "')");
      }
      List<String> files = resolveFiles(stmt, security);
      if (files.isEmpty()) {
        return null;
      }
      String fileList = files.stream().map(f -> "'" + f + "'").collect(Collectors.joining(","));
      String sql = "SELECT floor(extract('hour' from ts) * 3600 + extract('minute' from ts) * 60 "
          + "+ extract('second' from ts)) AS sod, count(*) AS trade_count "
          + "FROM read_parquet([" + fileList + "], hive_partitioning = true) "
          + "WHERE event_type = 'trade' "
          + "GROUP BY sod";
      try (ResultSet rs = stmt.executeQuery(sql)) {
        double[] counts = new double[bucketCount];
        double total = 0;
        boolean any = false;
        while (rs.next()) {
          any = true;
          long secondOfDay = rs.getLong("sod");
          int bucket = (int) Math.min(bucketCount - 1, (secondOfDay * bucketCount) / 86400);
          double c = rs.getLong("trade_count");
          counts[bucket] += c;
          total += c;
        }
        if (!any || total <= 0) {
          return null;
        }
        List<Double> weights = new ArrayList<>(bucketCount);
        for (double c : counts) {
          weights.add(c / total);
        }
        return weights;
      }
    } catch (Exception ex) {
      log.warn("volume-profile query against {} failed ({}) -- treating as no data", storePath, ex.toString());
      return null;
    }
  }

  // Both source partitions that can hold event_type='trade' rows (data-model.md): TAQ trades and
  // TraderX's own live trades. Quotes/price-ticks live under these too but are filtered by the
  // event_type predicate; the point of enumerating source explicitly is to avoid a source=* wildcard
  // that would re-trigger the full-store listing.
  private static final String[] TRADE_SOURCES = {"taq", "live"};

  /** Resolves the symbol's actual Parquet files over a bounded lookback window without a full-store
   * listing: builds one explicit {@code source=<s>/dt=<date>/symbol=<SYM>} glob per candidate day
   * and source, and lets {@code glob()} drop the ones that match nothing (weekends, holidays,
   * pre-history, absent sources). */
  private List<String> resolveFiles(Statement stmt, String security) throws java.sql.SQLException {
    List<String> candidates = new ArrayList<>(lookbackDays * TRADE_SOURCES.length);
    for (int i = 0; i < lookbackDays; i++) {
      String dt = asOf.minusDays(i).toString();
      for (String src : TRADE_SOURCES) {
        candidates.add("'" + storePath + "/source=" + src + "/dt=" + dt
            + "/symbol=" + security + "/*.parquet'");
      }
    }
    List<String> files = new ArrayList<>();
    try (ResultSet rs = stmt.executeQuery(
        "SELECT file FROM glob([" + String.join(",", candidates) + "])")) {
      while (rs.next()) {
        files.add(rs.getString("file"));
      }
    }
    return files;
  }
}
