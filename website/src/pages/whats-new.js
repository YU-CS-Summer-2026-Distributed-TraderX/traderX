import React from 'react';
import Head from '@docusaurus/Head';
import Link from '@docusaurus/Link';
import Footer from '../components/homepage/Footer';
import {catalogStates} from '../components/homepage/homepageData';
import styles from '../components/homepage/TraderXHomepage.module.css';
import pageStyles from './whats-new.module.css';

/**
 * What each state ADDED, in one paragraph, written from its own spec pack.
 *
 * Keyed by catalog id so the order, titles and spec links come from catalog/state-catalog.json
 * rather than being retyped here — the catalog is what the homepage already reads, and a state
 * renamed there must not silently disagree with this page. A state with no entry still renders,
 * carrying its catalog title, so adding a state cannot make it vanish from this list.
 */
const summaries = {
  'YU01-lmax-sequencer':
    'Replaces the request/response matcher with the LMAX pattern: a single-threaded, in-memory ' +
    'sequencer fed by a Disruptor ring buffer and journaled to disk. Order handling becomes ' +
    'deterministic and replayable, and the database stops being the source of truth.',
  'YU02-lmax-kubernetes':
    'Runs that engine as the deployed order-matcher on Kubernetes, recovering from journal plus ' +
    'periodic snapshots and held un-ready until replay finishes. Adds the approval-gated build and ' +
    'deploy path, so a push builds an image but no one ships without a human saying so.',
  'YU03-in-memory-risk-gateway':
    'Pre-trade risk on the hot path (SEC 15c3-5): credit, order size, notional and price-collar ' +
    'checks decided in memory, with no database round trip per order. A rejected order never ' +
    'reaches the book.',
  'YU04-durable-control-feeds':
    'Makes risk control changes durable. A limit, restriction or new security is written to a ' +
    'transactional outbox in the same commit as the row it describes, then published in strict ' +
    'version order — so a change survives the consumer being offline instead of being lost.',
  'YU05-post-trade-compliance':
    'The post-trade bundle: a real settlement lifecycle, reconciliation of the journal against the ' +
    'SQL projection, a reproducible regulatory export, transaction-cost analysis, and real JWT ' +
    'auth where account scope is checked against the trade’s own account.',
  'YU06-eod-price-production':
    'End-of-day closing prices as a versioned, immutable snapshot, with a quality gate that blocks ' +
    'publication on stale, spiking or missing marks until a human overrides it — and a durable ' +
    'overnight chain that computes EOD P&L from the published cut.',
  'YU07-historical-tick-store':
    'A columnar store of real historical market data, verified by cross-implementation gates whose ' +
    'expected values were computed independently in a second engine, so the store is checked ' +
    'against something other than itself.',
  'YU08-execution-algo-engine':
    'Large orders become TWAP parents sliced into child orders on a schedule, submitted through the ' +
    'same risk-gated ingress as any other order rather than around it.',
  'YU09-ops-hardening':
    'The unglamorous pass: credentials from Kubernetes secrets rather than literals, probe and ' +
    'durability fixes, memory limits that stop a warm-up OOM, and CI/CD for the remaining services.',
  'YU10-fix-ingress':
    'A standard FIX 4.4 session for external counterparties — new orders and cancels arriving over ' +
    'the protocol the industry actually uses, mapped onto the same sequenced path as REST.',
  'YU11-aeron-replication':
    'Replaces warm-standby replication with Aeron transport and SBE encoding, lifting replication ' +
    'capacity by a large multiple and proving recovery across an epoch boundary.',
  'YU12-aeron-cluster':
    'High availability becomes Raft consensus across three members, decided by the cluster itself ' +
    'with Kubernetes out of the decision path. Leader failover under 200 ms, and no order ID is ' +
    'ever reused across one.',
  'YU13-limit-order-book':
    'A genuine crossing book: price-time priority, marketable orders filled at the resting price, ' +
    'market orders that cannot rest, self-trade prevention, atomic replace, idempotent client order ' +
    'IDs — and the whole resting book carried in the cluster snapshot.',
  'YU14-listed-equity-options':
    'Listed equity options trade as ordinary securities on the unchanged book, with no second ' +
    'matching path. The risk math becomes contract-multiplier aware, so a $2.50 option controlling ' +
    '100 shares consumes $250 of credit rather than $2.50.',
  'YU15-eod-risk-extract':
    'A portfolio extract for an external risk engine, with every account frozen at the same ' +
    'consensus instant — a portfolio assembled from accounts sampled at different moments is one ' +
    'the firm never held. Rows are un-netted with the counterparty attached, and the bytes are ' +
    'identical every time for a given identifier.',
};

