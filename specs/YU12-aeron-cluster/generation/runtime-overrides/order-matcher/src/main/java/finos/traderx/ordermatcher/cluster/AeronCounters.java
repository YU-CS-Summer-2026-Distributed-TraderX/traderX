package finos.traderx.ordermatcher.cluster;

import io.aeron.CncFileDescriptor;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.status.CountersReader;

import java.io.File;
import java.nio.MappedByteBuffer;

/**
 * Latency L1 harness (FR-OPL-06): every counter in a running driver's CnC file, one {@code id type value label} line
 * each, as Aeron's AeronStat prints them (aeron-samples is not on the member's classpath). Read-only: it maps the file
 * and reads, so it runs beside a live member — the agents' max cycle times, their work-cycle-exceeded counts and the
 * back-pressure counters per window.
 * <pre>env -u JAVA_TOOL_OPTIONS java -Xmx64m -cp '/opt/app/lib/*' finos.traderx.ordermatcher.cluster.AeronCounters [aeronDir]</pre>
 */
public final class AeronCounters {
    private AeronCounters() {
    }

    public static void main(final String[] args) {
        final File cnc = new File(args.length > 0 ? args[0] : "/dev/shm/aeron-cluster", CncFileDescriptor.CNC_FILE);
        final MappedByteBuffer buffer = IoUtil.mapExistingFile(cnc, "cnc");
        try {
            final DirectBuffer meta = CncFileDescriptor.createMetaDataBuffer(buffer);
            CncFileDescriptor.checkVersion(meta.getInt(CncFileDescriptor.cncVersionOffset(0)));
            final CountersReader counters = new CountersReader(
                CncFileDescriptor.createCountersMetaDataBuffer(buffer, meta),
                CncFileDescriptor.createCountersValuesBuffer(buffer, meta));
            counters.forEach((value, id, label) ->
                System.out.println(id + " " + counters.getCounterTypeId(id) + " " + value + " " + label));
        } finally {
            IoUtil.unmap(buffer);
        }
    }
}
