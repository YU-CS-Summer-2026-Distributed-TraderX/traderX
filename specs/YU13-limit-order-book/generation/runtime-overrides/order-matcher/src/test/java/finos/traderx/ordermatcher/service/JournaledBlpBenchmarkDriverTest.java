package finos.traderx.ordermatcher.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Standalone driver for the in-process journaled-BLP benchmark, for hosts where the Spring app
 * (and its /system/benchmarks endpoint) isn't deployable — e.g. a dedicated pinned GCE VM.
 * Env-gated so the ~2.25M-order run never joins the default suite:
 *   RUN_BLP_BENCH=1 ./gradlew test --tests '*JournaledBlpBenchmarkDriverTest*'
 */
class JournaledBlpBenchmarkDriverTest {

    @Test
    void runJournaledBlpBenchmark() throws Exception {
        assumeTrue(System.getenv("RUN_BLP_BENCH") != null, "set RUN_BLP_BENCH=1 to run");
        JournaledBlpBenchmarkService svc = new JournaledBlpBenchmarkService(
            250_000, 2_000_000, 65_536, "yielding", 1_024, 0,
            1_000, 4_096, 65_536, 8_192);
        svc.startRun(null, null, null, null, null, null);
        while (Boolean.TRUE.equals(svc.status().get("running"))) {
            Thread.sleep(200);
        }
        System.out.println("JOURNALED-BLP-RESULT " + svc.status());
        svc.destroy();
        if (!Boolean.TRUE.equals(svc.status().get("success"))) {
            throw new AssertionError("benchmark did not complete successfully: " + svc.status());
        }
    }
}
