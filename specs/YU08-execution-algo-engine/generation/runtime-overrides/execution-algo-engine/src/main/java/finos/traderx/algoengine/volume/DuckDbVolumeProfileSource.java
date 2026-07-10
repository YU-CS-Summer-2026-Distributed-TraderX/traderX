package finos.traderx.algoengine.volume;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
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
 */
@Component
@Primary
public class DuckDbVolumeProfileSource implements VolumeProfileSource {
  private static final Logger log = LoggerFactory.getLogger(DuckDbVolumeProfileSource.class);

  private final String storePath;
  private final boolean enabled;
  private final SyntheticVolumeProfileSource fallback;
  private final String gcsHmacKeyId;
  private final String gcsHmacSecret;

  public DuckDbVolumeProfileSource(
      @Value("${algo.volume-profile.source:synthetic}") String source,
      @Value("${algo.volume-profile.duckdb.path:gs://traderx-501015-tick-store/ticks}") String storePath,
      @Value("${GCS_HMAC_KEY_ID:}") String gcsHmacKeyId,
      @Value("${GCS_HMAC_SECRET_ACCESS_KEY:}") String gcsHmacSecret,
      SyntheticVolumeProfileSource fallback) {
    this.enabled = "duckdb".equalsIgnoreCase(source);
    this.storePath = storePath;
    this.gcsHmacKeyId = gcsHmacKeyId;
    this.gcsHmacSecret = gcsHmacSecret;
    this.fallback = fallback;
  }

  @Override
  public List<Double> bucketWeights(String security, int bucketCount) {
    if (!enabled) {
      return fallback.bucketWeights(security, bucketCount);
    }
    List<Double> historical = queryHistoricalWeights(security, bucketCount);
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
    try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
      if (storePath.startsWith("gs://")) {
        try (var stmt = conn.createStatement()) {
          stmt.execute("INSTALL httpfs; LOAD httpfs;");
          stmt.execute("CREATE SECRET (TYPE gcs, KEY_ID '" + gcsHmacKeyId.replace("'", "")
              + "', SECRET '" + gcsHmacSecret.replace("'", "") + "')");
        }
      }
      String sql = "SELECT floor(extract('hour' from ts) * 3600 + extract('minute' from ts) * 60 "
          + "+ extract('second' from ts)) AS sod, count(*) AS trade_count "
          + "FROM read_parquet(?, hive_partitioning = true) "
          + "WHERE event_type = 'trade' AND symbol = ? "
          + "GROUP BY sod";
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, storePath + "/**/*.parquet");
        ps.setString(2, security);
        try (ResultSet rs = ps.executeQuery()) {
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
      }
    } catch (Exception ex) {
      log.warn("volume-profile query against {} failed ({}) -- treating as no data", storePath, ex.toString());
      return null;
    }
  }
}
