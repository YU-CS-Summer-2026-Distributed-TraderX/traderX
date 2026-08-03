package finos.traderx.ordermatcher.lmax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;

/**
 * Optional CPU pinning for the BLP thread (perf profile, NFR-09B06). Isolated in its own class so
 * the OpenHFT/JNA affinity API never appears in the no-GC hot-path classes scanned by the banned-API
 * gate (SC-09B13). Invoked exactly once, on the BLP thread's start hook — never in the event loop.
 *
 * <p>Container note: pinning calls {@code sched_setaffinity} for the current thread, which Docker
 * allows without extra capabilities as long as the target CPU is inside the container's cpuset. Any
 * failure (missing native lib, CPU outside the set) is logged and ignored — the engine runs unpinned.
 */
public final class CpuAffinity {
    private static final Logger log = LoggerFactory.getLogger(CpuAffinity.class);

    private CpuAffinity() {
    }

    /** Pin the calling thread to {@code cpu}; a negative value disables pinning (demo default). */
    public static void pinCurrentThread(int cpu) {
        if (cpu < 0) {
            return;
        }
        try {
            BitSet set = new BitSet();
            set.set(cpu);
            net.openhft.affinity.Affinity.setAffinity(set);
            int running = net.openhft.affinity.Affinity.getCpu();
            log.info("Pinned BLP thread '{}' to CPU {} (now scheduled on CPU {})",
                Thread.currentThread().getName(), cpu, running);
        } catch (Throwable t) {
            // Never fail startup over pinning — availability over the perf optimization.
            log.warn("CPU pinning to {} failed; continuing unpinned: {}", cpu, t.toString());
        }
    }
}
