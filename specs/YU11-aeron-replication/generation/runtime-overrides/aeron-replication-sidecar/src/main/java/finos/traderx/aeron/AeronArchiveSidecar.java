package finos.traderx.aeron;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Dedicated YU11 Media Driver + Archive process; no Spring heap or BLP duty cycle is shared. */
public final class AeronArchiveSidecar {
    public static final String SCHEMA_CHECKSUM =
        "ed3de6f48d4032b641e4123c0f79a7d02468452636e2dd235c65f34a269fae2a";

    private AeronArchiveSidecar() { }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnvironment();
        Files.createDirectories(config.aeronDir());
        Files.createDirectories(config.archiveDir());

        MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(config.aeronDir().toString())
            .dirDeleteOnStart(false)
            .dirDeleteOnShutdown(false)
            .threadingMode(ThreadingMode.SHARED)
            .warnIfDirectoryExists(false);
        Archive.Context archiveContext = new Archive.Context()
            .archiveDir(config.archiveDir().toFile())
            .deleteArchiveOnStart(false)
            .aeronDirectoryName(config.aeronDir().toString())
            .threadingMode(ArchiveThreadingMode.SHARED)
            .controlChannel(config.archiveControlChannel())
            .replicationChannel(config.archiveReplicationChannel())
            .recordingEventsChannel(config.recordingEventsChannel());

        try (ArchivingMediaDriver driver = ArchivingMediaDriver.launch(driverContext, archiveContext);
             AeronArchive archive = AeronArchive.connect(new AeronArchive.Context()
                 .aeronDirectoryName(config.aeronDir().toString())
                 .controlRequestChannel("aeron:ipc")
                 .controlResponseChannel("aeron:ipc"));
             HealthServer health = new HealthServer(config.healthPort(), config)) {
            long recordingSubscriptionId = archive.startRecording(config.recordingChannel(),
                config.recordingStreamId(), SourceLocation.REMOTE, true);
            health.recordingSubscriptionId(recordingSubscriptionId);
            health.start();
            Runtime.getRuntime().addShutdownHook(new Thread(health::stop, "aeron-sidecar-shutdown"));
            new CountDownLatch(1).await();
        }
    }

    public record Config(Path aeronDir, Path archiveDir, int healthPort,
                         String archiveControlChannel, String archiveReplicationChannel,
                         String recordingEventsChannel, String recordingChannel,
                         int recordingStreamId,
                         String expectedSchemaChecksum) {
        static Config fromEnvironment() {
            return new Config(
                Path.of(env("AERON_DIR", "/dev/shm/aeron/driver")),
                Path.of(env("AERON_ARCHIVE_DIR", "/var/lib/traderx-lmax/aeron-archive")),
                integerEnv("AERON_HEALTH_PORT", 18080),
                env("AERON_ARCHIVE_CONTROL_CHANNEL", "aeron:udp?endpoint=0.0.0.0:8010"),
                env("AERON_ARCHIVE_REPLICATION_CHANNEL", "aeron:udp?endpoint=0.0.0.0:8012"),
                env("AERON_ARCHIVE_RECORDING_EVENTS_CHANNEL", "aeron:ipc"),
                env("AERON_RECORD_CHANNEL", "aeron:udp?endpoint=0.0.0.0:40123"),
                integerEnv("AERON_RECORD_STREAM_ID", 1101),
                env("BLP_SBE_SCHEMA_CHECKSUM", SCHEMA_CHECKSUM));
        }

        boolean schemaMatches() { return SCHEMA_CHECKSUM.equals(expectedSchemaChecksum); }
    }

    static final class HealthServer implements AutoCloseable {
        private final HttpServer server;
        private final Config config;
        private volatile long recordingSubscriptionId = -1L;

        HealthServer(int port, Config config) throws IOException {
            this.config = config;
            server = HttpServer.create(new InetSocketAddress(port), 16);
            server.createContext("/healthz", this::health);
            server.createContext("/schema", this::schema);
        }

        void start() { server.start(); }
        void stop() { server.stop(0); }
        void recordingSubscriptionId(long value) { recordingSubscriptionId = value; }

        private void health(HttpExchange exchange) throws IOException {
            boolean ok = config.schemaMatches()
                && Files.isDirectory(config.aeronDir())
                && Files.isDirectory(config.archiveDir())
                && Files.isWritable(config.archiveDir());
            respond(exchange, ok ? 200 : 503,
                "{\"status\":\"" + (ok ? "UP" : "DOWN") + "\",\"schemaChecksum\":\""
                    + SCHEMA_CHECKSUM + "\",\"recordingSubscriptionId\":"
                    + recordingSubscriptionId + "}\n");
        }

        private void schema(HttpExchange exchange) throws IOException {
            respond(exchange, config.schemaMatches() ? 200 : 409,
                "{\"schemaChecksum\":\"" + SCHEMA_CHECKSUM + "\",\"matches\":"
                    + config.schemaMatches() + "}\n");
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override public void close() { stop(); }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int integerEnv(String key, int defaultValue) {
        try { return Integer.parseInt(env(key, Integer.toString(defaultValue))); }
        catch (NumberFormatException ex) { return defaultValue; }
    }
}
