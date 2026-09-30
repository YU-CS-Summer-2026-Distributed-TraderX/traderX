// Every reader of repository Markdown must apply the same private-material boundary.
const docsExclude = [
  'prompt-ideas/**', 'migration/**', '**/migration/**', 'guide/adr/**',
  'handoff/**', '**/handoff/**', 'risk-integration/**',
  '**/evidence/**', '**/review-evidence/**', '**/node_modules/**',
];
const specsExclude = [
  '**/generation/**', '**/evidence/**', '**/review-evidence/**',
  '**/handoff/**', '**/node_modules/**',
];
module.exports = {docsExclude, specsExclude};
