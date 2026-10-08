import copy
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
FIXTURES = HERE / 'fixtures'
ROOT = HERE.parents[2]
CLI = HERE / 'coverage.py'
PUBLISHER = Path(os.environ.get('TEST_PUBLISHER_ROOT', str(ROOT / 'specs/YU16-cdm-instruments/generation/runtime-overrides/price-publisher')))


def encode_sample(metadata):
    # Explicit TAQP1 layout, independent from the actual JS decoder and set calculations.
    symbols, days = metadata['symbols'], metadata['days']
    header = b'TAQP1' + struct.pack('<HHIHHI', metadata['slots'], metadata['windowSeconds'],
        metadata['sessionSeconds'], len(days), len(symbols), metadata['scale'])
    header += b''.join(day.encode('latin1') for day in days)
    header += b''.join(bytes([len(s)]) + s.encode('latin1') for s in symbols)
    count = len(symbols) * len(days) * (metadata['sessionSeconds']//metadata['windowSeconds']) * metadata['slots']
    plane = metadata.get('pricePlane', [200000] * count)
    assert len(plane) == count
    return header + b''.join(struct.pack('<i', px) for px in plane)


class CoverageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='universe-test-')
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.tape = json.loads((FIXTURES/'tape.json').read_text())
        self.sample = json.loads((FIXTURES/'print.json').read_text())
        self.expected = json.loads((FIXTURES/'inventory.json').read_text())
        self.config = self.dir/'config.json'
        shutil.copyfile(FIXTURES/'declaration.json', self.config)
        self.extract = self.dir/'extract.gz'
        self.extract.write_bytes(gzip.compress(json.dumps(self.tape).encode(), mtime=0))
        self.prints = self.dir/'prints.gz'
        self.prints.write_bytes(gzip.compress(encode_sample(self.sample), mtime=0))
        self.csv = self.dir/'reference.csv'
        shutil.copyfile(FIXTURES/'reference.csv',self.csv)

    def run_cli(self, *extra, declaration=True, tape=True, env=None, expect=0):
        args = [sys.executable,str(CLI),'--publisher-root',str(PUBLISHER)]
        if declaration:
            args += ['--declaration-config',str(self.config)]
        if tape:
            args += ['--tape-extract',str(self.extract)]
        args += list(extra)
        merged = dict(os.environ)
        if env:
            merged.update(env)
        p = subprocess.run(args,text=True,capture_output=True,timeout=30,env=merged)
        self.assertEqual(p.returncode,expect,p.stdout+p.stderr)
        summary = json.loads(p.stdout)
        output = Path(summary['privateOutput'])
        self.addCleanup(output.unlink,missing_ok=True)
        self.assertEqual(stat.S_IMODE(output.stat().st_mode),0o600)
        self.assertNotIn('BRK/A',p.stdout)
        return json.loads(output.read_text()),output.read_bytes(),summary

    def check_inventory(self,report):
        core = report['relations']['declaredTape']
        for key in ('declared','tapeAvailable','overlap','declaredWithoutTape','tapeExcludedByDeclaration'):
            self.assertEqual(core[key], self.expected[key],key)
        self.assertGreater(len(core['overlap']),0)
        self.assertGreater(len(core['tapeExcludedByDeclaration']),0)
        self.assertEqual(report['relations']['referenceDirectory'],self.expected['directory'])
        for key,value in self.expected['print'].items():
            self.assertEqual(report['relations']['printSample'][key],value,key)

    def test_independent_nonempty_inventory_and_actual_readers(self):
        report,_,_=self.run_cli('--reference-csv',str(self.csv),'--print-sample',str(self.prints))
        self.check_inventory(report)
        self.assertTrue(report['relations']['printSample']['runtimeArtifactAlignment'])
        self.assertEqual(report['sources']['declaration']['rawTokens'][0],' AAA ')
        self.assertEqual(report['sources']['declaration']['reader']['sha256'],hashlib.sha256((PUBLISHER/'src/main.js').read_bytes()).hexdigest())
        self.assertIn('FB',report['sources']['referenceDirectory']['symbols'])
        self.assertNotIn('META',report['sources']['referenceDirectory']['symbols'])

    def test_deterministic_bytes_and_exact_input_provenance(self):
        first,a,_=self.run_cli()
        _,b,_=self.run_cli()
        self.assertEqual(a,b)
        self.assertEqual(first['sources']['tape']['sha256'],hashlib.sha256(self.extract.read_bytes()).hexdigest())
        self.assertEqual(first['sources']['tape']['decodedSha256'],hashlib.sha256(gzip.decompress(self.extract.read_bytes())).hexdigest())
        self.assertIsNone(first['relations']['referenceDirectory'])
        self.assertEqual(first['sources']['referenceDirectory']['state'],'not_supplied')
        self.assertIsNone(first['relations']['printSample'])

    def test_explicit_default_and_empty_string_follow_actual_declaration(self):
        report,_,_=self.run_cli('--declaration-default',declaration=False)
        role=report['sources']['declaration']
        self.assertTrue(role['defaultSelected'])
        self.assertIn('AAPL',role['symbols'])
        self.assertNotIn('AAA',role['symbols'])
        self.config.write_text('{"PRICE_TICKERS":""}')
        explicit,_,_=self.run_cli()
        self.assertEqual(explicit['sources']['declaration']['symbols'],role['symbols'])
        self.assertTrue(explicit['sources']['declaration']['defaultSelected'])
        self.config.write_text('{}')
        unset,_,_=self.run_cli()
        self.assertEqual(unset['sources']['declaration']['symbols'],role['symbols'])

    def test_no_inherited_shell_declaration_or_live_quotes(self):
        report,_,_=self.run_cli(env={'PRICE_TICKERS':'TAPE-ONLY','REPLAY_EPOCH_START_MS':'bad'})
        self.assertEqual(report['relations']['declaredTape']['declared'],self.expected['declared'])
        missing,_,_=self.run_cli(declaration=False,env={'PRICE_TICKERS':'AAA'},expect=2)
        self.assertEqual(missing['sources']['declaration']['state'],'not_supplied')
        self.assertIsNone(missing['relations']['declaredTape'])

    def test_missing_and_invalid_primary_inputs_do_not_become_empty_success(self):
        self.extract.unlink()
        r,_,_=self.run_cli(expect=2)
        self.assertEqual(r['sources']['tape']['state'],'unavailable')
        self.assertIsNone(r['relations']['declaredTape'])
        self.extract.write_bytes(b'not gzip')
        r,_,_=self.run_cli(expect=2)
        self.assertEqual(r['sources']['tape']['state'],'invalid')
        self.assertIsNone(r['relations']['declaredTape'])
        self.extract.write_bytes(gzip.compress(b'null',mtime=0))
        r,a,_=self.run_cli(expect=2)
        _,b,_=self.run_cli(expect=2)
        self.assertEqual(a,b,'invalid reader detail must not contain random snapshot paths')
        self.assertEqual(r['sources']['tape']['state'],'invalid')

    def test_empty_populations_refuse(self):
        self.config.write_text('{"PRICE_TICKERS":" ,  "}')
        r,_,_=self.run_cli(expect=2)
        self.assertEqual(r['sources']['declaration']['state'],'invalid')
        self.config.write_text('{"PRICE_TICKERS":"AAA"}')
        self.tape['prices']={}
        self.extract.write_bytes(gzip.compress(json.dumps(self.tape).encode(),mtime=0))
        r,_,_=self.run_cli(expect=2)
        self.assertIsNone(r['relations']['declaredTape'])
        self.assertEqual(r['sources']['tape']['state'],'invalid')

    def test_duplicate_declaration_and_normalization_collisions_refuse(self):
        for value in ['AAA,AAA','aaa,AAA']:
            self.config.write_text(json.dumps({'PRICE_TICKERS':value}))
            r,_,_=self.run_cli(expect=2)
            self.assertEqual(r['sources']['declaration']['duplicateIdentities'],['AAA'])
            self.assertIsNone(r['relations']['declaredTape'])

    def test_duplicate_json_keys_and_schema_refuse(self):
        for plain in [b'{"prices":{"AAA":[],"AAA":[]}}',b'{"x":NaN}',b'{',b'{}']:
            self.extract.write_bytes(gzip.compress(plain,mtime=0))
            r,_,_=self.run_cli(expect=2)
            self.assertEqual(r['sources']['tape']['state'],'invalid')
            self.assertIsNone(r['relations']['declaredTape'])
        self.config.write_text('{"PRICE_TICKERS":"AAA","PRICE_TICKERS":"BBB"}')
        r,_,_=self.run_cli(expect=2)
        self.assertEqual(r['sources']['declaration']['state'],'invalid')

    def test_declaration_config_null_and_extra_keys_refuse(self):
        for obj in [{'PRICE_TICKERS':None}, {'PRICE_TICKERS':['AAA']}, {'OTHER':'AAA'}]:
            self.config.write_text(json.dumps(obj))
            r,_,_=self.run_cli(expect=2)
            self.assertEqual(r['sources']['declaration']['state'],'invalid')

    def test_reference_missing_invalid_duplicates_and_explicit_column(self):
        for content in ['ticker,name\nAAA,A\nAAA,B\n','wrong,name\nAAA,A\n','ticker,name\n AAA,A\n','ticker,name\n']:
            self.csv.write_text(content)
            r,_,_=self.run_cli('--reference-csv',str(self.csv),expect=2)
            self.assertEqual(r['sources']['referenceDirectory']['state'],'invalid')
            self.assertIsNone(r['relations']['referenceDirectory'])
            self.assertIsNotNone(r['relations']['declaredTape'])
        r,_,_=self.run_cli('--reference-csv',str(self.dir/'absent.csv'),expect=2)
        self.assertEqual(r['sources']['referenceDirectory']['state'],'unavailable')
        self.csv.write_text('instrumentKey,name\nAAA,"Synthetic, quoted"\nBRK/A,B\n')
        r,_,_=self.run_cli('--reference-csv',str(self.csv),'--reference-column','instrumentKey')
        self.assertEqual(r['sources']['referenceDirectory']['symbols'],['AAA','BRK/A'])

    def test_print_decoder_refusals_duplicates_and_artifact_alignment(self):
        self.prints.write_bytes(gzip.compress(b'bad binary',mtime=0))
        r,_,_=self.run_cli('--print-sample',str(self.prints),expect=2)
        self.assertEqual(r['sources']['printSample']['state'],'invalid')
        self.sample['symbols']=['AAA','AAA','TAPE-ONLY']
        self.prints.write_bytes(gzip.compress(encode_sample(self.sample),mtime=0))
        r,_,_=self.run_cli('--print-sample',str(self.prints),expect=2)
        self.assertEqual(r['sources']['printSample']['duplicateIdentities'],['AAA'])
        self.sample['symbols']=['AAA','BRK/A','NOT-TAPE']
        self.sample['days']=['2025-02-04']
        self.prints.write_bytes(gzip.compress(encode_sample(self.sample),mtime=0))
        r,_,_=self.run_cli('--print-sample',str(self.prints))
        pair=r['relations']['printSample']
        self.assertFalse(pair['runtimeArtifactAlignment'])
        self.assertFalse(pair['clockCalendarAligned'])
        self.assertEqual(pair['sampleWithoutTape'],['NOT-TAPE'])

    def test_js_strict_clock_types_are_not_python_boolean_aliases(self):
        self.tape['windowSeconds']=True
        self.tape['sessionSeconds']=1
        self.tape['prices']={s:[[200]] for s in self.tape['prices']}
        self.extract.write_bytes(gzip.compress(json.dumps(self.tape).encode(),mtime=0))
        self.sample.update(windowSeconds=1,sessionSeconds=1)
        self.prints.write_bytes(gzip.compress(encode_sample(self.sample),mtime=0))
        r,_,_=self.run_cli('--print-sample',str(self.prints))
        self.assertFalse(r['relations']['printSample']['clockCalendarAligned'])

    def test_budgets_refuse_and_nonregular_input_is_bounded(self):
        r,_,_=self.run_cli('--max-input-bytes','64',expect=2)
        self.assertIsNone(r['relations']['declaredTape'])
        r,_,_=self.run_cli('--max-identities','2',expect=2)
        self.assertIsNone(r['relations']['declaredTape'])
        fifo=self.dir/'fifo'
        os.mkfifo(fifo)
        r,_,_=self.run_cli('--tape-extract',str(fifo),tape=False,expect=2)
        self.assertEqual(r['sources']['tape']['state'],'invalid')

    def test_decoded_budget_corrupt_gzip_and_missing_config_refuse(self):
        self.tape['syntheticPadding'] = 'x' * 20000
        self.extract.write_bytes(gzip.compress(json.dumps(self.tape).encode(),mtime=0))
        self.assertLess(self.extract.stat().st_size,8192)
        r,_,_=self.run_cli('--max-input-bytes','8192',expect=2)
        self.assertEqual(r['sources']['tape']['state'],'invalid')
        self.assertIn('decompressed',r['sources']['tape']['reason'])
        raw=bytearray(gzip.compress(b'{}',mtime=0));raw[10]=255
        self.extract.write_bytes(raw)
        r,_,_=self.run_cli(expect=2)
        self.assertEqual(r['sources']['tape']['state'],'invalid')
        self.config.unlink()
        r,_,_=self.run_cli(expect=2)
        self.assertEqual(r['sources']['declaration']['state'],'unavailable')

    def test_excessively_nested_json_refuses_without_unhandled_exception(self):
        nested=b'['*2000+b'0'+b']'*2000
        self.extract.write_bytes(gzip.compress(nested,mtime=0))
        r,_,_=self.run_cli(expect=2)
        self.assertEqual(r['sources']['tape']['state'],'invalid')
        self.config.write_bytes(nested)
        r,_,_=self.run_cli(expect=2)
        self.assertEqual(r['sources']['declaration']['state'],'invalid')
        self.assertIsNone(r['relations']['declaredTape'])

    def test_output_never_overwrites_or_writes_inside_git(self):
        inside=ROOT/'must-not-create-coverage.json'
        p=subprocess.run([sys.executable,str(CLI),'--declaration-default','--output',str(inside)],capture_output=True,text=True,timeout=30)
        self.assertEqual(p.returncode,2)
        self.assertFalse(inside.exists())
        existing=self.dir/'existing.json';existing.write_text('preserve')
        p=subprocess.run([sys.executable,str(CLI),'--output',str(existing)],capture_output=True,text=True,timeout=30)
        self.assertEqual(p.returncode,2)
        self.assertEqual(existing.read_text(),'preserve')
        p=subprocess.run([sys.executable,str(CLI),'--max-output-bytes','1'],capture_output=True,text=True,timeout=30)
        self.assertEqual(p.returncode,2)
        self.assertEqual(p.stdout,'')

    def test_unsupported_reader_source_refuses(self):
        root=self.dir/'publisher'
        shutil.copytree(PUBLISHER,root)
        with (root/'src/main.js').open('a') as stream:
            stream.write('\n// unsupported change\n')
        r,_,_=self.run_cli('--publisher-root',str(root),expect=2)
        self.assertEqual(r['sources']['declaration']['state'],'invalid')
        self.assertIsNone(r['relations']['declaredTape'])

    def test_independent_inventory_rejects_omitted_and_miscalculated_sets(self):
        r,_,_=self.run_cli('--reference-csv',str(self.csv),'--print-sample',str(self.prints))
        for mutate in [lambda x:x['relations']['declaredTape'].pop('tapeExcludedByDeclaration'),
                       lambda x:x['relations']['declaredTape'].__setitem__('overlap',self.expected['tapeAvailable'])]:
            bad=copy.deepcopy(r);mutate(bad)
            with self.assertRaises((AssertionError,KeyError)):
                self.check_inventory(bad)


if __name__ == '__main__':
    unittest.main()
