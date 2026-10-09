"""Actual builder controls. Synthetic independent ancestry + real pipeline witness."""
import copy,importlib.util,json,os
from pathlib import Path
import shutil,subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent

def module(path,name):
 spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
RI18=module(ROOT/'scripts/tests/stp-image-provenance/test_provenance.py','ri18_fixture')
P=module(ROOT/'scripts/yu15/stp-image-provenance.py','ri18_hash')

class BuildControls(unittest.TestCase):
 def setUp(self):
  self.legacy=RI18.EntrypointControls();self.legacy.setUp()
  self.root=self.legacy.root;self.store=self.legacy.store;self.env=self.legacy.env
  for name in ('build-cluster-image.sh','build-service-images.sh','lib-state-image.sh'):
   shutil.copy2(ROOT/'scripts/yu15'/name,self.root/'scripts/yu15'/name)
  tools=self.legacy.base/'legacy-tools';shutil.copytree(self.legacy.bin,tools)
  shutil.copy2(HERE/'fake-command.py',self.legacy.bin/'docker');(self.legacy.bin/'docker').chmod(0o755)
  self.env['RI20_LEGACY_TOOLS']=str(tools)
  # Synthetic upstream and leaf owners: generation reads these independently,
  # never copying its expectation from the candidate generated output.
  catalog={'states':[{'id':'YU17-otc-rates','previous':[]},{'id':'YU18-risk-integration','previous':['YU17-otc-rates']}]}
  (self.root/'catalog/state-catalog.json').write_text(json.dumps(catalog))
  parent=self.owner('YU17-otc-rates','order-matcher');parent.mkdir(parents=True)
  (parent/'ancestor.txt').write_text('current ancestor bytes\n')
  for svc in ('trade-processor','position-service','price-publisher'):
   path=self.root/'templates'/svc;path.mkdir()
   (path/'Dockerfile').write_text('FROM scratch\n')
   shutil.copy2(self.root/'templates/order-matcher/gradlew',path/'gradlew')
   (path/'src/main/java').mkdir(parents=True);(path/'src/main/java/Service.java').write_text('class Service {}\n')
  (self.root/'pipeline/generate-state.sh').write_text('''#!/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$root/pipeline/fixture-generate.py" "$1"
''')
  (self.root/'pipeline/fixture-generate.py').write_text('''import json,shutil,sys
from pathlib import Path
r=Path(__file__).resolve().parents[1];state=sys.argv[1]
states={x['id']:x for x in json.loads((r/'catalog/state-catalog.json').read_text())['states']}
line=[]
def visit(s):
 for parent in states[s]['previous']:visit(parent)
 line.append(s)
visit(state)
target=r/'generated/code/target-generated';shutil.rmtree(target,ignore_errors=True);target.mkdir(parents=True)
for component in (r/'templates').iterdir():shutil.copytree(component,target/component.name)
for s in line:
 layer=r/'specs'/s/'generation/runtime-overrides'
 if layer.exists():
  for component in layer.iterdir():shutil.copytree(component,target/component.name,dirs_exist_ok=True)
 prunes=r/'specs'/s/'generation/prune.json'
 if prunes.exists():
  for rel in json.loads(prunes.read_text()):(target/rel).unlink(missing_ok=True)
''')
  self.render();self.legacy.build_pair();self.clear()
 def tearDown(self):self.legacy.tearDown()
 def owner(self,state,component):return self.root/'specs'/state/'generation/runtime-overrides'/component
 def render(self,state='YU18-risk-integration'):
  p=self.call('bash',str(self.root/'pipeline/generate-state.sh'),state);self.assertEqual(0,p.returncode,p.stdout+p.stderr)
 def call(self,*args):return subprocess.run(args,env=self.env,cwd=self.root,capture_output=True,text=True,timeout=40)
 def clear(self):(self.store/'trace.jsonl').write_text('')
 def trace(self):return [json.loads(x) for x in (self.store/'trace.jsonl').read_text().splitlines()]
 def build(self,name='build-cluster-image.sh',accepted=True,reason='does not derive',args=()):
  self.clear();p=self.call('bash',str(self.root/'scripts/yu15'/name),*args)
  self.assertEqual(0 if accepted else 1,p.returncode,p.stdout+p.stderr)
  effects=[x for x in self.trace() if x[0] in ('gradlew','docker')]
  if accepted:
   self.assertIn('current full-ancestry derivation',p.stdout);self.assertTrue(any(x[0]=='gradlew' for x in effects),effects)
   self.assertTrue(any(x[:2]==['docker','build'] for x in effects),effects)
  else:
   self.assertIn(reason,p.stdout+p.stderr);self.assertEqual([],effects,'refusal must precede compilation and all Docker activity')
  return p
 def test_fresh_current_all_three_entrypoints(self):
  for name in ('build-cluster-image.sh','build-service-images.sh','build-stp-boundary-images.sh'):
   with self.subTest(name=name):self.build(name)
 def test_unchanged_cache_outputs_and_mtimes_do_not_change_derivation(self):
  before=P.tree_hash(self.legacy.om)
  self.build();(self.legacy.om/'build').mkdir(exist_ok=True);(self.legacy.om/'build/old.jar').write_text('ignored prior output')
  (self.legacy.om/'.gradle').mkdir();(self.legacy.om/'.gradle/noise').write_text('cached')
  for path in self.legacy.om.rglob('*'):os.utime(path,(1900000000,1900000000))
  self.assertEqual(before,P.tree_hash(self.legacy.om));self.build();self.build('build-stp-boundary-images.sh')
 def test_leaf_owner_change_rebuild_without_render_refuses(self):
  path=self.owner('YU18-risk-integration','order-matcher')/'src/main/java'/RI18.GW;path.parent.mkdir(parents=True)
  path.write_bytes((self.legacy.om/'src/main/java'/RI18.GW).read_bytes()+b'// newly accepted but unrendered\n')
  self.build(accepted=False);self.build('build-stp-boundary-images.sh',False)
 def test_ancestor_change_refuses_despite_leaf_parity(self):
  (self.owner('YU17-otc-rates','order-matcher')/'ancestor.txt').write_text('unrendered ancestor change\n')
  self.build(accepted=False);self.build('build-stp-boundary-images.sh',False)
 def test_owner_addition_and_removal_refuse(self):
  path=self.owner('YU18-risk-integration','order-matcher')/'leaf-resource.txt';path.write_text('added')
  self.build(accepted=False);self.render();path.unlink();self.build(accepted=False)
 def test_template_change_refuses(self):
  (self.root/'templates/order-matcher/build.gradle').write_text('new dependency declaration')
  self.build(accepted=False)
 def test_deleted_ancestor_input_refuses_stale_existing_render(self):
  (self.owner('YU17-otc-rates','order-matcher')/'ancestor.txt').unlink()
  self.build(accepted=False);self.build('build-stp-boundary-images.sh',False)
 def test_pipeline_output_change_requires_full_render(self):
  path=self.root/'pipeline/fixture-generate.py'
  path.write_text(path.read_text()+"\n(target/'order-matcher/new-pipeline-resource.txt').write_text('new pipeline output')\n")
  self.build(accepted=False)
 def test_state_pruning_refuses_unpruned_context_then_admits(self):
  path=self.root/'specs/YU18-risk-integration/generation/prune.json';path.write_text(json.dumps(['order-matcher/ancestor.txt']))
  self.build(accepted=False);self.render();self.build()
 def test_current_dirty_owner_then_full_render_passes(self):
  (self.owner('YU17-otc-rates','order-matcher')/'ancestor.txt').write_text('dirty current ancestor')
  self.render();self.build();self.build('build-stp-boundary-images.sh')
 def test_missing_and_deleted_generated_inputs_refuse(self):
  (self.legacy.om/'build.gradle').unlink();self.build(accepted=False);self.build('build-stp-boundary-images.sh',False)
 def test_missing_whole_context_refuses(self):
  shutil.rmtree(self.legacy.om);self.build(accepted=False,reason='missing generated');self.build('build-stp-boundary-images.sh',False,'missing')
 def test_service_all_selected_contexts_admit_before_first_build(self):
  path=self.owner('YU18-risk-integration','position-service')/'resource.txt';path.parent.mkdir(parents=True);path.write_text('new resource')
  self.build('build-service-images.sh',False,args=('trade-processor','position-service'))
 def test_generated_extra_input_refuses(self):
  (self.legacy.om/'injected.properties').write_text('unowned')
  self.build(accepted=False)
 def test_untrusted_om_dir_without_explicit_role_refuses(self):
  copy=self.legacy.base/'untrusted';shutil.copytree(self.legacy.om,copy);self.env['OM_DIR']=str(copy)
  self.build(accepted=False,reason='requires explicit OM_ROLE')
 def test_validated_fix_and_stp_pre_om_overrides_pass(self):
  for role in ('fix','pre'):
   context=self.legacy.base/role;shutil.copytree(self.legacy.om,context)
   if role=='pre':P.transform(self.root,context)
   self.env.update(OM_DIR=str(context),OM_ROLE=role);self.build()
 def test_unknown_custom_pre_transform_refuses(self):
  context=self.legacy.base/'pre';shutil.copytree(self.legacy.om,context);P.transform(self.root,context)
  (context/'arbitrary.txt').write_text('unknown transform');self.env.update(OM_DIR=str(context),OM_ROLE='pre')
  self.build(accepted=False)
 def test_forged_private_context_env_cannot_bypass_derivation(self):
  self.env['DERIVED_BUILD_ROOT']=str(self.legacy.om.parent)
  self.build()
  self.assertNotEqual(str(self.legacy.om),(self.store/'generic-built').read_text())
 def test_stale_owner_with_preserved_mtime_refuses(self):
  path=self.owner('YU17-otc-rates','order-matcher')/'ancestor.txt';stat=path.stat()
  path.write_text('changed bytes, identical timestamp');os.utime(path,ns=(stat.st_atime_ns,stat.st_mtime_ns))
  self.build(accepted=False)
 def test_forged_prepared_manifest_cannot_bypass_stp_build(self):
  work=self.legacy.base/'forged';work.mkdir();shutil.copytree(self.legacy.om,work/'fix')
  (work/'fix/injected.txt').write_text('caller injection')
  label={'schema':P.SCHEMA,'selected':P.selected(self.root),'roles':{'fix':P.tree_hash(work/'fix')},'sourceRevision':'a'*40}
  (work/'prepared.json').write_text(json.dumps(label));self.clear()
  p=self.call('python3',str(self.root/'scripts/yu15/stp-image-provenance.py'),'build','--root',str(self.root),'--work',str(work),'--role','fix','--image','forged:fix')
  self.assertEqual(1,p.returncode,p.stdout+p.stderr);self.assertIn('does not derive',p.stderr)
  self.assertFalse(any(x[0] in ('gradlew','docker') for x in self.trace()))
 def test_prepared_manifest_cannot_forge_recorded_git_revision(self):
  work=self.legacy.base/'prepared';work.mkdir();shutil.copytree(self.legacy.om,work/'fix')
  label={'schema':P.SCHEMA,'selected':P.selected(self.root),'roles':{'fix':P.tree_hash(work/'fix')},'sourceRevision':'0'*40}
  (work/'prepared.json').write_text(json.dumps(label));self.clear()
  p=self.call('python3',str(self.root/'scripts/yu15/stp-image-provenance.py'),'build','--root',str(self.root),'--work',str(work),'--role','fix','--image','derived:fix')
  self.assertEqual(0,p.returncode,p.stdout+p.stderr)
  images=json.loads((self.store/'images.json').read_text());provenance=json.loads(images['derived:fix']['Config']['Labels'][P.LABEL])
  self.assertEqual('a0d6da0bfbef481f301bb1172b6810cb8a4c9e3e',provenance['sourceRevision'])
 def test_empty_generated_component_refuses(self):
  (self.root/'templates/empty-service').mkdir();self.render()
  self.build('build-service-images.sh',False,'no build inputs',args=('empty-service',))
 def test_intended_ancestor_build_state_is_supported(self):
  self.env['BUILD_STATE']='YU17-otc-rates';self.render('YU17-otc-rates');self.build()
 def test_source_and_checkout_render_unchanged_after_builds(self):
  from hashlib import sha256
  before=(self.legacy.om/'src/main/java'/RI18.GW).read_bytes();self.build();self.assertEqual(before,(self.legacy.om/'src/main/java'/RI18.GW).read_bytes())
  self.assertFalse((self.legacy.om/'build').exists())
  path=Path((self.store/'generic-built').read_text());self.assertFalse(path.exists(),'owned build context leaked')
 def test_original_guard_omission_control_exposes_unsafe_acceptance(self):
  original=subprocess.check_output(['git','show','9528d8ff:scripts/yu15/build-cluster-image.sh'],cwd=ROOT,text=True)
  (self.root/'scripts/yu15/build-cluster-image.sh').write_text(original)
  (self.owner('YU17-otc-rates','order-matcher')/'ancestor.txt').write_text('missing accepted ancestor change')
  self.clear();p=self.call('bash',str(self.root/'scripts/yu15/build-cluster-image.sh'))
  self.assertEqual(0,p.returncode,p.stdout+p.stderr)
  self.assertTrue(any(x[0]=='gradlew' for x in self.trace()));self.assertTrue(any(x[:2]==['docker','build'] for x in self.trace()))
  self.assertNotEqual((self.owner('YU17-otc-rates','order-matcher')/'ancestor.txt').read_bytes(),(self.legacy.om/'ancestor.txt').read_bytes())

if __name__=='__main__':unittest.main(verbosity=2)
