// Docs at https://docusaurus.io/docs

const projectName = 'traderX'
const projectSlug = 'traderX'
// Site identity. This deployment is Yeshiva University's own build of TraderX, so YU is the primary
// identity — but TraderX is a FINOS project and the upstream credit stays visible and correct
// (footer logo, an Upstream links column, and the copyright line below). Presenting a fork as if it
// were unaffiliated would be both wrong and useless to Dov, who wants to point at it AS a FINOS
// showcase of an outside organisation running with TraderX.
const siteTitle = 'Distributed TraderX'
// The wordmark shown beside the logo in the top-left of every page. Deliberately shorter than
// siteTitle: next to the YU mark it only needs to say which application this is.
const navbarTitle = 'TraderX'
const siteOrg = 'Yeshiva University'
const siteTagline = 'A sell-side OMS on the LMAX architecture — Yeshiva University CS'
const copyrightOwner = 'Yeshiva University CS · built on TraderX, a FINOS project'
const docsUrl = process.env.DOCUSAURUS_URL || 'https://YU-CS-Summer-2026-Distributed-TraderX.github.io'
const docsBaseUrl = process.env.DOCUSAURUS_BASE_URL || '/traderX/'

function pathBrowserPolyfillPlugin() {
  return {
    name: 'path-browser-polyfill',
    configureWebpack() {
      return {
        resolve: {
          fallback: {
            path: require.resolve('path-browserify'),
          },
        },
      }
    },
  }
}

// GitHub repo configuration - update these for forks/branches
const repoOwner = 'YU-CS-Summer-2026-Distributed-TraderX';
// Allow override via environment (e.g., DOCS_BRANCH=feature-branch).
// Default is the branch the site is actually built from, NOT main: on this fork main is the upstream
// sync and does not carry our docs/ or specs/, so "Edit this page" against main 404s for every page
// added here. Change this if the site starts deploying from a different branch.
const repoBranch = process.env.DOCS_BRANCH || 'YU15-eod-risk-extract';
const repoUrl = `https://github.com/${repoOwner}/${projectSlug}`;

// Remark plugin to transform relative links to GitHub URLs
const transformRelativeLinks = require('./src/remark/transformRelativeLinks');

