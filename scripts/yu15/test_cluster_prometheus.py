import importlib.util
from pathlib import Path
import json
import unittest
spec=importlib.util.spec_from_file_location('renderer',Path(__file__).with_name('render-cluster-prometheus.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class ClusterPrometheus(unittest.TestCase):
 def services(self,names):
  return {'items':[{'metadata':{'name':n,'namespace':'audit'},'spec':{'selector':{'app':'cluster-gateway' if n in ('order-matcher','order-matcher-gw') else n}}} for n in names]}
 def jobs(self,names):
  return json.loads(m.render(self.services(names),'audit',3)['data']['prometheus.yml'])['scrape_configs']
 def test_both_aliases_scrape_once(self):
  jobs=self.jobs(['order-matcher','order-matcher-gw','trade-processor'])
  targets=[t for j in jobs for s in j['static_configs'] for t in s['targets']]
  self.assertEqual(sum(t.endswith(':18110') for t in targets),1)
  self.assertNotIn('otel-collector:13133',targets)
  self.assertFalse(any('blackbox' in t for t in targets))
  self.assertIn('trade-processor:18091',targets) # no endpoint health filtering
  self.assertFalse(any('account-service' in t for t in targets))
 def test_each_alias_and_member_namespace(self):
  for alias in ('order-matcher','order-matcher-gw'):
   jobs=self.jobs([alias]);self.assertEqual(jobs[-2]['static_configs'][0]['targets'],[alias+':18110'])
   self.assertEqual(len(jobs[-1]['static_configs'][0]['targets']),3)
   self.assertTrue(all('.audit.' in t for t in jobs[-1]['static_configs'][0]['targets']))
 def test_missing_gateway_refuses(self):
  with self.assertRaises(ValueError):self.jobs(['trade-processor'])
 def test_baseline_matcher_not_mistaken_for_gateway(self):
  s=self.services(['order-matcher']);s['items'][0]['spec']['selector']['app']='order-matcher'
  with self.assertRaises(ValueError):m.render(s,'audit',3)
if __name__=='__main__':unittest.main()
