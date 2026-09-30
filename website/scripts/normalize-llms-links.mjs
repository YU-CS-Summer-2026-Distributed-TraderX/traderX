import fs from 'node:fs/promises'
import path from 'node:path'
import {unified} from 'unified'
import remarkParse from 'remark-parse'
import publicProse from '../plugins/remark-public-prose.js'

const outDir = path.resolve(process.cwd(), 'build')
const targets = ['llms.txt', 'llms-full.txt']
const docsUrl = process.env.DOCUSAURUS_URL || 'https://YU-CS-Summer-2026-Distributed-TraderX.github.io'
const docsBaseUrl = process.env.DOCUSAURUS_BASE_URL || '/traderX/'

// Use the renderer's routes instead of the LLM plugin's guessed .md URLs.
const publishedRoutes = new Map()
async function readRoutes(dir) {
  for (const entry of await fs.readdir(dir, {withFileTypes: true})) {
    const file = path.join(dir, entry.name)
    if (entry.isDirectory()) { await readRoutes(file); continue }
    if (!file.endsWith('.json')) continue
    const doc = JSON.parse(await fs.readFile(file, 'utf8'))
    if (!doc.source || !doc.permalink) continue
    const route = doc.permalink.slice(normalizeBasePath(docsBaseUrl).length).replace(/\/$/, '')
    const exists = await Promise.any([
      fs.access(path.join(outDir, route + '.html')),
      fs.access(path.join(outDir, route, 'index.html')),
    ]).then(() => true, () => false)
    if (exists) {
      const source = doc.source.replace('@site/../', '')
      publishedRoutes.set(source, doc.permalink)
      publishedRoutes.set(source.replace(/\.mdx$/, '.md').replace(/\/index\.md$/, '.md'), doc.permalink)
      if (route === 'docs/blog') publishedRoutes.set('blog.md', doc.permalink)
    }
  }
}
await readRoutes(path.resolve('.docusaurus/docusaurus-plugin-content-docs'))

function normalizeBasePath(baseUrl) {
  if (!baseUrl || baseUrl === '/') {
    return '/'
  }
  return `/${baseUrl.replace(/^\/+|\/+$/g, '')}/`
}

function addSiteBaseToInternalLinks(content) {
  if (!docsUrl) {
    return content
  }

  const normalizedBase = normalizeBasePath(docsBaseUrl)
  if (normalizedBase === '/') {
    return content
  }

  let origin
  try {
    origin = new URL(docsUrl).origin
  } catch {
    return content
  }

  const internalRoots = ['docs', 'specs', 'api', 'specify', 'adr', 'blog']
  let updated = content

  for (const root of internalRoots) {
    const withoutBase = `${origin}/${root}`
    const withBase = `${origin}${normalizedBase}${root}`
    updated = updated.replaceAll(withoutBase, withBase)
  }

  return updated
}

function normalizeProse(content) {
  const edits = [];
  const tree = unified().use(remarkParse).parse(content);
  function visit(node) {
    if (['code', 'inlineCode', 'blockquote', 'html'].includes(node.type)) return;
    if (node.type === 'link') {
      let url;
      try { url = new URL(node.url) } catch {}
      if (url && url.origin === new URL(docsUrl).origin) {
        const source = decodeURI(url.pathname).replace(normalizeBasePath(docsBaseUrl), '').replace(/^\//, '');
        const route = publishedRoutes.get(source);
        if (route) {
          const start = node.position.start.offset, end = node.position.end.offset;
          edits.push([start, end, content.slice(start, end).replace(node.url, new URL(route, docsUrl).href + url.hash)]);
          return;
        }
      }
    }
    if (node.type === 'text' && node.value.includes('—')) {
      const start = node.position.start.offset, end = node.position.end.offset;
      edits.push([start, end, publicProse.prose(content.slice(start, end))]);
    }
    node.children?.forEach(visit);
  }
  visit(tree);
  for (const [start, end, text] of edits.sort((a,b) => b[0]-a[0])) content = content.slice(0,start)+text+content.slice(end);
  return content;
}

function normalize(content) {
  // docusaurus-plugin-llms currently emits `docs/../docs/*` when docs live outside `website/`.
  const normalized = content
    .replaceAll('/docs/../docs/', '/docs/')
    .replaceAll('/docs/../docs', '/docs')
    .replaceAll('/../docs/', '/docs/')
  return normalizeProse(addSiteBaseToInternalLinks(normalized))
}

async function normalizeFile(fileName) {
  const filePath = path.join(outDir, fileName)

  try {
    const original = await fs.readFile(filePath, 'utf8')
    const updated = normalize(original)
    if (updated !== original) {
      await fs.writeFile(filePath, updated, 'utf8')
      console.log(`[llms] normalized links in ${fileName}`)
    }
  } catch (error) {
    if (error && error.code === 'ENOENT') {
      console.log(`[llms] skipped ${fileName} (not generated)`)
      return
    }
    throw error
  }
}

for (const target of targets) {
  await normalizeFile(target)
}
