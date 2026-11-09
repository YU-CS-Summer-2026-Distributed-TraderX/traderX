package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.net.ServerSocket;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * FR-DRS-38 test rig: a JetStream NATS on a fixed host port with a file store, so a SIGKILL and a start keep both the
 * bridge's URL and the stream - the shape of kind's OOM-killed NATS pod. {@link #pause} freezes the server first, so
 * what the bridge sends meanwhile is in flight at the kill and never stored.
 */
final class RestartableNats implements AutoCloseable {
    final String url;
    private final FixedHostPortGenericContainer<?> c;

    RestartableNats() throws Exception {
        final int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        c = new FixedHostPortGenericContainer<>("nats:2.14-alpine").withFixedExposedPort(port, 4222)
            .withCommand("-js", "-sd", "/data").waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        c.start();
        url = "nats://" + c.getHost() + ":" + port;
    }

    /** TRADERX_FEED on {@code /trades} and {@code /orders}, file storage so it survives the kill. */
    void addFeedStream() throws Exception {
        try (Connection nc = Nats.connect(url)) {
            nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("TRADERX_FEED")
                .subjects("/trades", "/orders").storageType(StorageType.File).build());
        }
    }

    void pause() {
        c.getDockerClient().pauseContainerCmd(c.getContainerId()).exec();
    }

    /** SIGKILL (a paused server dies without reading what it was sent) and start the same container again. */
    void killAndStart() throws Exception {
        c.getDockerClient().killContainerCmd(c.getContainerId()).exec();
        while (Boolean.TRUE.equals(c.getDockerClient().inspectContainerCmd(c.getContainerId()).exec().getState().getRunning())) {
            Thread.sleep(50);
        }
        c.getDockerClient().startContainerCmd(c.getContainerId()).exec();
        final long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            try (Connection nc = Nats.connect(url)) {
                nc.jetStreamManagement().getStreamInfo("TRADERX_FEED");
                return;
            } catch (final Exception e) {
                if (System.currentTimeMillis() > deadline) { throw e; }
                Thread.sleep(200);
            }
        }
    }

    @Override
    public void close() {
        c.stop();
    }
}
