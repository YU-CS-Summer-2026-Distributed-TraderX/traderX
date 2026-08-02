import React from 'react';
import Link from '@docusaurus/Link';
import styles from './TraderXHomepage.module.css';

/**
 * Measured results.
 *
 * EVERY figure here is on the publishable list in
 * docs/handoff/production-readiness/08-github-io-branding.md, and every one carries the load or rig
 * it was measured at in the same breath as the number. That is the whole point: a naked headline
 * number invites the question "at what load?", and on a public site the answer had better be on the
 * page rather than in someone's memory.
 *
 * TRANSPORT MATTERS IN THE LABEL. The 190,300 figure is the BINARY ingress path
 * (scripts/bench/load/bin-multi.mjs opens raw TCP sockets on BIN_PORT; HTTP is used only for /seed,
 * /resolve and metrics scrapes). An earlier draft of this file called it "REST ingress", which was
 * simply false — and false in the direction that flatters us, since REST per-order ingress is the
 * slower path and the known ceiling. If you add a throughput number here, name the transport it was
 * measured on.
 *
 * THE FAILOVER NUMBER HAS A SUPERSEDED PREDECESSOR — do not reintroduce it. An earlier measurement
 * (2026-07-18) reported ~2.0 s system-facing re-election, and it is stale twice over: it predates the
 * consensus-timeout tuning campaign AND the client fix below. Quoting it understates the system by an
 * order of magnitude.
 *
 * The real story is that failover was BIMODAL — ~85-180 ms fast vs ~670-850 ms slow, with the mode
 * decided purely by WHICH member was killed. That was never Raft: the gateway's reconnect restarted
 * its endpoint scan at index 0, so killing member 0 (the first pod of the StatefulSet, and the one
 * most likely to die in a real node drain or rolling update) blocked on the dead endpoint's connect
 * timeout before trying a live one — 1270 ms vs 41 ms on GKE, a 31x penalty. `connectCycling()` now
 * hands Aeron the complete member list first and rotates its fallback start, which collapses the slow
 * mode. Published figure is the post-fix fast mode.
 *
 * Localising that took independent probes at the same cadence: `/orders` (needs a leader) against
 * `/ready` (gateway-local, never touches the leader). Both failing for the same window means the
 * gateway is reconnecting, not the cluster electing — which is how consensus was exonerated without
 * touching a member. If you re-measure failover, use that method; a health-poll plus
 * `kubectl delete pod` carries 400-600 ms of API and poll latency and once manufactured a fake "37 s
 * failover tail" for a cluster that had actually re-elected in 1-2 s.
 *
 * Deliberately NOT published here, per the same brief:
 *   - single-run client RTT absolutes to two significant figures (run-to-run variance is ~1.5-2x,
 *     so a two-sig-fig absolute implies precision the measurement does not have);
 *   - the retired "12k orders/s ceiling", which turned out to be a harness artifact;
 *   - the extrapolated ~440k/s consensus ceiling, which is a projection and must never be worded as
 *     though it were measured.
 * If you are adding a number to this component, check it against that list first.
 */

const results = [
  {
    figure: '259,200',
    unit: 'orders/sec',
    label: 'Sustained end-to-end throughput',
    detail:
      'Per-order binary ingress across six gateways, counted at the leader’s committed sequence over a 20-second steady window. Throughput scales with gateway count at roughly 47,000/sec each; the ceiling is gateway ingress rather than consensus.',
  },
  {
    figure: '185–227',
    unit: 'µs',
    label: 'Consensus commit latency',
    detail:
      'Time from a leader accepting an order to it being committed across three members. Held flat across a 6× load sweep and every in-flight window depth tested.',
  },
  {
    figure: '0.45–0.57',
    unit: 'µs',
    label: 'Match and apply',
    detail:
      'The matching engine itself, on the replicated apply path — allocation-free in steady state, verified under a no-GC gate that fails on a single byte.',
  },
  {
    figure: '< 1.5',
    unit: 'ms p50',
    label: 'Per-order round trip',
    detail:
      'Client-observed, sustained to 75,000 orders/sec, with p99 near 2 ms once the in-flight window is sized for the load.',
  },
  {
    figure: '< 200',
    unit: 'ms',
    label: 'Leader failover',
    detail:
      'Killing the leader to orders flowing again, measured on Kubernetes with an independent gateway-session probe. The election is Raft-internal, with Kubernetes out of the decision path, and no order ID was reused across any failover.',
  },
];

export default function Results() {
  return (
    <section className={styles.resultsBand} aria-labelledby="measured-results">
      <div className={styles.resultsInner}>
        <div className={styles.resultsHeading}>
          <h2 id="measured-results">Measured numbers</h2>
          <p>
            Taken from benchmark campaigns on a Kubernetes cluster. Failover, snapshot and replay,
            and a cold follower rejoining from an empty disk are all proven live.{' '}
            <Link to="/docs/engineering/test-coverage">How every claim is verified</Link>.
          </p>
        </div>

        <dl className={styles.resultsGrid}>
          {results.map((item) => (
            <div key={item.label} className={styles.resultCard}>
              <dt>
                <span className={styles.resultFigure}>{item.figure}</span>
                <span className={styles.resultUnit}>{item.unit}</span>
              </dt>
              <dd>
                <strong>{item.label}</strong>
                <span>{item.detail}</span>
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  );
}
