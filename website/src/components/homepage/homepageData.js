import stateCatalog from '../../../../catalog/state-catalog.json';
import liveEnvironments from '../../../../catalog/live-environments.json';

// This deployment's repository, not upstream's. Every catalog/source link built from this constant
// was pointing readers at finos/traderX, where our state branches and catalog files do not exist.
export const repoBaseUrl = 'https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX';
export const upstreamRepoUrl = 'https://github.com/finos/traderX';

// Where a "go to the repository" link should land. The default branch is not the interesting one:
// YU15 is the tip state, the only branch carrying every ancestor's spec pack, and the state this
// site documents. repoBaseUrl stays the bare root because catalog and per-state links build their
// own /blob/<ref>/ and /tree/<branch>/ paths from it — do not fold the branch into that constant.
export const repoTreeUrl = `${repoBaseUrl}/tree/YU15-eod-risk-extract`;
const trackLabels = {
  prelude: 'Prelude Track',
  baseline: 'Baseline Track',
  architecture: 'Architecture Track',
  nonfunctional: 'Nonfunctional Track',
  functional: 'Functional Track',
  devex: 'Developer Experience Track',
};

const trackTones = {
  prelude: 'blue',
  baseline: 'blue',
  architecture: 'violet',
  nonfunctional: 'violet',
  functional: 'cyan',
  devex: 'green',
};

function stripNumberedPrefix(pathSegment) {
  return pathSegment.replace(/^\d{3}-/, '');
}

// The badge shown on each state card. `slice(0, 3)` was written for the numeric `001-…` ids and
// truncated every YU state to "YU0" or "YU1", so fifteen states shared two labels. Take the segment
// before the first dash instead, which is correct for both `001-baseline…` and `YU13-limit-order…`.
function stateNumber(stateId) {
  const head = stateId.split('-')[0];
  return /^\d/.test(head) ? head.slice(0, 3) : head;
}

function featurePackSlug(featurePack) {
  return stripNumberedPrefix(featurePack.split('/').pop());
}

function flowArtifactPath(state) {
  return state.id === '001-baseline-uncontainerized-parity'
    ? 'system/end-to-end-flows'
    : 'system/runtime-topology';
}

function stateDescription(state) {
  const role = state.primaryLineageRole === 'optional' ? 'Optional branch' : 'Canonical state';
  const convergence =
    state.convergenceLevel && state.convergenceLevel !== 'none'
      ? `, ${state.convergenceLevel} convergence checkpoint`
      : '';
  return `${role} on the ${trackLabels[state.track] || state.track} (${state.status}${convergence}).`;
}

export const catalogSource = {
  version: stateCatalog.version,
  stateCount: stateCatalog.states.length,
  catalogUrl: `${repoBaseUrl}/blob/main/catalog/state-catalog.json`,
  liveEnvironmentVersion: liveEnvironments.version,
  liveEnvironmentCount: liveEnvironments.environments.length,
  liveEnvironmentCatalogUrl: `${repoBaseUrl}/blob/main/catalog/live-environments.json`,
  liveEnvironmentsDocs: '/docs/spec-kit/live-environments',
  generatedBranchesDocs: '/docs/spec-kit/generated-state-branches',
  learningPaths: '/docs/learning-paths',
};

export const catalogStates = stateCatalog.states.map((state) => {
  const specSlug = featurePackSlug(state.featurePack);
  const specPath = `/specs/${specSlug}`;
  const branch = state.publish?.branch;

  return {
    id: state.id,
    number: stateNumber(state.id),
    title: state.title,
    description: stateDescription(state),
    status: state.status,
    track: state.track,
    trackLabel: trackLabels[state.track] || state.track,
    tone: trackTones[state.track] || 'blue',
    convergenceLevel: state.convergenceLevel,
    isConvergence: state.isConvergence,
    primaryLineageRole: state.primaryLineageRole,
    previous: state.previous || [],
    branch,
    links: {
      spec: specPath,
      architecture: `${specPath}/system/architecture`,
      runtime: `${specPath}/${flowArtifactPath(state)}`,
      learning: `/docs/learning/state-${state.id}`,
      code: branch ? `${repoBaseUrl}/tree/${branch}` : undefined,
      adr: state.decisionRecord
        ? `/${state.decisionRecord.replace(/^docs\//, 'docs/').replace(/\.md$/, '')}`
        : undefined,
    },
  };
});