const verification = [
  {
    to: '/docs/engineering/test-coverage',
    title: 'Test coverage',
    detail:
      'What is tested, how much runs automatically on every push, and how each number was counted — ' +
      'including the ones that are deliberately not automated, and why.',
  },
  {
    to: '/docs/engineering/testing-strategy',
    title: 'Testing strategy',
    detail:
      'Which tier proves what: in-process tests, container-backed integration against a real ' +
      'database and broker, operator-run end-to-end proofs, and the allocation gates that cut ' +
      'across all of them.',
  },
  {
    to: '/docs/spec-kit/state-docs',
    title: 'Per-state documentation',
    detail:
      'Each state’s own spec pack — requirements, architecture decisions and the runtime flow — ' +
      'so a claim on this page can be traced to the state that makes it.',
  },
];

export default function WhatsNew() {
  const states = catalogStates.filter((state) => summaries[state.id] || state.id.startsWith('YU'));

  return (
    <>
      <Head>
        <title>What’s new | Distributed TraderX</title>
        <meta
          name="description"
          content="What Yeshiva University's build adds to FINOS TraderX, state by state: an LMAX matching engine on Raft consensus, pre-trade risk, a crossing order book, listed options and an end-of-day risk extract."
        />
      </Head>

      <div className={styles.page}>
        <header className={pageStyles.header}>
          <div className={pageStyles.headerInner}>
            <Link to="/" className={pageStyles.back}>
              ← Distributed TraderX
            </Link>
            <h1>What’s new</h1>
            <p>
              FINOS TraderX is a reference trading platform: correct, readable, and deliberately
              simple. This build asks what it would take to make it behave like a sell-side order
              management system — and answers it in {states.length} runnable states, each one a
              working system rather than a branch of half-finished work.
            </p>
            <p>
              The matching engine moved in-memory and single-threaded, then onto Raft consensus
              across three members. Risk moved in front of the book. Settlement, reconciliation,
              regulatory export, FIX ingress, listed options and an end-of-day risk extract were
              added on top. Every state below still generates, deploys and runs.
            </p>
          </div>
        </header>

        <main className={styles.main}>
          <ol className={pageStyles.stateList}>
            {states.map((state) => (
              <li key={state.id} className={pageStyles.state}>
                <div className={pageStyles.stateHead}>
                  <span className={pageStyles.stateId}>{state.id.split('-')[0]}</span>
                  <h2>{state.title}</h2>
                </div>
                <p>{summaries[state.id] || 'Documented in this state’s own spec pack.'}</p>
                {state.links?.spec && (
                  <Link className={pageStyles.stateLink} to={state.links.spec}>
                    Read the spec pack →
                  </Link>
                )}
              </li>
            ))}
          </ol>
        </main>

        <section className={styles.resultsBand} aria-labelledby="how-verified">
          <div className={styles.resultsInner}>
            <div className={styles.resultsHeading}>
              <h2 id="how-verified">See how each state is verified</h2>
              <p>
                Every claim above is checked by something that can fail. The engine, cluster,
                gateway, risk and post-trade logic run as machine-verified tests on every push;
                container-backed suites check the properties that live in a real database or broker;
                and end-to-end proof scripts drive the deployed system and print an explicit pass or
                fail per step.
              </p>
            </div>
            <dl className={styles.resultsGrid}>
              {verification.map((item) => (
                <Link key={item.title} to={item.to} className={pageStyles.verifyCard}>
                  <dt>
                    <strong>{item.title}</strong>
                  </dt>
                  <dd>{item.detail}</dd>
                </Link>
              ))}
            </dl>
          </div>
        </section>

        <Footer />
      </div>
    </>
  );
}
