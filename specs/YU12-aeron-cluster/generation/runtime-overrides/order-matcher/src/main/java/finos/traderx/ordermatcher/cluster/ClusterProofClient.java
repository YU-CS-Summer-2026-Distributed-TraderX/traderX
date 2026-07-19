package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Kind/GKE proof client (T-AC20): seeds control state, then submits one order every
 * {@code PROOF_INTERVAL_MS} through the cluster client, logging every accepted reference with
 * wall-clock receive time. On any window where consecutive accepted acks are further apart than
 * {@code PROOF_GAP_MS}, prints a {@code GAP ms=... beforeRef=... afterRef=...} line — during a
 * leader kill that gap IS the client-observed failover time. Duplicate or reused references
 * fail loudly ({@code REUSE ref=...}); the run is judged from the log.
 */
public final class ClusterProofClient {
    private static final long PX = 1_000_000L;

    public static void main(final String[] args) {
        final String ingressEndpoints = env("PROOF_INGRESS_ENDPOINTS", "0=localhost:21802");
        final long intervalMs = Long.parseLong(env("PROOF_INTERVAL_MS", "100"));
        final long gapMs = Long.parseLong(env("PROOF_GAP_MS", "1000"));
        final int account = Integer.parseInt(env("PROOF_ACCOUNT", "11"));
        final int security = Integer.parseInt(env("PROOF_SECURITY", "1"));
        final String aeronDir = env("PROOF_AERON_DIR", "/dev/shm/aeron-proof");
        // Marketable by default (limit == seeded price): orders fill and go terminal instead of
        // resting forever — a non-marketable canary accumulates open state until a risk cap
        // rejects everything silently (bit us at ~ref 99k; rejects still consume orderRefs).
        final long limitPx = Long.parseLong(env("PROOF_LIMIT_PX", "150")) * PX;

        final MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true));

        final AeronReplicationCodec codec = new AeronReplicationCodec();
        final InputEvent event = new InputEvent();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
        final long[] lastAckMillis = { 0 };
        final long[] lastAckRef = { 0 };
        final boolean[] seen = new boolean[1 << 20];

        final AeronCluster.Context clientContext = new AeronCluster.Context()
            .aeronDirectoryName(aeronDir)
            .ingressChannel("aeron:udp?term-length=64k")
            .messageTimeoutNs(1_000_000_000L)
            .ingressEndpoints(ingressEndpoints)
            .egressChannel("aeron:udp?term-length=64k|endpoint=" + env("PROOF_EGRESS_HOST", env("POD_IP", "localhost")) + ":" + env("PROOF_EGRESS_PORT", "0"))
            .egressListener(new io.aeron.cluster.client.EgressListener() {
                @Override
                public void onNewLeader(final long clusterSessionId, final long leadershipTermId,
                                        final int leaderMemberId, final String ingressEndpoints1) {
                    // Phase 1a: native leader following — the SAME session re-points its ingress.
                    System.out.println("NEW-LEADER atMs=" + System.currentTimeMillis()
                        + " session=" + clusterSessionId + " leader=" + leaderMemberId);
                }

                @Override
                public void onMessage(final long clusterSessionId, final long timestamp,
                                      final org.agrona.DirectBuffer egress, final int offset, final int length,
                                      final io.aeron.logbuffer.Header header) {
                final int ref = egress.getInt(offset + 8);
                final byte kind = egress.getByte(offset + 12);
                if (kind != OutputEvent.KIND_ORDER_ACCEPTED) {
                    return;
                }
                final long now = System.currentTimeMillis();
                if (ref < seen.length && seen[ref]) {
                    System.out.println("REUSE ref=" + ref);
                } else if (ref < seen.length) {
                    seen[ref] = true;
                }
                if (lastAckMillis[0] != 0 && now - lastAckMillis[0] > gapMs) {
                    System.out.println("GAP ms=" + (now - lastAckMillis[0])
                        + " beforeRef=" + lastAckRef[0] + " afterRef=" + ref);
                }
                lastAckMillis[0] = now;
                lastAckRef[0] = ref;
                System.out.println("ACK ref=" + ref + " at=" + now);
                }
            });

        // Phase 1a (joint plan): PROOF_NATIVE_FOLLOW=1 connects ONCE with the FULL endpoint
        // list and lets the replicated session follow NewLeaderEvent — no close, no cycling.
        // newLeaderTimeoutNs is pinned explicitly: the default (2x the SERVER heartbeat
        // timeout) shrinks under the timeout ladder to less than an election takes.
        // The cycling path below remains the fallback (kind-era redirect-wedge workaround).
        final boolean nativeFollow = "1".equals(env("PROOF_NATIVE_FOLLOW", "0"));
        final String[] endpointEntries = ingressEndpoints.split(",");
        if (nativeFollow) {
            clientContext.newLeaderTimeoutNs(2_000_000_000L);
        }
        AeronCluster client = nativeFollow
            ? connectFull(clientContext, ingressEndpoints)
            : connectCycling(clientContext, endpointEntries);
        seed(client, codec, event, buffer, account, security);
        System.out.println("SEEDED account=" + account + " security=" + security);

        // Failover detection: AeronCluster signals session loss as a STATE, not an exception —
        // offer() just stops making progress. So if no ack lands for reconnectAfterMs while we
        // are still submitting, proactively close and re-find the leader. The GAP line (printed
        // in the egress listener when an ack arrives after > gapMs) is the client-observed
        // failover measurement (T-AC20).
        final long reconnectAfterMs = Long.parseLong(env("PROOF_RECONNECT_MS", "2000"));
        long nextSendMillis = System.currentTimeMillis();
        long lastReconnectMillis = 0;
        long lastStallLogMillis = 0;
        while (true) {
            try {
                client.pollEgress();
                final long now = System.currentTimeMillis();
                if (now >= nextSendMillis) {
                    newOrder(event, account, security, limitPx, 0L);
                    codec.encodeInput(buffer, 0, event, 0, 0, 0);
                    client.offer(buffer, 0, AeronReplicationCodec.INPUT_BYTES);
                    nextSendMillis = now + intervalMs;
                }
                // lastAckMillis stays at the true last ack so the GAP line measures the real
                // failover. The stall CLOCK restarts on each reconnect (stallBase = max of last
                // ack and last reconnect) so a just-established session gets a full grace window
                // to produce an ack before we tear it down again — without this the client churns
                // reconnects every window and never lets acks resume (self-inflicted long outage).
                final long stallBase = Math.max(lastAckMillis[0], lastReconnectMillis);
                final boolean stalled = lastAckMillis[0] != 0 && now - stallBase > reconnectAfterMs;
                if (nativeFollow) {
                    // An ack stall is observability, never authority to destroy the replicated
                    // session (codeX critique, adopted). Reconnect only on genuine close,
                    // rate-limited to one attempt per second.
                    if (stalled && now - lastStallLogMillis > 1000) {
                        lastStallLogMillis = now;
                        System.out.println("STALL ms=" + (now - stallBase) + " session=" + client.clusterSessionId());
                    }
                    if (client.isClosed() && now - lastReconnectMillis > 1000) {
                        System.out.println("RECONNECT reason=closed");
                        CloseHelper.quietClose(client);
                        client = connectFull(clientContext.clone(), ingressEndpoints);
                        lastReconnectMillis = System.currentTimeMillis();
                    }
                } else if (client.isClosed() || stalled) {
                    System.out.println("RECONNECT reason=" + (client.isClosed() ? "closed" : "stalled"));
                    CloseHelper.quietClose(client);
                    client = connectCycling(clientContext.clone(), endpointEntries);
                    lastReconnectMillis = System.currentTimeMillis(); // grace starts after connect
                }
                Thread.yield();
            } catch (final Exception e) {
                System.out.println("RECONNECT cause=" + e.getClass().getSimpleName());
                CloseHelper.quietClose(client);
                client = nativeFollow
                    ? connectFull(clientContext.clone(), ingressEndpoints)
                    : connectCycling(clientContext.clone(), endpointEntries);
            }
        }
    }

    /** Phase 1a: one connect against the full member list; the session then follows leadership
     *  changes natively. Retries the whole connect (rate-limited) until the cluster answers. */
    private static AeronCluster connectFull(final AeronCluster.Context context,
                                            final String ingressEndpoints) {
        while (true) {
            try {
                final AeronCluster client = AeronCluster.connect(context.clone().ingressEndpoints(ingressEndpoints));
                System.out.println("CONNECTED-FULL session=" + client.clusterSessionId()
                    + " leader=" + client.leaderMemberId() + " atMs=" + System.currentTimeMillis());
                return client;
            } catch (final Exception e) {
                System.out.println("CONNECT-FULL-RETRY cause=" + e.getMessage());
                try { Thread.sleep(1000); } catch (final InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private static AeronCluster connectCycling(final AeronCluster.Context context,
                                               final String[] endpointEntries) {
        int attempt = 0;
        while (true) {
            final String entry = endpointEntries[attempt++ % endpointEntries.length];
            try {
                final AeronCluster client = AeronCluster.connect(context.clone().ingressEndpoints(entry));
                System.out.println("CONNECTED via " + entry);
                return client;
            } catch (final Exception e) {
                System.out.println("CONNECT-RETRY endpoint=" + entry + " cause=" + e.getMessage());
            }
        }
    }

    private static void seed(final AeronCluster client, final AeronReplicationCodec codec,
                             final InputEvent event, final UnsafeBuffer buffer,
                             final int account, final int security) {
        event.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        event.accountId = account;
        event.securityId = 0;
        event.setControlEnabled(true);
        event.setControlVersion(1L);
        offer(client, codec, event, buffer);

        event.type = InputEvent.TYPE_SECURITY_CONTROL;
        event.accountId = 0;
        event.securityId = security;
        event.setControlEnabled(true);
        event.setControlVersion(2L);
        offer(client, codec, event, buffer);

        event.type = InputEvent.TYPE_PRICE_TICK;
        event.side = 0;
        event.securityId = security;
        event.priceTicks = 150 * PX;
        offer(client, codec, event, buffer);
    }

    private static void offer(final AeronCluster client, final AeronReplicationCodec codec,
                              final InputEvent event, final UnsafeBuffer buffer) {
        codec.encodeInput(buffer, 0, event, 0, 0, 0);
        while (client.offer(buffer, 0, AeronReplicationCodec.INPUT_BYTES) < 0) {
            client.pollEgress();
            Thread.yield();
        }
    }

    private static void newOrder(final InputEvent event, final int account, final int security,
                                 final long limitPx, final long clientOrderKey) {
        event.type = InputEvent.TYPE_ORDER_NEW;
        event.side = InputEvent.SIDE_BUY;
        event.orderRef = 0;
        event.accountId = account;
        event.securityId = security;
        event.qty = 10;
        event.limitPx = limitPx;
        event.priceTicks = clientOrderKey;
        event.eventTimeMillis = 0;
    }

    private static String env(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
