package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * FR-DRS-55: a gateway claims its fills stream instead of hashing its pod name onto one. Run 6: 18 gateways hashed onto
 * 13 of 20 streams, three on one, and by server 2 / 3 / 4 / 3 / 6, so nats-4 carried 3x nats-0's fills. A claim is a key
 * {@code <k>} in the KV bucket {@value #BUCKET}, created only if absent and kept alive while the gateway runs. The
 * gateway takes the free stream on the server with the fewest claims, then names itself {@code <pod>_s<k>} to the
 * cluster, so the read model routes to the same stream ({@link #claimed}; FillsRouter.shard reads the same suffix).
 *
 * <p>A claim is advisory. Two gateways on one stream is FR-DRS-53's ordinary shape (each reads its own subject), so a
 * lapsed claim, a full table or an unreachable NATS costs evenness, never a fill: the gateway then keeps its hashed
 * stream under its plain name.
 */
final class FillsShardClaim {
    static final String BUCKET = "FILLS_SHARD_CLAIMS";
    static final String MARK = "_s";   // a pod name is a DNS label: it never holds '_'
    static final Duration TTL = Duration.ofSeconds(90);

    private FillsShardClaim() {
    }

    /** The claims as the chooser needs them: which streams are held, and an attempt to take one (false: lost the race). */
    interface Claims {
        Set<Integer> held() throws Exception;

        boolean take(int k) throws Exception;
    }

    static String named(final String id, final int k) {
        return id + MARK + k;
    }

    /** The stream a claimed name carries, or -1 for a plain name (or a claim outside this stream count). */
    static int claimed(final String name, final int streams) {
        final int at = name.lastIndexOf(MARK);
        if (at <= 0 || at + MARK.length() >= name.length()) {
            return -1;
        }
        int k = 0;
        for (int i = at + MARK.length(); i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c < '0' || c > '9' || k > 100_000) {
                return -1;
            }
            k = k * 10 + (c - '0');
        }
        return k < streams ? k : -1;
    }

    /**
     * A free stream on the server holding the fewest claims (stream k is on server k mod {@code servers}, FR-DRS-53),
     * scanning from the name's hash so two gateways starting together try different streams first. -1 when every stream
     * is held. Retries when another gateway takes the choice first.
     */
    static int choose(final Claims claims, final String id, final int streams, final int servers) throws Exception {
        final int start = Math.floorMod(id.hashCode(), streams);
        for (int attempt = 0; attempt < streams; attempt++) {
            final Set<Integer> held = claims.held();
            final int[] perServer = new int[servers];
            for (final int k : held) {
                if (k >= 0 && k < streams) {
                    perServer[k % servers]++;
                }
            }
            int best = -1;
            for (int i = 0; i < streams; i++) {
                final int k = (start + i) % streams;
                if (!held.contains(k) && (best < 0 || perServer[k % servers] < perServer[best % servers])) {
                    best = k;
                }
            }
            if (best < 0) {
                return -1;
            }
            if (claims.take(best)) {
                return best;
            }
        }
        return -1;
    }

    static Claims over(final KeyValue kv, final String id) {
        return new Claims() {
            public Set<Integer> held() throws Exception {
                final Set<Integer> out = new HashSet<>();
                for (final String key : kv.keys()) {
                    try {
                        out.add(Integer.parseInt(key));
                    } catch (final NumberFormatException ignore) {
                        // not a claim
                    }
                }
                return out;
            }

            public boolean take(final int k) throws Exception {
                try {
                    kv.create(String.valueOf(k), id.getBytes(StandardCharsets.UTF_8));
                    return true;
                } catch (final io.nats.client.JetStreamApiException taken) {
                    return false;
                }
            }
        };
    }

    static KeyValue bucket(final Connection nats) throws Exception {
        try {
            nats.keyValueManagement().create(KeyValueConfiguration.builder().name(BUCKET).maxHistoryPerKey(1).ttl(TTL).build());
        } catch (final io.nats.client.JetStreamApiException exists) {
            // another gateway made it
        }
        return nats.keyValue(BUCKET);
    }

    /**
     * Claim a stream and keep it: returns the name to connect to the cluster with, {@code <id>_s<k>}, or {@code id}
     * itself when no stream was free. The claim is re-put every TTL/3 on a daemon thread for the life of the process.
     */
    static String claim(final Connection nats, final String id, final int streams, final int servers) throws Exception {
        final KeyValue kv = bucket(nats);
        final int k = choose(over(kv, id), id, streams, servers);
        if (k < 0) {
            System.out.println("fills stream claim: all " + streams + " held; " + id + " keeps its hashed stream");
            return id;
        }
        final Thread keep = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(TTL.toMillis() / 3);
                    kv.put(String.valueOf(k), id.getBytes(StandardCharsets.UTF_8));
                } catch (final InterruptedException e) {
                    return;
                } catch (final Exception e) {
                    System.err.println("fills stream claim " + k + " not renewed (another gateway may share it): " + e);
                }
            }
        }, "fills-shard-claim");
        keep.setDaemon(true);
        keep.start();
        System.out.println("fills stream claim: " + id + " holds stream " + k + " of " + streams);
        return named(id, k);
    }

    /**
     * At gateway start, before the cluster connect: with {@code FILLS_SHARD_CLAIM} = the NATS server count (0 or unset:
     * off, the hashed stream) on a routed feed over more than one stream, claim and set the gateway's name.
     */
    static void atStart(final java.util.function.Function<String, String> env, final String natsUrl) {
        final int servers = Integer.parseInt(env.apply("FILLS_SHARD_CLAIM"));
        final int streams = Integer.parseInt(env.apply("FILLS_STREAMS"));
        final String id = env.apply(ClusterClientAuth.GATEWAY_ID);
        if (servers <= 0 || streams <= 1 || id.isBlank() || !"routed".equals(env.apply("FIX_FILL_FEED"))) {
            return;
        }
        try {
            final Connection nats = io.nats.client.Nats.connect(new io.nats.client.Options.Builder().server(natsUrl)
                .maxReconnects(-1).connectionTimeout(Duration.ofSeconds(5)).build());
            ClusterClientAuth.gatewayName(claim(nats, id, streams, servers));   // the connection stays: it renews
        } catch (final Exception e) {
            System.err.println("fills stream claim failed; " + id + " keeps its hashed stream: " + e);
        }
    }
}
