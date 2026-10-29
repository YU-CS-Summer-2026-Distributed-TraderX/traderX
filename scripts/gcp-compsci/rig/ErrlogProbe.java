import io.aeron.cluster.codecs.mark.ClusterComponentType;
import io.aeron.cluster.service.ClusterMarkFile;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.errors.DistinctErrorLog;
import java.io.File;

/**
 * FR-PRG-03 G-pre fixture: a member's cluster dir as Aeron 1.51 itself writes it, both mark files, with MESSAGE recorded
 * in the error log of the SERVICE container's file only (cluster-mark-service-0.dat). That is where a snapshot record
 * the service refused is logged, not the consensus module's cluster-mark.dat. test_rig runs rig.sh's G-pre reader on it.
 *   java -cp aeron-all.jar ErrlogProbe.java DIR [MESSAGE]
 */
@SuppressWarnings("removal")
public class ErrlogProbe {
    public static void main(final String[] args) {
        final File dir = new File(args[0]);
        final SystemEpochClock clock = SystemEpochClock.INSTANCE;
        try (ClusterMarkFile consensus = new ClusterMarkFile(new File(dir, ClusterMarkFile.FILENAME),
                ClusterComponentType.CONSENSUS_MODULE, 1 << 20, clock, 0);
             ClusterMarkFile service = new ClusterMarkFile(new File(dir, ClusterMarkFile.markFilenameForService(0)),
                ClusterComponentType.CONTAINER, 1 << 20, clock, 0)) {
            new DistinctErrorLog(consensus.errorBuffer(), clock).record(new IllegalStateException("election timeout"));
            if (args.length > 1) new DistinctErrorLog(service.errorBuffer(), clock).record(new IllegalStateException(args[1]));
        }
    }
}
