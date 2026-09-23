import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import bundle
import instrument_terms
import verify_golden

FIXTURES=Path(__file__).parent/'fixtures'


class CompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.source=FIXTURES/'compatibility/note-structured-basis'
        self.manifest,self.parsed=bundle.validate(self.source)
        self.doc=json.loads((self.source/'instrument-terms.json').read_bytes())

    def validate(self,doc):
        return instrument_terms.validate(bundle.encoded(doc),self.parsed,self.manifest['clusterEpoch'],'synthetic')

    def test_structured_basis_pinned_and_old_terms_still_valid(self):
        self.assertEqual(self.manifest['artifacts']['instrumentTerms']['schema'],instrument_terms.SCHEMA_V2)
        old,_=bundle.validate(FIXTURES/'shared/note/v2')
        self.assertEqual(old['artifacts']['instrumentTerms']['schema'],instrument_terms.SCHEMA)
        self.assertNotEqual(old['bundleId'],self.manifest['bundleId'])
        self.assertEqual(self.doc['entries'][0]['provenance']['origin'],'synthetic')

    def test_basis_required_for_v2_security(self):
        del self.doc['entries'][0]['accrualBasis']
        with self.assertRaises(ValueError):self.validate(self.doc)

    def test_v1_rejects_unversioned_extension(self):
        self.doc['schema']=instrument_terms.SCHEMA
        with self.assertRaises(ValueError):self.validate(self.doc)

    def test_wrong_date_rounding_or_mode_rejected(self):
        for field,value in [('valuationDate','2025-06-03'),('fractionDecimals',5),('rounding','HALF_UP'),('dateBasis','SETTLEMENT_DATE')]:
            doc=copy.deepcopy(self.doc);doc['entries'][0]['accrualBasis'][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):self.validate(doc)

    def test_basis_must_agree_with_reference_terms(self):
        self.doc['entries'][0]['terms']['settlementDays']=1
        with self.assertRaisesRegex(ValueError,'conflicts'):self.validate(self.doc)

    def test_negative_fixture_fails_before_publication(self):
        p=FIXTURES/'compatibility/note-missing-accrual'
        expected=json.loads((p/'expected.json').read_bytes())
        with self.assertRaisesRegex(ValueError,expected['errorContains']):
            bundle.build(p/'positions.csv',p/'contracts.csv',self.root/'out',self.manifest['clusterEpoch'],
                         self.manifest['valuationTime'],'synthetic',p/'instrument-terms.json')
        self.assertFalse((self.root/'out').exists())
        self.assertFalse((p/'manifest.json').exists())

    def test_negative_fixture_changes_only_accrual_cells(self):
        original=(FIXTURES/'shared/note/v2/positions.csv').read_bytes()
        negative=(FIXTURES/'compatibility/note-missing-accrual/positions.csv').read_bytes()
        self.assertEqual(original.count(b',0.018571\n'),2)
        self.assertEqual(negative,original.replace(b',0.018571\n',b',\n'))

    def test_crlf_diagnostic_names_file_without_normalizing(self):
        target=self.root/'golden';shutil.copytree(FIXTURES/'golden-v1',target)
        p=target/'basic/positions.csv';p.write_bytes(p.read_bytes().replace(b'\n',b'\r\n'));before=p.read_bytes()
        with self.assertRaisesRegex(ValueError,'CRLF line endings.*positions.csv'):verify_golden.verify(target)
        self.assertEqual(before,p.read_bytes())

    def test_assumed_not_a_reference_origin(self):
        self.doc['entries'][0]['provenance']['origin']='assumed'
        with self.assertRaisesRegex(ValueError,'provenance'):self.validate(self.doc)


if __name__=='__main__':unittest.main()
