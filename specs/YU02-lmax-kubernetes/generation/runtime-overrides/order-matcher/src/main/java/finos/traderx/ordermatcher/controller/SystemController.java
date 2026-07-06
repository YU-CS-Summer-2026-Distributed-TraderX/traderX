package finos.traderx.ordermatcher.controller;

import finos.traderx.messaging.Publisher;
import finos.traderx.messaging.nats.NatsJSONPublisher;
import finos.traderx.ordermatcher.api.OpenCountResponse;
import finos.traderx.ordermatcher.api.OrderResponse;
import finos.traderx.ordermatcher.service.JournaledBlpBenchmarkService;
import finos.traderx.ordermatcher.service.LiveThroughputService;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/")
public class SystemController {
    private final OrderMatcherService orderMatcherService;
    private final Publisher<OrderResponse> orderPublisher;
    private final LiveThroughputService liveThroughputService;
    private final JournaledBlpBenchmarkService journaledBlpBenchmarkService;

    public SystemController(OrderMatcherService orderMatcherService, Publisher<OrderResponse> orderPublisher,
                            LiveThroughputService liveThroughputService,
                            JournaledBlpBenchmarkService journaledBlpBenchmarkService) {
        this.orderMatcherService = orderMatcherService;
        this.orderPublisher = orderPublisher;
        this.liveThroughputService = liveThroughputService;
        this.journaledBlpBenchmarkService = journaledBlpBenchmarkService;
    }

    @GetMapping({"/health", "/healthz", "/system/health"})
    public Map<String, Object> health() {
        Map<String, Object> payload = new LinkedHashMap<>(orderMatcherService.health());
        Map<String, Object> messageBus = describeMessageBus();
        payload.put("messageBus", messageBus);
        payload.put("journaledBlpBenchmark", journaledBlpBenchmarkService.status());
        payload.put("status", "connected".equals(messageBus.get("status")) ? "ok" : "degraded");
        return payload;
    }

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public String metrics() {
        double connectedGauge = orderPublisher != null && orderPublisher.isConnected() ? 1.0 : 0.0;
        StringBuilder sb = new StringBuilder(orderMatcherService.prometheusMetrics());
        liveThroughputService.appendPrometheusMetrics(sb);
        journaledBlpBenchmarkService.appendPrometheusMetrics(sb);
        sb.append(
            "# HELP traderx_messagebus_connected Message bus connectivity gauge.\n"
            + "# TYPE traderx_messagebus_connected gauge\n"
            + "traderx_messagebus_connected{component=\"order-matcher\",role=\"publisher\"} " + connectedGauge + "\n");
        return sb.toString();
    }

    @GetMapping("/orders/open-count")
    public OpenCountResponse openCount() {
        return orderMatcherService.openCounts();
    }

    // ---- journaled-BLP benchmark ------------------------------------------------------------

    @GetMapping("/system/benchmarks/journaled-blp")
    public Map<String, Object> journaledBlpBenchmarkStatus() {
        return journaledBlpBenchmarkService.status();
    }

    /**
     * Trigger the journaled-BLP benchmark. The harness wires the same hot-path topology as
     * production (journaler + replicator gating the BLP) so the results reflect the actual
     * ceiling on YU02-lmax-kubernetes.
     *
     * <p>Set {@code simulatedRttMs=0} (default) for journaling-only mode (YU02-lmax-kubernetes
     * topology with ReplicatorStub). Set {@code simulatedRttMs=1..10} to model the NATS
     * round-trip on the HA branch and measure the batch-ACK ceiling directly.
     */
    @PostMapping("/system/benchmarks/journaled-blp/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> runJournaledBlpBenchmark(
        @RequestParam(value = "warmupOrders", required = false) Integer warmupOrders,
        @RequestParam(value = "measuredOrders", required = false) Integer measuredOrders,
        @RequestParam(value = "ringSize", required = false) Integer ringSize,
        @RequestParam(value = "waitStrategy", required = false) String waitStrategy,
        @RequestParam(value = "batchRecords", required = false) Integer batchRecords,
        @RequestParam(value = "simulatedRttMs", required = false) Integer simulatedRttMs
    ) {
        return journaledBlpBenchmarkService.startRun(
            warmupOrders, measuredOrders, ringSize, waitStrategy, batchRecords, simulatedRttMs);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private Map<String, Object> describeMessageBus() {
        Map<String, Object> payload = new LinkedHashMap<>();
        String status = orderPublisher != null && orderPublisher.isConnected() ? "connected" : "disconnected";
        String address = "unknown";
        String clientId = "order-matcher-publisher";
        long uptimeSeconds = 0;

        if (orderPublisher instanceof NatsJSONPublisher<?> natsPublisher) {
            status = natsPublisher.getConnectionStatus();
            address = natsPublisher.getServerAddress();
            clientId = natsPublisher.getClientId();
            uptimeSeconds = natsPublisher.getUptimeSeconds();
        }

        payload.put("status", status);
        payload.put("address", address);
        payload.put("clientId", clientId);
        payload.put("uptimeSeconds", uptimeSeconds);
        return payload;
    }
}
