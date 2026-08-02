import React, {useCallback, useEffect, useRef, useState} from 'react';
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

const Chevron = ({dir}) => (
  <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false">
    <path
      d={dir === 'left' ? 'M15 5 L8 12 L15 19' : 'M9 5 L16 12 L9 19'}
      fill="none"
      stroke="currentColor"
      strokeWidth="2.25"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

export default function Results() {
  const scroller = useRef(null);
  // Each arrow shows only while there is travel left in its direction. The band is exactly one card
  // step wide, so in practice exactly one arrow is on screen at a time: right at rest, left once
  // scrolled. The same expression also hides BOTH when the row does not overflow at all — with
  // max at 0, `scrollLeft > 1` and `scrollLeft < max - 1` are both false.
  const [edges, setEdges] = useState({left: false, right: false});

  const sync = useCallback(() => {
    const el = scroller.current;
    if (!el) return;
    // 1px of slack at each end: fractional layout widths mean scrollLeft rarely lands exactly on 0
    // or on max, and without it an arrow lingers at the end it has already reached.
    const max = el.scrollWidth - el.clientWidth;
    setEdges({left: el.scrollLeft > 1, right: el.scrollLeft < max - 1});
  }, []);

  useEffect(() => {
    const el = scroller.current;
    if (!el) return undefined;

    // Shift+wheel, handled explicitly rather than left to the browser. It has to be a native
    // listener with passive:false — React attaches wheel handlers passively, so a preventDefault
    // from an onWheel prop is ignored and the page scrolls vertically underneath the slider.
    const onWheel = (event) => {
      if (!event.shiftKey) return;
      const max = el.scrollWidth - el.clientWidth;
      if (max <= 0) return;
      event.preventDefault();
      el.scrollLeft += event.deltaY + event.deltaX;
    };
    el.addEventListener('wheel', onWheel, {passive: false});
    el.addEventListener('scroll', sync, {passive: true});
    window.addEventListener('resize', sync);
    sync();
    return () => {
      el.removeEventListener('wheel', onWheel);
      el.removeEventListener('scroll', sync);
      window.removeEventListener('resize', sync);
    };
  }, [sync]);

  const nudge = (direction) => {
    const el = scroller.current;
    if (!el) return;
    // One card plus its gap, read from the DOM so it tracks the CSS rather than duplicating it.
    const card = el.firstElementChild;
    const step = card
      ? card.getBoundingClientRect().width + parseFloat(getComputedStyle(el).columnGap || 0)
      : el.clientWidth * 0.8;
    const max = el.scrollWidth - el.clientWidth;
    let target = el.scrollLeft + direction * step;

    // Finish the run instead of stranding a sliver. On a desktop the track scrolls 368px against a
    // 308px card step, so stepping blindly leaves 60px behind: the first click stops just short of
    // the end and the second travels almost nothing, which reads as a dead click. Absorb a remainder
    // of up to HALF a step; absorbing a full one made the last click on mobile jump 577px, nearly
    // two screens, which is a worse problem than the one it solves.
    if (direction > 0 && max - target < step / 2) target = max;
    if (direction < 0 && target < step / 2) target = 0;

    el.scrollTo({left: Math.max(0, Math.min(target, max)), behavior: 'smooth'});
  };

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

        <div className={styles.resultsSlider}>
          <button
            type="button"
            className={`${styles.resultsArrow} ${styles.resultsArrowLeft}`}
            onClick={() => nudge(-1)}
            hidden={!edges.left}
            aria-label="Show previous measurements">
            <Chevron dir="left" />
          </button>

          <dl className={styles.resultsGrid} ref={scroller} tabIndex={0}>
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

          <button
            type="button"
            className={`${styles.resultsArrow} ${styles.resultsArrowRight}`}
            onClick={() => nudge(1)}
            hidden={!edges.right}
            aria-label="Show more measurements">
            <Chevron dir="right" />
          </button>
        </div>
      </div>
    </section>
  );
}
