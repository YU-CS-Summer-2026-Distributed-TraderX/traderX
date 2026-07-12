package finos.traderx.algoengine.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-031: with no matching Parquet files present the query returns no usable data and the source
 * falls back to {@link SyntheticVolumeProfileSource}'s weights instead of failing. A second test
 * writes a tiny local Parquet store in the real {@code source/dt/symbol} layout and exercises the
 * TD-AE01 scoped-glob + bucketing path end to end. Both use a real, local (non-{@code gs://})
 * DuckDB query — no network, no GCS credential — so they run fully offline.
 */
class DuckDbVolumeProfileSourceTest {

  @Test
  void fallsBackToSyntheticWhenNoParquetFilesExist(@TempDir File emptyStore) {
    SyntheticVolumeProfileSource synthetic = new SyntheticVolumeProfileSource();
    DuckDbVolumeProfileSource source = new DuckDbVolumeProfileSource(
        "duckdb", emptyStore.getAbsolutePath(), 30, "", "", "", synthetic);

    List<Double> expected = synthetic.bucketWeights("IBM", 6);
    List<Double> actual = source.bucketWeights("IBM", 6);
    assertEquals(expected, actual);
  }

  @Test
  void disabledSourceAlwaysDefersToSynthetic() {
    SyntheticVolumeProfileSource synthetic = new SyntheticVolumeProfileSource();
    DuckDbVolumeProfileSource source = new DuckDbVolumeProfileSource(
        "synthetic", "gs://unused/ticks", 30, "", "", "", synthetic);

    assertEquals(synthetic.bucketWeights("IBM", 4), source.bucketWeights("IBM", 4));
  }

  /** TD-AE01: real trade rows in the symbol's own partition are read (via the scoped per-date glob),
   * quotes are excluded by the event_type predicate, and volume concentrates in the right bucket. */
  @Test
  void bucketsRealTradeVolumeFromScopedPartition(@TempDir File store) throws Exception {
    String dir = store.getAbsolutePath() + "/source=taq/dt=2025-02-03/symbol=IBM";
    Files.createDirectories(new File(dir).toPath());
    try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
        Statement stmt = conn.createStatement()) {
      // 3 trades at 09:xx (first quarter of the day) + a quote at 15:00 that must NOT be counted.
      stmt.execute("COPY (SELECT * FROM (VALUES "
          + "(TIMESTAMP '2025-02-03 09:30:00', 'trade'), "
          + "(TIMESTAMP '2025-02-03 09:31:00', 'trade'), "
          + "(TIMESTAMP '2025-02-03 09:32:00', 'trade'), "
          + "(TIMESTAMP '2025-02-03 15:00:00', 'quote')) t(ts, event_type)) "
          + "TO '" + dir + "/part.parquet' (FORMAT parquet)");
    }

    SyntheticVolumeProfileSource synthetic = new SyntheticVolumeProfileSource();
    DuckDbVolumeProfileSource source = new DuckDbVolumeProfileSource(
        "duckdb", store.getAbsolutePath(), 2, "2025-02-03", "", "", synthetic);

    List<Double> weights = source.bucketWeights("IBM", 4);
    // Buckets span the full 24h day (unchanged bucketing): 09:30 = sod 34200/86400 ≈ 0.4 -> bucket 1.
    // All three trades land there; the 15:00 quote is excluded by the event_type predicate.
    assertEquals(1.0, weights.get(1), 1e-9);
    assertEquals(0.0, weights.get(0) + weights.get(2) + weights.get(3), 1e-9);
    // and it is a real historical profile, not the synthetic fallback shape.
    assertTrue(weights != synthetic.bucketWeights("IBM", 4));
  }
}
