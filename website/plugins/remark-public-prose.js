const path = require('node:path');
const fs = require('node:fs');
const {createSlugger} = require('@docusaurus/utils');
const root = path.resolve(__dirname, '../..');

// Only prose nodes are edited. Code, math, URLs and quoted blocks retain their exact bytes.
// Spec sources are shared with runtime generation, so presentation punctuation is handled here.
function prose(value) {
  return value.split(/("[^"\n]*"|“[^”\n]*”)/g).map((part, i) => i % 2 ? part : part.replace(/\s*—\s*/g, '; ').replace(/;\s*([.,;:!?])/g, '$1')).join('');
}
function publicLink(url, source) {
  if (!url || /^(?:[a-z]+:|#)/i.test(url)) return url;
  if (/(?:^|\/)docs\/risk-integration\//.test(url)) return '/docs/engineering/integration-and-recovery';
  if (url.startsWith('/issues/')) return '/docs/engineering/feature-map';
  if (url.startsWith('/')) return url;
  const [target, hash] = url.split('#');
  const absolute = path.resolve(path.dirname(source), target);
  const relative = path.relative(root, absolute).replaceAll(path.sep, '/');
  if (/^(?:docs\/)?(?:handoff|coordination)\//.test(relative) || relative.includes('/review-evidence/')) return null;
  if (relative.startsWith('issues/')) return '/docs/engineering/feature-map';
  if (relative.startsWith('docs/risk-integration/')) return '/docs/engineering/integration-and-recovery';
  if (/\.(md|mdx)$/.test(relative) && fs.existsSync(absolute)) {
    const sourcePart = path.relative(root, source).split(path.sep)[0];
    const targetPart = relative.split('/')[0];
    if (targetPart !== sourcePart && ['docs', 'specs', '.specify'].includes(targetPart)) {
      const content = fs.readFileSync(absolute, 'utf8');
      const slug = content.match(/^slug:\s*["']?([^\n"']+)/m)?.[1];
      let route = relative.replace(/\.(md|mdx)$/, '').replace(/^\.specify\//, 'specify/');
      route = route.split('/').map(part => part.replace(/^\d{3}[a-z]?-(?=\w)/, '')).join('/');
      route = route.replace(/\/(?:README|index)$/i, '');
      if (slug) route = `${targetPart === '.specify' ? 'specify' : targetPart}/${slug.replace(/^\//, '')}`;
      return `/${route}${hash ? '#' + hash : ''}`;
    }
    return url;
  }
  // Files outside docs plugins are source links, not routes relative to the current page.
  if (!relative.startsWith('..') && fs.existsSync(absolute)) {
    return `https://github.com/YU-CS-Summer-2026-Distributed-TraderX/traderX/${fs.statSync(absolute).isDirectory() ? 'tree' : 'blob'}/traderX-risk-integration/${relative}${hash ? '#' + hash : ''}`;
  }
  return url;
}
module.exports = function remarkPublicProse() {
  return function transform(tree, file) {
    const source = file.path || file.history?.[0] || root;
    const slugger = createSlugger();
    function nodeText(n) { return n.value || (n.children || []).map(nodeText).join(''); }
    function walk(node) {
      if (['code', 'inlineCode', 'blockquote', 'math', 'inlineMath'].includes(node.type)) return;
      if (node.type === 'heading') {
        const original = nodeText(node);
        const id = slugger.slug(original);
        if (!/\{#[^}]+\}/.test(original)) {
          node.data ||= {}; node.data.hProperties ||= {}; node.data.hProperties.id ||= id;
        }
      }
      if (node.type === 'text') node.value = prose(node.value);
      if (node.type === 'link') {
        const url = publicLink(node.url, source);
        if (url === null) {node.type = 'emphasis'; delete node.url;} else node.url = url;
      }
      if (node.children) node.children.forEach(walk);
    }
    walk(tree);
  };
};
module.exports.prose = prose;
module.exports.publicLink = publicLink;
