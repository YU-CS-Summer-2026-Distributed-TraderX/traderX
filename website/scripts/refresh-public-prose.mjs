// Apply an editorial punctuation pass only to authored public docs. Generated learning pages
// are handled by their generator; shared spec contracts are processed by the renderer.
import fs from 'node:fs';
import path from 'node:path';
import {unified} from 'unified';
import remarkParse from 'remark-parse';
import remarkFrontmatter from 'remark-frontmatter';
const root = path.resolve(import.meta.dirname, '../..');
const files = fs.readdirSync(path.join(root, 'docs'), {recursive: true})
  .filter(p => /\.(md|mdx)$/.test(p) && !/^(handoff|risk-integration|migration|prompt-ideas|learning|learning-paths)\//.test(p) && p !== 'spec-kit/state-docs.md');
let changed = 0;
for (const file of files) {
 const full = path.join(root, 'docs', file), original = fs.readFileSync(full, 'utf8');
 const tree = unified().use(remarkParse).use(remarkFrontmatter).parse(original), edits=[];
 function walk(node, heading=false) {
  if (['code','inlineCode','blockquote','html','yaml'].includes(node.type)) return;
  if (node.type==='text' && node.value.includes('—')) {
   const start=node.position.start.offset,end=node.position.end.offset;
   const old=original.slice(start,end);
   // Quoted text is source material; retain it literally.
   if (!/[“”"]/.test(old)) edits.push([start,end,old.replace(/\s*—\s*/g,heading ? ': ' : '; ')]);
  }
  node.children?.forEach(child=>walk(child,heading || node.type==='heading'));
 }
 walk(tree);
 let text=original;
 for(const [start,end,replacement] of edits.sort((a,b)=>b[0]-a[0]))text=text.slice(0,start)+replacement+text.slice(end);
 if(text!==original){fs.writeFileSync(full,text);changed++;}
}
console.log(`Updated prose punctuation in ${changed} authored documents; code and quotes preserved.`);
