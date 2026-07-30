function labelFor(item) {
  if (!item) {
    return ''
  }
  if (typeof item.label === 'string' && item.label.length > 0) {
    return item.label
  }
  if (typeof item.id === 'string') {
    return item.id
  }
  if (typeof item.href === 'string') {
    return item.href
  }
  return ''
}

function normalizeLabel(label) {
  return (label || '')
    .toLowerCase()
    .replace(/^[^a-z0-9]+/i, '')
    .trim()
}

function docSlug(item) {
  if (item?.type !== 'doc' || typeof item.id !== 'string') {
    return ''
  }
  const parts = item.id.split('/')
  return parts[parts.length - 1]?.toLowerCase() ?? ''
}

function priorityFor(item) {
  if (item?.type === 'doc') {
    const slug = docSlug(item)
    const order = {
      readme: 0,
      spec: 1,
      plan: 2,
      tasks: 3,
      research: 4,
      'data-model': 5,
      quickstart: 6,
      'fidelity-profile': 7,
    }
    if (Object.prototype.hasOwnProperty.call(order, slug)) {
      return order[slug]
    }
    return 8
  }

  if (item?.type === 'category') {
    const pinned = priorityOverrideFor(item.label)
    if (pinned !== undefined) {
      return pinned
    }
    const label = normalizeLabel(item.label || '')
    if (label === 'components') {
      return 20
    }
    if (label === 'system') {
      return 21
    }
    if (label === 'contracts') {
      return 22
    }
    if (label === 'requirements') {
      return 23
    }
    if (label === 'conformance') {
      return 24
    }
    if (label === 'tests') {
      return 25
    }
    if (label === 'generation') {
      return 26
    }
  }

  return 30
}

function sortItems(items) {
  return [...items].sort((left, right) => {
    const pLeft = priorityFor(left)
    const pRight = priorityFor(right)
    if (pLeft !== pRight) {
      return pLeft - pRight
    }
    return labelFor(left).localeCompare(labelFor(right), undefined, {
      numeric: true,
      sensitivity: 'base',
    })
  })
}

// Sidebar labels come in three real shapes, and a matcher has to cover all of them:
//   "001 Simple App - Base Uncontainerized App"        bare number
//   "Feature Pack 009b: LMAX Sequencer Architecture"   number with a letter suffix
//   "Feature Pack: YU02-lmax-kubernetes"               colon, then a YU key
// The previous matcher required three digits followed by a word boundary, so it missed BOTH the
// `009b` pack (no boundary between "9" and "b") and every `YU..` pack (colon, and not a digit).
// The result was that our own states were the only ones rendered without the 📦 decoration.
const PACK_KEY_RE = /^(?:feature pack)?\s*:?\s*([0-9]{3}[a-z]?|YU[0-9]{2})\b/i

function packKeyFor(label) {
  const match = PACK_KEY_RE.exec((label || '').replace(/^📦\s*/, '').trim())
  return match ? match[1].toUpperCase() : null
}

function isStatePackLabel(label) {
  return packKeyFor(label) !== null
}

// No label overrides are needed: the pack was renamed on disk from
// `009b-lmax-sequencer-architecture` to `YU01-lmax-sequencer`, so its label now reads YU01 from the
// source of truth rather than being rewritten on the way to the screen.
const PACK_LABEL_OVERRIDES = {}

// Pin the portal pack directly beneath the Overview doc. Every pack is a category and so lands on
// the same default priority, which sorts them alphabetically — putting the portal pack last.
// Keyed by label text, not pack number: this pack's sidebar label is plain "Docs Portal Homepage",
// with no "Feature Pack" prefix and no 015 anywhere in it, so there is no number to key on.
const LABEL_PRIORITY_OVERRIDES = {
  'docs portal homepage': 10,
}

function priorityOverrideFor(label) {
  const stripped = (label || '').replace(/^📦\s*/, '').trim()
  const byLabel = LABEL_PRIORITY_OVERRIDES[stripped.toLowerCase()]
  if (byLabel !== undefined) {
    return byLabel
  }
  return undefined
}

function transformItem(item, depth = 0) {
  if (item?.type === 'doc') {
    const slug = docSlug(item)
    const docDecorations = {
      readme: '📘 Overview',
      spec: '📜 Specification',
      plan: '🗺️ Implementation Plan',
      tasks: '✅ Tasks',
      research: '🔎 Research',
      'data-model': '🧱 Data Model',
      quickstart: '🚀 Quickstart',
      'fidelity-profile': '🎯 Fidelity Profile',
    }
    if (docDecorations[slug]) {
      return {
        ...item,
        label: docDecorations[slug],
      }
    }
    return item
  }

  if (item?.type !== 'category') {
    return item
  }

  const label = item.label || ''
  const normalized = normalizeLabel(label)
  let decoratedLabel = label

  const categoryDecorations = {
    components: '🧩 Components',
    system: '💻 System',
    contracts: '📜 Contracts',
    conformance: '✅ Conformance',
    generation: '⚙️ Generation',
    requirements: '🧾 Requirements',
    tests: '🧪 Tests',
  }

  if (depth === 0 && isStatePackLabel(label)) {
    const rename = PACK_LABEL_OVERRIDES[packKeyFor(label)]
    decoratedLabel = `📦 ${rename ? rename(label) : label}`
  } else if (categoryDecorations[normalized]) {
    decoratedLabel = categoryDecorations[normalized]
  }

  const children = Array.isArray(item.items)
    ? item.items.map((child) => transformItem(child, depth + 1))
    : []

  return {
    ...item,
    label: decoratedLabel,
    items: sortItems(children),
  }
}

module.exports = async function specsSidebarItemsGenerator({
  defaultSidebarItemsGenerator,
  ...args
}) {
  const generated = await defaultSidebarItemsGenerator(args)
  const transformed = generated.map((item) => transformItem(item, 0))
  return sortItems(transformed)
}