const statesById = new Map(catalogStates.map((state) => [state.id, state]));

export const phaseGroups = Array.from(
  catalogStates.reduce((groups, state) => {
    if (!groups.has(state.track)) {
      groups.set(state.track, {
        track: state.track,
        label: state.trackLabel,
        title: state.trackLabel,
        tone: state.tone,
        states: [],
      });
    }
    groups.get(state.track).states.push(state);
    return groups;
  }, new Map()).values(),
);

export const tabs = [
  {id: 'what', label: 'What is TraderX?', icon: 'layers'},
  {id: 'paths', label: 'Canonical Learning Path', icon: 'route'},
  {id: 'sdd', label: 'Spec-Driven Development', icon: 'code'},
  {id: 'speckit', label: 'What is Spec Kit?', icon: 'bot'},
];

export const internalNav = [
  {to: '/docs/home', label: 'Docs'},
  {to: '/docs/spec-kit/getting-started-with-traderx', label: 'Getting Started'},
  {to: '/specs', label: 'Specs'},
  {to: '/docs/learning', label: 'Learning'},
  {to: '/docs/blog', label: 'Blog'},
];

// Reframed from upstream's positioning ("Serving FINOS", "Reinvent the FINOS hackathon", "sponsors
// who need proof of value"). That is FINOS describing its own project to its own community; on this
// site it read as though FINOS were the publisher. These cards say what THIS deployment did, and the
// upstream relationship is stated as what it actually is — a fork that tracks its parent.
export const overviewCards = [
  {
    title: 'The Goal',
    description:
      'Take the reference demo to a system a sell-side desk would recognise: correctness, risk controls, post-trade and audit first — latency second.',
    tone: 'blue',
    icon: 'target',
  },
  {
    title: 'Upstream, Tracked',
    description:
      'A fork that stays a fork. All fifteen states were rebased onto current FINOS TraderX, absorbing upstream commits and security fixes with every suite still green.',
    tone: 'cyan',
    icon: 'handshake',
  },
  {
    title: 'Tested Continuously',
    description:
      'Every architectural claim is pinned by tests that run on each push, backed by end-to-end proofs against a live cluster.',
    tone: 'slate',
    icon: 'users',
  },
];

export const liveEnvironmentCards = liveEnvironments.environments.map((environment) => {
  const state = statesById.get(environment.stateId);
  return {
    id: environment.id,
    href: environment.url,
    title: environment.name,
    description: environment.notes,
    status: environment.status,
    stateId: environment.stateId,
    stateTitle: state?.title || environment.stateId,
    stateBranch: environment.stateBranch,
    tone: state?.tone || 'blue',
    icon: state?.track === 'functional' ? 'bolt' : 'monitor',
    links: state?.links,
  };
});

export const documentationCards = [
  {
    href: '/llms.txt',
    title: 'LLM-Native Docs',
    description:
      'Generated by the configured Docusaurus LLM docs plugin during the website build.',
    tone: 'green',
    icon: 'bot',
  },
];

export const specKitSteps = [
  [
    'speckit.init',
    'Establishes repository constitution, governance guardrails, and dependency matrices.',
  ],
  [
    'speckit.specify',
    'Turns business intent into requirements, scenarios, and success criteria that demos must preserve.',
  ],
  [
    'speckit.plan & .tasks',
    'Maps the spec into architecture decisions, implementation slices, and verification work.',
  ],
  [
    'speckit.implement',
    'Generates demo-ready states while keeping links back to the source requirements.',
  ],
];
