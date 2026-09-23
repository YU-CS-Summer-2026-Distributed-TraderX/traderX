import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle
import market_inputs as m

FIXTURE = Path(__file__).parent/'fixtures/market-inputs'


class MarketInputsTest(unittest.TestCase):
    def setUp(self):
        self.metadata = json.loads((FIXTURE/'metadata.json').read_bytes())
        self.doc = json.loads((FIXTURE/'observations.json').read_bytes())

    def report(self):
        data = bundle.encoded(self.doc)
        return m.suitability(m.manifest_for(self.metadata, data), data)

    def test_golden_and_byte_preservation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)/'package'
            manifest, report = m.build(FIXTURE/'metadata.json', FIXTURE/'observations.json', root)
            self.assertTrue(report['suitableForSelection'])
            self.assertFalse(report['usableForRisk'])
            self.assertEqual(manifest['packageId'], (FIXTURE/'expected-package-id.txt').read_text().strip())
            self.assertEqual((root/'observations.json').read_bytes(), (FIXTURE/'observations.json').read_bytes())
            self.assertEqual(m.validate(root), (manifest,report))
            with self.assertRaises(ValueError):
                m.build(FIXTURE/'metadata.json', FIXTURE/'observations.json', root)
            (root/'observations.json').write_bytes((root/'observations.json').read_bytes()+b' ')
            with self.assertRaisesRegex(ValueError, 'hash'):
                m.validate(root)

    def test_raw_bytes_and_metadata_change_identity(self):
        data = bundle.encoded(self.doc)
        original = m.manifest_for(self.metadata,data)['packageId']
        self.assertNotEqual(original,m.manifest_for(self.metadata,data+b' ')['packageId'])
        self.metadata['selection']['maxAgeCalendarDays'] = 1
        self.assertNotEqual(original,m.manifest_for(self.metadata,data)['packageId'])

    def test_duplicate_and_invalid_fields(self):
        for field,value in [('units','basis-points'),('quoteType','zero-curve'),('provenance','real'),('value',4.2),('value','NaN'),('retrievalTime','2025-06-02T14:00:00')]:
            with self.subTest(field=field,value=value):
                original=copy.deepcopy(self.doc)
                self.doc['observations'][0][field]=value
                with self.assertRaises(ValueError): self.report()
                self.doc=original
        self.doc['observations'].append(copy.deepcopy(self.doc['observations'][0]))
        with self.assertRaisesRegex(ValueError,'duplicate'): self.report()

    def test_duplicate_json_keys(self):
        with self.assertRaisesRegex(ValueError,'duplicate'):
            m.observations(b'{"schema":"x","schema":"x"}')

    def test_missing_required(self):
        self.doc['observations'].pop()
        result=self.report()
        self.assertTrue(result['structurallyValid'])
        self.assertFalse(result['suitableForSelection'])
        self.assertIn('MISSING_REQUIRED',result['issues'][0]['reasons'])

    def test_unknown_publication_never_inferred(self):
        self.doc['observations'][0]['publicationTime']=None
        self.assertIn('PUBLICATION_UNKNOWN', self.report()['issues'][0]['reasons'])

    def test_late_times(self):
        for field in ('observationTime','publicationTime','retrievalTime'):
            with self.subTest(field=field):
                self.setUp()
                row=self.doc['observations'][0]
                for name in ('observationTime','publicationTime','retrievalTime')[('observationTime','publicationTime','retrievalTime').index(field):]:
                    row[name]='2025-06-02T21:00:00Z'
                self.assertIn(field.upper()+'_AFTER_CUTOFF',self.report()['issues'][0]['reasons'])

    def test_future_stale_and_no_synthetic_substitution(self):
        self.metadata['selection']['allowedProvenance']=['observed']
        self.assertIn('PROVENANCE_NOT_ALLOWED',self.report()['issues'][0]['reasons'])
        for d,reason in [('2025-06-03','FUTURE_OBSERVATION'),('2025-06-01','STALE_OBSERVATION')]:
            self.setUp()
            for refs in (self.metadata['selection']['selected'],self.metadata['selection']['required']):
                refs[0]['observationDate']=d
            self.doc['observations'][0]['identity']['observationDate']=d
            self.doc['observations'][0]['observationTime']=None
            self.assertIn(reason,self.report()['issues'][0]['reasons'])

    def test_selection_and_time_structure(self):
        for mutate in [lambda p:p.update(required=[]),lambda p:p.update(maxAgeCalendarDays=True),lambda p:p['selected'].append(p['selected'][0]),lambda p:p.update(allowedProvenance=[])]:
            self.setUp();mutate(self.metadata['selection'])
            with self.assertRaises(ValueError):self.report()
        self.setUp();self.metadata['availabilityCutoff']='2025-06-02T21:00:00Z'
        with self.assertRaises(ValueError):self.report()
        self.setUp();self.doc['observations'][0]['publicationTime']='2025-06-03T00:00:00Z'
        with self.assertRaises(ValueError):self.report()

    def test_symlink_and_extra_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'package'
            m.build(FIXTURE/'metadata.json',FIXTURE/'observations.json',root)
            (root/'extra').write_text('x')
            with self.assertRaises(ValueError):m.validate(root)
            (root/'extra').unlink()
            (root/'observations.json').unlink()
            (root/'observations.json').symlink_to((FIXTURE/'observations.json').resolve())
            with self.assertRaises(ValueError):m.validate(root)
