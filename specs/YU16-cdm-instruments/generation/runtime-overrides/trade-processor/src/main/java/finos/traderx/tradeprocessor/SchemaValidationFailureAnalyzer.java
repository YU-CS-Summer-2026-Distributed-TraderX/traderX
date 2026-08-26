package finos.traderx.tradeprocessor;

import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns Hibernate's schema-validation failure into a startup message that names the fix.
 *
 * <p><b>Why this exists.</b> Schema changes ship in the {@code database-init-sql} configmap, and an
 * init configmap only ever executes against an EMPTY data directory. A long-lived volume therefore
 * drifts from what the pack declares and nothing notices until a query fails. That has now happened
 * twice on the cluster rig — the {@code orderbook.status} CHECK constraint, and
 * {@code orderbook.traceid}, where 271 order writes were rejected one at a time and the symptom
 * surfaced as an unrelated failing proof two subsystems away.
 *
 * <p>{@code ddl-auto=validate} makes that drift fail at startup instead. Hibernate's own message
 * names the table and column but stops there; this analyzer adds where the statement belongs and
 * what has to be restarted for it to run, so the reader is sent to the DDL rather than to the
 * Hibernate documentation.
 */
public class SchemaValidationFailureAnalyzer
    extends AbstractFailureAnalyzer<SchemaManagementException> {

  @Override
  protected FailureAnalysis analyze(
      final Throwable rootFailure, final SchemaManagementException cause) {
    return new FailureAnalysis(
        cause.getMessage(),
        """
        The database this service is pointed at is missing schema its entities read. Startup is \
        refused deliberately: serving traffic would reject every write through the affected table \
        one query at a time, with nothing to connect the rejections back to this cause.

        To fix, add the idempotent statement to the 900-migrations.sql key of \
        kubernetes-runtime/manifests/base/database-init-configmap.yaml, e.g.

            ALTER TABLE <table> ADD COLUMN IF NOT EXISTS <column> <type>;

        then restart the database pod. Its schema-migrate init container applies 900-migrations.sql \
        to an already-populated volume; the 001-initialSchema.sql key runs ONLY on an empty data \
        directory and will not reach a live rig.""",
        cause);
  }
}
