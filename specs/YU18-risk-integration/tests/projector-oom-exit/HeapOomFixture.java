import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;

/** Disposable synthetic allocation; run only with the harness's 16 MiB heap. */
public final class HeapOomFixture {
    private static volatile byte[] allocation;

    public static void main(String[] args) throws Exception {
        var diagnostic = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        System.out.println("EXIT_FLAG=" + diagnostic.getVMOption("ExitOnOutOfMemoryError").getValue());
        System.out.println("MAX_RAM_PERCENT=" + diagnostic.getVMOption("MaxRAMPercentage").getValue());
        if (args.length > 0 && args[0].equals("application-fault")) {
            try {
                throw new IllegalStateException("synthetic application fault");
            } catch (IllegalStateException expected) {
                System.out.println("APPLICATION_FAULT_CAUGHT");
            }
        } else {
            try {
                allocation = new byte[32 * 1024 * 1024];
                throw new AssertionError("fixture did not exhaust its bounded heap");
            } catch (OutOfMemoryError expected) {
                System.out.println("HEAP_OOM_CAUGHT: " + expected.getMessage());
            }
        }
        Thread.sleep(250);
        System.out.println("POST_CATCH_ALIVE");
    }
}
