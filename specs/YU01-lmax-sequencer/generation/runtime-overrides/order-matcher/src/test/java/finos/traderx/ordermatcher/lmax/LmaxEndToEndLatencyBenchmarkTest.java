package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.api.OrderCreateRequest;
import finos.traderx.ordermatcher.model.OrderSide;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Local in-process end-to-end latency benchmark: service edge -> input disruptor ->
 * journal/replicator -> BLP -> output ring -> marshaller ack. It intentionally excludes
 * HTTP, real NATS network I/O, and external database flush cost.
 */
@SpringBootTest(properties = {
    // MySQL compat mode (not PostgreSQL): the projector's batch upserts use MariaDB dialect
    // (INSERT IGNORE / ON DUPLICATE KEY UPDATE) since the read-model DB moved off Postgres.
    "spring.datasource.url=jdbc:h2:mem:lmaxe2e;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=sa",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "order.matcher.publisher=noop",
    "order.matcher.pricing-subscriber.enabled=false",
    "order.matcher.seed-enabled=false",
    "journal.enabled=false",
    "output.projector.batch-size=1000000",
    "order.matcher.trade-service-url=http://localhost:1/trade/"
})
class LmaxEndToEndLatencyBenchmarkTest {
    private static final int WARMUP = 200;

    @Autowired
    private OrderMatcherService service;

    @Test
    void serviceToMarshallerAckLatencyPercentiles() {
        assumeTrue(Boolean.getBoolean("outputLatencyBenchmark"),
            "run with ./gradlew outputLatencyBenchmark");

        int iterations = Integer.getInteger("endToEndLatencyBenchmark.iterations", 1_000);
        OrderCreateRequest[] requests = new OrderCreateRequest[iterations + WARMUP];
        for (int i = 0; i < requests.length; i++) {
            requests[i] = request(20_000 + (i & 127), "E2E", OrderSide.Buy, 100 + (i & 31), "101.125");
        }

        for (int i = 0; i < WARMUP; i++) {
            service.createOrder(requests[i]);
        }

        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long before = System.nanoTime();
            service.createOrder(requests[WARMUP + i]);
            samples[i] = System.nanoTime() - before;
        }
        Arrays.sort(samples);
        System.out.printf(
            "LMAX in-process end-to-end create latency: iterations=%d warmup=%d p50=%d ns p95=%d ns p99=%d ns max=%d ns%n",
            iterations, WARMUP, percentile(samples, 50), percentile(samples, 95), percentile(samples, 99),
            samples[samples.length - 1]);
    }

    private static OrderCreateRequest request(int accountId, String security, OrderSide side, int qty, String limit) {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setAccountId(accountId);
        request.setSecurity(security);
        request.setSide(side);
        request.setQuantity(qty);
        request.setLimitPrice(new BigDecimal(limit));
        return request;
    }

    private static long percentile(long[] sortedSamples, int percentile) {
        int index = (int) Math.ceil((percentile / 100.0) * sortedSamples.length) - 1;
        return sortedSamples[Math.max(0, Math.min(index, sortedSamples.length - 1))];
    }
}
