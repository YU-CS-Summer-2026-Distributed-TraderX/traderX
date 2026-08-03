package finos.traderx.ordermatcher.service;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Binary counterpart of {@link PricingNatsSubscriberService}: subscribes to
 * {@code pricing-tick-bin.*}, where price-publisher additionally (not instead of the JSON
 * pricing.* subject the front-end still uses) publishes a 16-byte fixed struct per tick — int64
 * price ticks + int64 source epoch millis, big-endian, matching order-matcher's own fixed-point
 * convention (lmax/Px.java). No JSON parse, no BigDecimal string parse, on this path.
 */
@Service
@ConditionalOnProperty(
    name = "order.matcher.pricing-subscriber.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class PricingNatsBinarySubscriberService implements InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(PricingNatsBinarySubscriberService.class);
    private static final String SUBJECT_PREFIX = "pricing-tick-bin.";
    private static final int PAYLOAD_LENGTH = 16;

    private final OrderMatcherService orderMatcherService;
    private final String natsAddress;
    private Connection connection;
    private Dispatcher dispatcher;

    public PricingNatsBinarySubscriberService(
        OrderMatcherService orderMatcherService,
        @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress
    ) {
        this.orderMatcherService = orderMatcherService;
        this.natsAddress = natsAddress;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        Options options = new Options.Builder()
            .server(natsAddress)
            .maxReconnects(-1)
            .connectionTimeout(Duration.ofSeconds(5))
            .build();
        connection = Nats.connect(options);
        dispatcher = connection.createDispatcher(this::onMessage);
        dispatcher.subscribe(SUBJECT_PREFIX + "*");
        log.info("Subscribed to {}* on {}", SUBJECT_PREFIX, natsAddress);
    }

    @Override
    public void destroy() throws Exception {
        if (dispatcher != null && connection != null) {
            connection.closeDispatcher(dispatcher);
            dispatcher = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    private void onMessage(Message message) {
        try {
            String ticker = extractTicker(message.getSubject());
            if (ticker == null) {
                return;
            }
            byte[] data = message.getData();
            if (data == null || data.length != PAYLOAD_LENGTH) {
                log.warn("Discarding malformed binary tick for {}: expected {} bytes, got {}",
                    ticker, PAYLOAD_LENGTH, data == null ? 0 : data.length);
                return;
            }
            long priceTicks = readLongBE(data, 0);
            long sourceEpochMillis = readLongBE(data, 8);
            orderMatcherService.onPriceTickRaw(ticker, priceTicks, sourceEpochMillis);
        } catch (Exception ex) {
            log.warn("Failed to process binary pricing tick message", ex);
        }
    }

    private static long readLongBE(byte[] data, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    private String extractTicker(String subject) {
        if (subject == null || !subject.startsWith(SUBJECT_PREFIX) || subject.length() <= SUBJECT_PREFIX.length()) {
            return null;
        }
        return subject.substring(SUBJECT_PREFIX.length()).trim().toUpperCase(Locale.ROOT);
    }
}
