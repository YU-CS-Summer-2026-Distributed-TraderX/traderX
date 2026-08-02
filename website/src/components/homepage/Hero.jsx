import React from 'react';
import Link from '@docusaurus/Link';
// useBaseUrl, not a bare "/img/..." string: this site is served from /traderX/, so a root-absolute
// image path resolves to https://<host>/img/... and 404s. The originals were bare paths.
import useBaseUrl from '@docusaurus/useBaseUrl';
import {Icon} from './Icons';
import {ExternalLink} from './Links';
import {catalogSource, internalNav, repoTreeUrl} from './homepageData';
import styles from './TraderXHomepage.module.css';

function TopBanner() {
  return (
    <div className={styles.topBanner}>
      <strong>Yeshiva University CS</strong> — TraderX rebuilt on the LMAX architecture over an Aeron
      Raft cluster. Read the{' '}
      <Link to="/docs/engineering/testing-strategy">testing strategy</Link>.
    </div>
  );
}

function HeroNav() {
  return (
    <nav className={styles.heroNav} aria-label="Homepage">
      <Link to="/" className={styles.brand}>
        <img
          src={useBaseUrl('/img/yu/yu-crest.png')}
          alt=""
          className={styles.brandLogo}
        />
        <span>YU · TraderX</span>
      </Link>

      <div className={styles.navLinks}>
        {internalNav.map((item) => (
          <Link key={item.to} to={item.to} className={styles.navLink}>
            {item.label === 'Blog' && <Icon name="blog" />}
            {item.label}
          </Link>
        ))}
        <ExternalLink href={repoTreeUrl} className={styles.navLink}>
          GitHub
          <Icon name="external" />
        </ExternalLink>
        {/* Upstream badge, kept deliberately: TraderX is a FINOS project and this is a fork of it.
            It now reads "Upstream" so it credits FINOS without implying FINOS published this site. */}
        <ExternalLink href="https://github.com/finos/traderX" className={styles.finosNavBadge}>
          <img src={useBaseUrl('/img/finos/finos-white.png')} alt="" />
          <span>Upstream: FINOS TraderX</span>
        </ExternalLink>
      </div>
    </nav>
  );
}

export default function Hero() {
  return (
    <>
      <TopBanner />
      <header className={styles.hero}>
        <HeroNav />
        <div className={styles.heroContent}>
          <div className={styles.heroKicker}>
            <Icon name="branch" />
            Yeshiva University · Computer Science
          </div>

          <div className={styles.heroTitleGroup}>
            <img
              src={useBaseUrl('/img/yu/yu-cs-shield-transparent.png')}
              alt="Yeshiva University Computer Science"
              className={styles.heroMark}
            />
            <img
              src={useBaseUrl('/img/traderX/TraderX_Horizontal_BLK.svg')}
              alt="TraderX"
              className={styles.heroLogo}
            />
          </div>

          <p className={styles.heroCopy}>
            Yeshiva University&rsquo;s own build of <ExternalLink
              href="https://github.com/finos/traderX">FINOS TraderX</ExternalLink>, taken from the
            reference demo to a <strong>sell-side order management system</strong>: the matching
            engine rebuilt on the LMAX Disruptor architecture, replicated across a three-member Aeron
            Raft cluster, with risk, post-trade, FIX ingress and end-of-day risk extraction added as
            fifteen individually runnable{' '}
            <Link to={catalogSource.generatedBranchesDocs}>architectural states</Link>.
          </p>

          <p className={styles.heroCopy}>
            Every claim on this site is machine-checked or measured on a deployed cluster.
          </p>

          {/* Its own sentence on its own line. Joined to the line above with an em dash, the link
              wrapped onto a new line by itself and read as a fragment. */}
          <p className={styles.heroCopyLink}>
            <Link to="/docs/engineering/test-coverage">See how it is verified.</Link>
          </p>

          <div className={styles.heroActions}>
            <Link to={catalogSource.liveEnvironmentsDocs} className={styles.primaryAction}>
              Live Demos
            </Link>
            <ExternalLink href={repoTreeUrl} className={styles.secondaryAction}>
              GitHub Repository
            </ExternalLink>
          </div>
        </div>
      </header>
    </>
  );
}
