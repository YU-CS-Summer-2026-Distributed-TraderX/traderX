module.exports = {
  mainSidebar: [
    'home',
    // Top-level, directly under the portal: "what's new" is the entry point for a reader who has
    // not been told what this build IS yet, so burying it inside Testing put the answer behind the
    // question. It still sits immediately before the Testing category, which keeps its pager
    // pointing at Testing strategy — what was built, then how each claim is checked.
    'engineering/whats-new',
    {
      type: 'category',
      label: 'Testing',
      items: ['engineering/testing-strategy', 'engineering/test-coverage'],
    },
    // After Testing, and top-level rather than inside it: tracing and the tick store are neither
    // states nor test tiers, so they had nowhere to live. What's new links here for the long version.
    'engineering/observability-and-replay',
    {
      type: 'category',
      label: 'Engineering Blog',
      link: {
        type: 'doc',
        id: 'blog/index',
      },
      items: [
        'blog/2026-05-20-were-finally-live-demos',
        'blog/2026-04-18-fdc3-integration-lessons',
        'blog/2026-04-10-custom-overlay-model-and-example',
        'blog/2026-04-10-state-003-agentic-harness-foundation',
        'blog/2026-04-06-convergence-milestone-releases',
        'blog/2026-04-06-state-009-order-management-matcher',
        'blog/2026-04-06-state-007-observability-baseline',
        'blog/2026-03-31-state-011-pricing-awareness-functional-track',
        'blog/2026-03-31-state-010-postgres-architecture-track',
        'blog/2026-03-31-state-007-nats-architecture-track',
        'blog/2026-03-29-traderx-speckit-migration',
        'blog/2024-09-platform-paths-kubernetes-radius',
        'blog/2024-03-docs-portal-and-project-storytelling',
        'blog/2023-11-osff-nyc-keynote-and-go-live',
        'blog/2023-09-open-source-hardening-and-governance',
        'blog/2023-05-from-hackathon-to-open-source-baseline',
      ],
    },
    {
      type: 'category',
      label: 'Start Here',
      link: {
        type: 'doc',
        id: 'spec-kit/getting-started-with-traderx',
      },
      items: [
        'spec-kit/getting-started-with-traderx',
        {
          type: 'category',
          label: 'Core Concepts',
          items: [
            'spec-kit/index',
            'spec-kit/why-speckit',
            'spec-kit/baseline-vs-parity',
          ],
        },
        {
          type: 'category',
          label: 'Generation and Validation',
          items: [
            'spec-kit/spec-kit-generation-guide',
            'spec-kit/spec-kit-workflow',
            'spec-kit/state-docs',
            'spec-kit/live-environments',
            'spec-kit/convergence-states',
            'spec-kit/generated-state-branches',
            'spec-kit/generated-state-ci',
            'spec-kit/aws-ec2-compose-prerequisites',
            'spec-kit/aws-ec2-kubernetes-prerequisites',
            'spec-kit/run-generated-overlays',
          ],
        },
        {
          type: 'category',
          label: 'Customizing TraderX',
          link: {
            type: 'doc',
            id: 'spec-kit/customizing-traderx',
          },
          items: [
            'spec-kit/customizing-traderx',
            'spec-kit/corporate-environments-guide',
            'spec-kit/custom-overlay-architecture',
            'spec-kit/custom-environments-guide',
          ],
        },
      ],
    },
    {
      type: 'category',
      label: 'SpecKit Sources',
      items: [
        'spec-kit/spec-kit-portal',
        {
          type: 'link',
          label: 'Spec Catalog',
          href: '/specs',
        },
        {
          type: 'link',
          label: 'Constitution',
          href: '/specify/memory/constitution',
        },
        {
          type: 'link',
          label: 'Standalone API Explorer',
          href: '/docs/spec-kit/api-explorer',
        },
      ],
    },
    {
      type: 'category',
      label: 'Learning Paths',
      items: [
        'learning/index',
        'learning/state-001-baseline-uncontainerized-parity',
        'learning/state-002-edge-proxy-uncontainerized',
        'learning/state-003-agentic-harness-foundation',
        'learning/state-004-containerized-compose-runtime',
        'learning/state-005-postgres-database-replacement',
        'learning/state-006-messaging-nats-replacement',
        'learning/state-007-observability-lgtm-compose',
        'learning/state-008-pricing-awareness-market-data',
        'learning/state-009-order-management-matcher',
        'learning/state-010-kubernetes-runtime',
        'learning/state-011-tilt-kubernetes-dev-loop',
        'learning/state-012-platform-convergence-c3',
        'learning/state-013-radius-kubernetes-platform',
        'learning/state-014-fdc3-intent-interoperability',
        'learning/state-YU01-lmax-sequencer',
        'learning/state-YU02-lmax-kubernetes',
        'learning/state-YU03-in-memory-risk-gateway',
        'learning/state-YU04-durable-control-feeds',
        'learning/state-YU05-post-trade-compliance',
        'learning/state-YU06-eod-price-production',
        'learning/state-YU07-historical-tick-store',
        'learning/state-YU08-execution-algo-engine',
        'learning/state-YU09-ops-hardening',
        'learning/state-YU10-fix-ingress',
        'learning/state-YU11-aeron-replication',
        'learning/state-YU12-aeron-cluster',
        'learning/state-YU13-limit-order-book',
        'learning/state-YU14-listed-equity-options',
        'learning/state-YU15-eod-risk-extract',
        'learning/state-YU16-cdm-instruments',
        'learning/state-YU17-otc-rates',
        'learning-paths/index',
        'spec-kit/spec-kit-learning-path-strategy',
        'spec-kit/state-transition-generation-plan',
      ],
    },
    {
      type: 'category',
      label: 'Reference',
      items: [
        {
          type: 'category',
          label: 'Project ADRs',
          items: [
            {
              type: 'autogenerated',
              dirName: 'adr',
            },
          ],
        },
        'spec-kit/api-explorer',
        {
          type: 'link',
          label: 'Official Spec Kit Docs',
          href: 'https://github.github.com/spec-kit/index.html',
        },
      ],
    },
  ],
}
