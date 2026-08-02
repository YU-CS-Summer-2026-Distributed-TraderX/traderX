import React from 'react';
import useBaseUrl from '@docusaurus/useBaseUrl';
import {Icon} from './Icons';
import {ExternalLink, SmartLink} from './Links';
import {catalogSource, repoTreeUrl, upstreamRepoUrl} from './homepageData';
import styles from './TraderXHomepage.module.css';

export default function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={styles.footerInner}>
        <div className={styles.footerBrand}>
          <ExternalLink href="https://www.yu.edu" className={styles.footerYuLogoLink}>
            <img
              src={useBaseUrl('/img/yu/yu-crest.png')}
              alt="Yeshiva University"
              className={styles.footerYuLogo}
            />
          </ExternalLink>
          <span />
          <div>
            {/*
              Was "Copyright (c) 2026 Fintech Open Source Foundation" — not who holds this work. The
              upstream project is credited on the line below rather than misattributed as the author
              of this deployment. The copyright notice itself is gone: it was a hardcoded year that
              would silently go stale, and attribution is what this line is actually for.
            */}
            <p>Yeshiva University Computer Science.</p>
            <small>
              Built on <ExternalLink href={upstreamRepoUrl}>FINOS TraderX</ExternalLink>. Homepage
              state list sourced from{' '}
              <ExternalLink href={catalogSource.catalogUrl}>
                catalog/state-catalog.json
              </ExternalLink>
              .
            </small>
          </div>
        </div>
        <div className={styles.footerLinks}>
          <ExternalLink href={catalogSource.catalogUrl}>State Catalog</ExternalLink>
          <SmartLink to={catalogSource.generatedBranchesDocs}>Generated Branches</SmartLink>
          <ExternalLink href={repoTreeUrl}>
            <Icon name="github" />
            Repository
          </ExternalLink>
        </div>
      </div>
    </footer>
  );
}
