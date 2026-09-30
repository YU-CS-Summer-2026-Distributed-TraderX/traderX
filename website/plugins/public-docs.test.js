const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const plugin = require('./remark-public-prose');
const root = path.resolve(__dirname,'../..');
test('edits prose while preserving code, quotations and stable heading anchors', () => {
  const tree={type:'root',children:[
    {type:'heading',depth:2,children:[{type:'text',value:'A — B'}]},
    {type:'paragraph',children:[{type:'text',value:'Two parts — one result.'},{type:'inlineCode',value:'x—y'}]},
    {type:'code',value:'echo "a—b"'},
    {type:'blockquote',children:[{type:'text',value:'A — quotation'}]},
    {type:'paragraph',children:[{type:'text',value:'Preserve “a — b” literally.'}]},
  ]};
  plugin()(tree,{path:path.join(root,'docs/home.mdx')});
  assert.equal(tree.children[0].children[0].value,'A; B');
  assert.equal(tree.children[0].data.hProperties.id,'a--b');
  assert.equal(tree.children[1].children[1].value,'x—y');
  assert.equal(tree.children[2].value,'echo "a—b"');
  assert.equal(tree.children[3].children[0].value,'A — quotation');
  assert.equal(tree.children[4].children[0].value,'Preserve “a — b” literally.');
});
test('routes cross-plugin links and removes private handoff destinations',()=>{
 const source=path.join(root,'specs/YU18-risk-integration/components/order-types/spec.md');
 assert.equal(plugin.publicLink('../../../../docs/spec-kit/state-components.md',source),'/docs/spec-kit/state-components');
 assert.equal(plugin.publicLink('../../../../docs/risk-integration/ri03-local-container.md',source),'/docs/engineering/integration-and-recovery');
 assert.equal(plugin.publicLink('../../../../docs/handoff/private.md',source),null);
 assert.equal(plugin.publicLink('/docs/risk-integration/chat.md',source),'/docs/engineering/integration-and-recovery');
 assert.equal(plugin.publicLink('https://example.com/a—b',source),'https://example.com/a—b');
});
