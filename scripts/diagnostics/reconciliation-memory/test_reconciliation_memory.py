"""Actual bounded orphan-sweep profiles and retained-view/copy discriminator controls."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import reconciliation_memory as tool

EVIDENCE=os.environ.get("RECON_MEMORY_TEST_EVIDENCE")

def save(name,report):
    if EVIDENCE:
        p=Path(EVIDENCE);p.mkdir(parents=True,exist_ok=True);(p/(name+'.json')).write_text(json.dumps(report,sort_keys=True,indent=2)+'\n')

def sample(name,*args):
    report=tool.measure(tool.parser().parse_args(args));save(name,report);return report

class ReconciliationMemoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.empty=sample('empty','--history','0','--orphans','0')
        cls.clean=sample('no-orphans','--history','128','--orphans','0')
        cls.small=sample('small','--history','128','--orphans','20')
        cls.capped=sample('capped700','--history','256','--orphans','700')
        cls.larger=sample('capped1200','--history','512','--orphans','1200')
        cls.repeated=sample('repeated-local','--history','64','--orphans','300','--local-repeat','2')

    def test_actual_empty_and_nonempty_method_outputs(self):
        self.assertEqual(self.empty['actual_result']['orphan_count'],0)
        self.assertEqual(self.clean['actual_result']['local_trade_count'],128)
        self.assertEqual(self.clean['actual_result']['orphan_count'],0)
        self.assertEqual(self.small['actual_result']['reported_ids'],[f'O{i:06d}' for i in range(20)])
        self.assertTrue(all(p['actual_result']['last_result_same_identity'] for p in (self.empty,self.clean,self.small,self.capped,self.larger)))

    def test_actual_http_reindex_and_full_pagination(self):
        self.assertEqual(self.capped['http_witness'],{'page_gets':5,'reindex_posts':1,'repository_reads':1,'served_history_rows':512})
        self.assertEqual(self.empty['http_witness']['page_gets'],1)
        self.assertEqual(self.larger['http_witness']['served_history_rows'],1024)

    def test_repeated_ids_not_confused_with_unique_history(self):
        self.assertEqual(self.capped['temporary']['history_unique_ids'],256)
        self.assertEqual(self.capped['http_witness']['served_history_rows'],512)
        self.assertEqual(self.repeated['actual_result']['orphan_count'],600)
        self.assertEqual(self.repeated['temporary']['orphan_unique_ids'],300)
        self.assertEqual(self.repeated['last_result_selected_graph']['string_objects'],250)
        self.assertEqual(self.repeated['actual_backing']['slots_beyond_visible_view'],0)

    def test_two_bounded_history_sizes_show_actual_storage_growth(self):
        a=self.capped['temporary']['history_selected_graph'];b=self.larger['temporary']['history_selected_graph']
        self.assertEqual((a['string_objects'],b['string_objects']),(256,512))
        self.assertGreater(b['selected_shallow_bytes'],a['selected_shallow_bytes'])
        self.assertGreater(b['array_payload_bytes'],a['array_payload_bytes'])

    def test_actual_current_result_bounds_nonvisible_string_objects(self):
        for p,n in ((self.capped,700),(self.larger,1200)):
            with self.subTest(n=n):
                self.assertEqual(len(p['actual_result']['reported_ids']),500)
                self.assertEqual(p['actual_backing']['view_class'],'java.util.ArrayList')
                self.assertEqual(p['actual_backing']['root_list_size'],500)
                self.assertEqual(p['actual_backing']['slots_beyond_visible_view'],0)
                self.assertEqual(p['last_result_selected_graph']['string_objects'],500)
                self.assertNotIn('java.util.ArrayList$SubList',p['last_result_selected_graph']['classes'])
                self.assertEqual(p['bounded_copy_selected_graph']['string_objects'],500)
                self.assertEqual(p['bounded_copy_backing']['slots_beyond_visible_view'],0)
                self.assertEqual(p['last_result_selected_graph']['selected_shallow_bytes'],p['bounded_copy_selected_graph']['selected_shallow_bytes'])

    def test_temporary_and_result_roots_deduplicate_shared_ids(self):
        t=self.small['temporary']
        self.assertLess(t['temporary_selected_union']['selected_shallow_bytes'],sum(t[k]['selected_shallow_bytes'] for k in ('history_selected_graph','local_ids_selected_graph','all_orphans_selected_graph')))
        self.assertGreater(t['temporary_selected_union']['aliases_skipped'],0)
        self.assertEqual(t['all_orphans_selected_graph']['string_objects'],20)

    def test_below_cap_and_zero_controls_are_arraylists(self):
        for p in (self.empty,self.clean,self.small):
            self.assertEqual(p['actual_backing']['view_class'],'java.util.ArrayList')
            self.assertEqual(p['actual_backing']['slots_beyond_visible_view'],0)
            self.assertEqual(p['actual_backing']['root_list_size'],p['actual_result']['orphan_count'])

    def test_source_pin_observer_is_reversible_and_whole_source(self):
        source=tool.sources(tool.ROOT)['service/ReconciliationService.java']['data']
        hooked=tool.instrument(source)
        self.assertEqual(tool.digest(source),tool.PIN)
        self.assertEqual(hooked.decode().replace(tool.HOOK,''),source.decode())
        self.assertEqual(self.capped['provenance']['instrumented_service_sha256'],tool.digest(hooked))
        self.assertIn('finos/traderx/tradeprocessor/service/ReconciliationService.class',self.capped['provenance']['class_sha256'])
        self.assertEqual(len(self.capped['provenance']['sources']),10)
        self.assertEqual(len(self.capped['provenance']['dependencies']),17)

    def test_pin_drift_refuses_before_actual_method(self):
        source=tool.sources(tool.ROOT)['service/ReconciliationService.java']['data']
        with self.assertRaisesRegex(ValueError,'SHA drift'):tool.instrument(source+b'\n')

    def test_copy_graph_negative_refuses_wrong_measurement_root(self):
        broken=copy.deepcopy(self.capped)
        broken['last_result_selected_graph']=broken['temporary']['all_orphans_selected_graph']
        with self.assertRaisesRegex(ValueError,'wrong current last-result'):tool.validate(broken)

    def test_real_original_view_source_control_detects_retention_regression(self):
        original_instrument=tool.instrument
        source_marker='new ArrayList<>(orphans.subList(0, MAX_REPORTED_ORPHANS)) : orphans'
        def copied(data):
            hooked=original_instrument(data).decode()
            self.assertEqual(hooked.count(source_marker),1)
            changed=hooked.replace(source_marker,'orphans.subList(0, MAX_REPORTED_ORPHANS) : orphans')
            if EVIDENCE:(Path(EVIDENCE)/'negative-original-view-source.java').write_text(changed)
            return changed.encode()
        original_validate=tool.validate
        def capture(report):save('negative-original-view-result',report);return original_validate(report)
        with patch.object(tool,'instrument',copied),patch.object(tool,'validate',capture):
            with self.assertRaisesRegex(ValueError,'bounded current result') as refused:
                tool.measure(tool.parser().parse_args(['--history','32','--orphans','700']))
        if EVIDENCE:(Path(EVIDENCE)/'negative-original-view-refusal.txt').write_text(str(refused.exception)+'\n')

    def test_missing_string_or_array_category_refused(self):
        for name in ('java.lang.String','[B'):
            broken=copy.deepcopy(self.capped);broken['last_result_selected_graph']['classes'].pop(name)
            with self.assertRaises(ValueError):tool.validate(broken)

    def test_unexecuted_or_zero_count_fake_report_refused(self):
        broken=copy.deepcopy(self.capped);broken['actual_method_executed']=False
        with self.assertRaises(ValueError):tool.validate(broken)
        broken=copy.deepcopy(self.capped);broken['http_witness']['reindex_posts']=0
        with self.assertRaises(ValueError):tool.validate(broken)

    def test_bounded_cleanup_jvm_and_graph_exclusions(self):
        p=self.capped
        self.assertEqual(p['vm']['flags']['MaxHeapSize'],str(128*1024*1024))
        self.assertTrue(all(p['unavailable'].values()))
        self.assertTrue(p['provenance']['temporary_artifacts_removed_on_exit'])
        arg=next(s for s in p['vm']['input_arguments'] if s.startswith('-javaagent:'))
        self.assertFalse(Path(arg.split(':',1)[1]).exists())

    def test_invalid_sizes_refuse_before_compilation(self):
        for args in (['--history','2001'],['--orphans','2001'],['--history','2000','--orphans','2000'],['--history-repeat','3'],['--page-size','4']):
            with self.subTest(args=args),patch.object(tool,'sources',side_effect=AssertionError('should not compile')):
                with self.assertRaises(ValueError):tool.measure(tool.parser().parse_args(args))

if __name__=='__main__':unittest.main(verbosity=2)