module.exports = {
  markdown: {
    mermaid: true,
    format: 'md',
  },
  themes: ['@docusaurus/theme-mermaid'],
  // src/mermaid-zoom-client.js was written and styled (see .tx-mermaid-zoom-* in custom.css) but
  // never registered, so the zoom controls never mounted on any page — which is why the diagrams
  // read as small and un-zoomable. A client module has to be declared here to run.
  clientModules: [require.resolve('./src/mermaid-zoom-client.js')],
  onBrokenLinks: 'ignore',
  title: `${siteTitle} · ${siteOrg}`,
  tagline: siteTagline,
  url: docsUrl,
  baseUrl: docsBaseUrl,
  trailingSlash: false,
  favicon: 'img/yu/yu-cs-shield.png',
  projectName: `${projectName}`,
  organizationName: 'YU-CS-Summer-2026-Distributed-TraderX',
  customFields: {
    repoUrl: repoUrl,
  },
  scripts: ['https://buttons.github.io/buttons.js'],
  stylesheets: ['https://fonts.googleapis.com/css?family=Overpass:400,400i,700'],
  themeConfig: {
    announcementBar: {
      id: 'yu-traderx-welcome',
      backgroundColor: '#23437c',
      textColor: '#ffffff',
      isCloseable: false,
      content:
        '<strong>Yeshiva University CS</strong> — TraderX rebuilt on the LMAX architecture over an Aeron Raft cluster. Read the <a href="/docs/blog/2026-03-29-traderx-speckit-migration"><strong>engineering story</strong></a>.',
    },
    navbar: {
      title: navbarTitle,
      logo: {
        alt: 'Yeshiva University Computer Science',
        // Transparent PNG. The original is opaque-on-white, which rendered as a white card around
        // the shield on the navy navbar.
        src: 'img/yu/yu-cs-shield-transparent.png',
      },
      items: [
        {to: '/docs/home', label: 'Overview', position: 'right'},
        {to: '/docs/spec-kit/getting-started-with-traderx', label: 'Getting Started', position: 'right'},
        {to: '/specs', label: 'Specs', position: 'right'},
        {to: '/docs/spec-kit/state-docs', label: 'State Docs', position: 'right'},
        {to: '/docs/adr', label: 'ADRs', position: 'right'},
        {to: '/docs/learning', label: 'Learning', position: 'right'},
        {type: 'search', position: 'right'},
        {
          // Was https://github.com/finos/ — the FINOS org root, which is neither this fork nor even
          // the upstream repo. Points at our repository now; upstream is credited in the footer.
          href: repoUrl,
          label: 'GitHub',
          position: 'right',
        }
      ],
    },
    footer: {
      copyright: `Copyright © ${new Date().getFullYear()} ${copyrightOwner}`,
      logo: {
        alt: 'Yeshiva University',
        // Navy-ground crest rather than the white-ground one: the footer is dark, and a white plate
        // sitting on it read as a stray tile.
        src: 'img/yu/yu-crest-navy.jpg',
        href: 'https://www.yu.edu'
      },
      links: [
        {
          title: 'Docs',
          items: [
            {
              label: 'Home',
              to: '/docs/home',
            },
            {
              label: 'Blog',
              to: '/docs/blog',
            },
            {
              label: 'Getting Started',
              to: '/docs/spec-kit/getting-started-with-traderx',
            },
            {
              label: 'Learning Paths',
              to: '/docs/learning-paths',
            },
            {
              label: 'Learning Guides',
              to: '/docs/learning',
            },
            {
              label: 'Specs',
              to: '/specs',
            }
          ]
        },
        {
          // YU column added, and the two FINOS columns below consolidated into one. Six upstream
          // links and none of our own made the site read as FINOS's rather than an organisation's
          // own deployment, which is the exact thing this site is supposed to demonstrate.
          title: 'Yeshiva University',
          items: [
            {
              label: 'Yeshiva University',
              to: 'https://www.yu.edu',
            },
            {
              label: 'YU Computer Science',
              to: 'https://www.yu.edu/yeshiva-college/ug/computer-science',
            },
            {
              label: 'This deployment on GitHub',
              to: repoUrl,
            }
          ]
        },
        {
          title: 'Upstream — FINOS',
          items: [
            {
              label: 'TraderX (upstream)',
              to: 'https://github.com/finos/traderX',
            },
            {
              label: 'FINOS Website',
              to: 'https://www.finos.org/',
            },
            {
              label: 'FINOS Projects',
              to: 'https://landscape.finos.org',
            },
            {
              label: 'Community Handbook',
              to: 'https://community.finos.org/',
            }
          ]
        },
      ]
    },
  },
  presets: [
    [
      '@docusaurus/preset-classic',
      {
        docs: {
          path: '../docs',
          // `handoff/**` is internal working material — planning briefs, per-session recaps,
          // cross-lane critiques and proposals. It was being published in full, which put internal
          // decisions, named collaborators and private correspondence on a public, crawlable site.
          // The public-facing versions of the material worth sharing live in docs/engineering/.
          // Do not remove this exclusion to fix a broken link; move the page instead.
          exclude: [
            'prompt-ideas/**', 'migration/**', 'migration/**/*', '**/migration/**',
            'guide/adr/**', 'guide/adr/**/*',
            'handoff/**', 'handoff/**/*', '**/handoff/**',
          ],
          editUrl:
            `${repoUrl}/edit/${repoBranch}/website/`,
          sidebarPath: require.resolve('./sidebars.js')
        },
        theme: {
          customCss: require.resolve('./src/css/custom.css'),
        }
      }
    ]
  ],
  plugins: [
    [
      require.resolve('docusaurus-lunr-search'),
      {
        languages: ['en'],
      },
    ],
    [
      'docusaurus-plugin-llms',
      {
        docsDir: '../docs',
        // This plugin walks docsDir ITSELF and does not honour the docs preset's `exclude`, so the
        // internal handoff tree was still being indexed into llms.txt — 45 entries, each with its
        // title and an excerpt of its opening lines. Excluding it in one place is not enough; every
        // generator that reads ../docs needs telling separately.
        ignoreFiles: ['handoff/**', 'handoff/**/*', 'prompt-ideas/**', 'migration/**'],
        includeBlog: false,
        generateLLMsTxt: true,
        generateLLMsFullTxt: true,
        generateMarkdownFiles: false,
        excludeImports: true,
        removeDuplicateHeadings: true,
      },
    ],
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'traderspec-root-specs',
        path: '../specs',
        routeBasePath: 'specs',
        sidebarPath: require.resolve('./traderspec-root-specs.sidebars.js'),
        sidebarItemsGenerator: require('./plugins/specs-sidebar-items-generator'),
        remarkPlugins: [require('./plugins/remark-speckit-reference-links')],
        include: ['**/*.md'],
        editUrl: `${repoUrl}/edit/${repoBranch}/specs/`,
      },
    ],
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'traderspec-specify',
        path: '../.specify',
        routeBasePath: 'specify',
        sidebarPath: require.resolve('./traderspec-specify.sidebars.js'),
        include: ['memory/**/*.md'],
        editUrl: `${repoUrl}/edit/${repoBranch}/.specify/`,
      },
    ],
    pathBrowserPolyfillPlugin,
  ],
};
