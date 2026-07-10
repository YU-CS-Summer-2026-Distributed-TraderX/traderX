package finos.traderx.algoengine.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-031: with no matching Parquet files present (the expected state today — bulk TAQ ingestion
 * is still blocked, per YU07's handoff), the query must return no usable data and the source falls
 * back to {@link SyntheticVolumeProfileSource}'s weights instead of failing. Uses a real, local
 * (non-{@code gs://}) DuckDB query — no network access, no GCS credential needed — so this runs
 * fully offline while still exercising the actual query/fallback code path.
 */
class DuckDbVolumeProfileSourceTest {

  @Test
  void fallsBackToSyntheticWhenNoParquetFilesExist(@TempDir File emptyStore) {
    SyntheticVolumeProfileSource synthetic = new SyntheticVolumeProfileSource();
    DuckDbVolumeProfileSource source = new DuckDbVolumeProfileSource(
        "duckdb", emptyStore.getAbsolutePath(), "", "", synthetic);

    List<Double> expected = synthetic.bucketWeights("IBM", 6);
    List<Double> actual = source.bucketWeights("IBM", 6);
    assertEquals(expected, actual);
  }

  @Test
  void disabledSourceAlwaysDefersToSynthetic() {
    SyntheticVolumeProfileSource synthetic = new SyntheticVolumeProfileSource();
    DuckDbVolumeProfileSource source = new DuckDbVolumeProfileSource(
        "synthetic", "gs://unused/ticks", "", "", synthetic);

    assertEquals(synthetic.bucketWeights("IBM", 4), source.bucketWeights("IBM", 4));
  }
}
