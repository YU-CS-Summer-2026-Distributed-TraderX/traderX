import React from 'react';
import clsx from 'clsx';
import {Icon} from './Icons';
import {ExternalLink, SmartLink} from './Links';
import {
  catalogSource,
  documentationCards,
  liveEnvironmentCards,
  overviewCards,
  phaseGroups,
  specKitSteps,
} from './homepageData';
import styles from './TraderXHomepage.module.css';

function SectionIntro({title, children}) {
  return (
    <div className={styles.sectionIntro}>
      <h2>{title}</h2>
      <p>{children}</p>
    </div>
  );
}

function InfoCard({item}) {
  return (
    <article className={clsx(styles.card, styles[`tone-${item.tone}`])}>
      <div className={styles.cardIcon}>
        <Icon name={item.icon} />
      </div>
      <h3>{item.title}</h3>
      <p>{item.description}</p>
    </article>
  );
}

function WhatPanel() {
  return (
    <div className={styles.panelStack}>
      <SectionIntro title="From reference demo to order management system">
        FINOS TraderX is a teaching platform: a deliberately approachable trading application that
        runs on a laptop. This deployment keeps that property — every state still runs locally — and
        pushes the architecture underneath it much further. The synchronous, database-backed matcher
        became an event-sourced LMAX Disruptor engine replicated over an Aeron Raft cluster, and the
        surrounding system grew the parts a real desk cannot do without: pre-trade risk controls,
        settlement and reconciliation, a FIX gateway, listed options, and a reproducible end-of-day
        risk extract.
      </SectionIntro>

      <div className={styles.cardGrid}>
        {overviewCards.map((item) => (
          <InfoCard key={item.title} item={item} />
        ))}
      </div>

      <section className={styles.demoPanel}>
        <h3>Explore Catalogued Live Environments</h3>
        <div className={styles.demoSource}>
          <span>
            {catalogSource.liveEnvironmentCount} environments sourced from live environment catalog
            version {catalogSource.liveEnvironmentVersion}
          </span>
          <SmartLink to={catalogSource.liveEnvironmentsDocs}>All demo environments</SmartLink>
          <ExternalLink href={catalogSource.liveEnvironmentCatalogUrl}>View catalog source</ExternalLink>
        </div>
        <div className={styles.demoGrid}>
          {liveEnvironmentCards.map((card) => (
            <article key={card.id} className={styles.demoCard}>
              <SmartLink
                href={card.href}
                className={clsx(styles.demoIconLink, styles[`tone-${card.tone}`])}
                aria-label={`Launch ${card.title}`}
              >
                <Icon name={card.icon} />
                <span className={styles.srOnly}>{card.title}</span>
              </SmartLink>
              <h4>{card.title}</h4>
              <p>{card.description}</p>
              <div className={styles.demoMeta}>
                <span>{card.status}</span>
                <span>{card.stateId}</span>
              </div>
              <div className={styles.demoLinks}>
                {card.links?.spec && (
                  <SmartLink to={card.links.spec}>
                    <Icon name="file" />
                    State spec
                  </SmartLink>
                )}
                {card.links?.code && (
                  <ExternalLink href={card.links.code}>
                    <Icon name="github" />
                    Generated code
                  </ExternalLink>
                )}
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className={styles.docsPanel}>
        <h3>Generated Documentation Surfaces</h3>
        <div className={styles.demoGrid}>
          {documentationCards.map((card) => (
            <article key={card.title} className={styles.demoCard}>
              <SmartLink
                to={card.to}
                href={card.href}
                className={clsx(styles.demoIconLink, styles[`tone-${card.tone}`])}
              >
                <Icon name={card.icon} />
                <span className={styles.srOnly}>{card.title}</span>
              </SmartLink>
              <h4>{card.title}</h4>
              <p>{card.description}</p>
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}

function PhaseCard({state, tone}) {
  const actions = [
    {
      action: 'spec',
      icon: 'file',
      label: 'Open source specification',
      to: state.links.spec,
    },
    {
      action: 'architecture',
      icon: 'diagram',
      label: 'Open generated architecture docs',
      to: state.links.architecture,
    },
    {
      action: 'runtime',
      icon: 'flow',
      label: 'Open runtime topology or flows',
      to: state.links.runtime,
    },
    {
      action: 'learning',
      icon: 'book',
      label: 'Open learning guide',
      to: state.links.learning,
    },
    state.links.adr && {
      action: 'adr',
      icon: 'decision',
      label: 'Open architecture decision record',
      to: state.links.adr,
    },
    state.links.code && {
      action: 'code',
      icon: 'github',
      label: 'Open generated code branch',
      href: state.links.code,
    },
  ].filter(Boolean);

  return (
    <article
      data-state-card={state.id}
      className={clsx(
        styles.phaseState,
        state.isConvergence && styles.phaseStateFeatured,
        state.primaryLineageRole === 'optional' && styles.phaseStateOptional,
        styles[`phase-${tone}`],
      )}
    >
      <div className={styles.phaseStateTitle}>
        <SmartLink
          to={state.links.spec}
          className={styles.stateTitleLink}
          data-state-action="title"
        >
          {state.number}: {state.title}
        </SmartLink>
        {state.convergenceLevel !== 'none' && <strong>{state.convergenceLevel}</strong>}
        {state.primaryLineageRole === 'optional' && <em>Optional</em>}
      </div>
      <p>{state.description}</p>
      <div className={styles.stateMeta}>
        <span>{state.status}</span>
        <span>{state.branch}</span>
      </div>
      <div className={styles.stateLinks}>
        {actions.map((action) => {
          const LinkComponent = action.to ? SmartLink : ExternalLink;
          return (
            <LinkComponent
              key={action.action}
              to={action.to}
              href={action.href}
              className={styles.stateIconLink}
              aria-label={`${action.label} for ${state.id}`}
              data-state-action={action.action}
              data-tooltip={action.label}
              title={action.label}
            >
              <Icon name={action.icon} />
              <span className={styles.srOnly}>{action.label}</span>
            </LinkComponent>
          );
        })}
      </div>
    </article>
  );
}

function PathsPanel() {
  return (
    <div>
      <SectionIntro title="The Canonical Learning Path">
        The official learning path is derived from the repository state catalog. Each state block
        links back to the source specification, generated architecture docs, learning guide, and
        generated code branch.
      </SectionIntro>

      <div className={styles.catalogCallout}>
        <span>
          {catalogSource.stateCount} states sourced from catalog version {catalogSource.version}
        </span>
        <SmartLink to={catalogSource.learningPaths}>Open full learning path</SmartLink>
        <ExternalLink href={catalogSource.catalogUrl}>View catalog source</ExternalLink>
      </div>

      <div className={styles.timeline}>
        {phaseGroups.map((phase) => (
          <section key={phase.track} className={styles.timelinePhase}>
            <div className={clsx(styles.timelineDot, styles[`phase-${phase.tone}`])} />
            <span className={clsx(styles.phaseBadge, styles[`phase-${phase.tone}`])}>
              {phase.label}
            </span>
            <h3>{phase.title}</h3>
            <div className={styles.phaseGrid}>
              {phase.states.map((state) => (
                <PhaseCard key={state.id} state={state} tone={phase.tone} />
              ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}

function CodeBlock({title, label, children}) {
  return (
    <article className={styles.codeCard}>
      <div className={styles.codeHeader}>
        <strong>{title}</strong>
        <span>{label}</span>
      </div>
      <pre>
        <code>{children}</code>
      </pre>
    </article>
  );
}

function SddPanel() {
  return (
    <div className={styles.panelStack}>
      <SectionIntro title="Fifteen states, each one specified before it was built">
        Every state here began as a spec. Each declares what it must make true, layers its changes
        over its ancestors, and renders into a complete, independently runnable system — so
        &ldquo;the version with the order book but without options&rdquo; is something you can boot
        and benchmark directly. That layering has one sharp edge: a fix landed in a layer that a
        later state overrides is inert, which is why the test suite runs against three separately
        composed trees.
      </SectionIntro>

      <div className={styles.codeGrid}>
        <CodeBlock title="Core Requirements Stay Canonical" label="spec.md">
          {`## Functional Requirements
- FR-00901: Preserve baseline trade capture behavior.
- FR-00902: Add order lifecycle events over the message bus.
- FR-00903: Keep account, position, and execution flows traceable.

## Success Criteria
- SC-00901: A generated demo shows order creation, match, and fill.`}
        </CodeBlock>
        <CodeBlock title="Companies Add Internal Learning Overlays" label="overlay/state-catalog.yaml">
          {`states:
  - extends: 009-order-management-matcher
    id: corp-credit-risk-demo
    audience: risk-platform-team
    overlays:
      - internal-auth
      - approved-market-data
      - bank-specific-controls`}
        </CodeBlock>
      </div>

      {/* Was an abstract pitch about what SDD "lets firms" do. We actually did it, and the measured
          cost of doing it is far more useful to another organisation than the promise. */}
      <section className={styles.auditPanel}>
        <h3>Diverging without forking away</h3>
        <p>
          The point of layering the changes rather than editing the baseline is that upstream stays
          reachable. We tested that the hard way: all fifteen states were rebased onto current FINOS
          TraderX after seven weeks of upstream movement. Upstream had shipped{' '}
          <strong>62 commits and changed zero lines of application code</strong> — and catching up
          still took roughly <strong>150 hand edits</strong>, none of which <code>git</code> could
          show us, because the work is in the layers rather than the files.
        </p>
        <ul>
          <li>
            Requirements stay traceable from spec to generated code to the demo that runs it.
          </li>
          <li>
            Our states are overlays, so an upstream change lands underneath them instead of
            conflicting with them.
          </li>
        </ul>
      </section>
    </div>
  );
}

function SpecKitPanel() {
  return (
    <div>
      <SectionIntro title="The GitHub Spec Kit Engine">
        Spec Kit replaces non-deterministic AI vibe coding with a structured pipeline. AI assists
        with reasoning and decomposition, while deterministic patch scripts control emitted files.
      </SectionIntro>

      <div className={styles.officialLinks} aria-label="Official Spec Kit links">
        <ExternalLink href="https://github.github.com/spec-kit/index.html">
          Official Spec Kit Docs
        </ExternalLink>
        <ExternalLink href="https://github.github.com/spec-kit/quickstart.html">
          Quickstart
        </ExternalLink>
        <ExternalLink href="https://github.com/github/spec-kit">
          GitHub Repository
        </ExternalLink>
      </div>

      <div className={styles.speckitGrid}>
        <div className={styles.steps}>
          {specKitSteps.map(([title, description], index) => (
            <article key={title} className={styles.step}>
              <span>{index + 1}</span>
              <div>
                <h3>{title}</h3>
                <p>{description}</p>
              </div>
            </article>
          ))}
        </div>

        <div className={styles.codeStack}>
          <CodeBlock title="Specify the Desired State" label="spec.md">
            {`Feature: Replace polling with push-driven order updates

User story:
  As a learner, I can see orders move from new to filled in real time.

Requirement:
  Preserve inherited trade and position behavior while adding orders.`}
          </CodeBlock>
          <CodeBlock title="Generate a Demo State" label="terminal">
            {`bash pipeline/generate-state.sh 009-order-management-matcher
./scripts/start-state-009-order-management-matcher-generated.sh

# Output: a runnable demo branch linked back to the reviewed spec.`}
          </CodeBlock>
        </div>
      </div>
    </div>
  );
}

export default function ActivePanel({activeTab}) {
  const panels = {
    what: <WhatPanel />,
    paths: <PathsPanel />,
    sdd: <SddPanel />,
    speckit: <SpecKitPanel />,
  };

  return (
    <section
      id={`panel-${activeTab}`}
      role="tabpanel"
      aria-labelledby={`tab-${activeTab}`}
      className={styles.activePanel}
    >
      {panels[activeTab]}
    </section>
  );
}
