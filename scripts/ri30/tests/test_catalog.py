import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import pyarrow as pa
import pyarrow.parquet as pq
from preserve import convert,PROVENANCE
from compact import compact
from verify_compact import verify
from catalog import build_catalog,select_files,query_paths
from catalog_batch import plan

class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        source=self.root/'taq_trades_mar2025_csv.zip'
        with zipfile.ZipFile(source,'w') as z:
            z.writestr('a.csv','DATE,SYM_ROOT,SYM_SUFFIX,PRICE,NOTE\n2025-03-03,BRK,A,001.00,"line\nnext"\n2025-03-03,BRK,A,001.00,"line\nnext"\n2025-03-04,ABC,,NaN,\n2025-02-30,ABC,,NULL,invalid\n,ABC,,0,empty\n')
            z.writestr('b.csv','DATE,SYM_ROOT,SYM_SUFFIX,PRICE,NOTE\n2025-03-03,ABC,,0001.00,member\n')
        self.commit=convert(source,self.root/'preserved',source_id='s'*64,chunk_rows=1,chunk_bytes=256)
        self.commit['source']=dict(source_id='s'*64,name=source.name,generation=1)
        self.inputs=[self.root/'preserved'/p['file'] for p in self.commit['parts']]
        self.report=compact(self.commit,self.inputs,self.root/'out',target_bytes=1024**2,row_group_rows=2)
        self.paths=[self.root/'out'/f['file'] for f in self.report['files']]
    def tearDown(self): self.tmp.cleanup()
    def audit(self): return verify(self.commit,self.inputs,self.report,self.paths)
    def publication(self):
        r=copy.deepcopy(self.report);r.update(status='complete',verification=self.audit())
        for i,f in enumerate(r['files']): f.update(bucket='private',object=f['file'],generation=i+1,crc32c='crc')
        return r
    def test_cross_part_and_exact_strings(self):
        self.assertEqual(self.audit()['rows'],6)
        self.assertEqual(len(self.report['files']),4)
        self.assertGreater(len(self.inputs),len(self.report['files']))
        invalid=[f for f in self.report['files'] if f['date']=='invalid']; self.assertEqual(sum(f['rows'] for f in invalid),2)
        q=query_paths(self.paths,'2025-03-03','BRK','A',20)
        self.assertEqual(q.num_rows,2);self.assertEqual(q['PRICE'].to_pylist(),['001.00']*2)
        self.assertEqual(q['NOTE'].to_pylist(),['line\nnext']*2)
    def mutate(self,fn):
        table=pq.ParquetFile(self.paths[0]).read();pq.write_table(fn(table),self.paths[0])
    def test_changed_field_rejected(self):
        self.mutate(lambda t:t.set_column(t.column_names.index('PRICE'),'PRICE',pa.array(['changed']*t.num_rows)))
        with self.assertRaisesRegex(ValueError,'exact'): self.audit()
    def test_same_count_duplicate_rejected(self):
        self.mutate(lambda t:pa.concat_tables([t.slice(0,1)]*t.num_rows))
        with self.assertRaises(ValueError):self.audit()
    def test_missing_row_rejected(self):
        self.mutate(lambda t:t.slice(0,t.num_rows-1))
        with self.assertRaises(ValueError):self.audit()
    def test_type_drift_rejected(self):
        self.mutate(lambda t:t.set_column(t.column_names.index('PRICE'),'PRICE',pa.array([1]*t.num_rows)))
        with self.assertRaises(ValueError):self.audit()
    def test_null_rejected(self):
        self.mutate(lambda t:t.set_column(t.column_names.index('PRICE'),'PRICE',pa.array([None]*t.num_rows,type=pa.string())))
        with self.assertRaises(ValueError):self.audit()
    def test_provenance_rejected(self):
        self.mutate(lambda t:t.set_column(t.column_names.index(PROVENANCE[2]),PROVENANCE[2],pa.array([999]*t.num_rows,type=pa.int64())))
        with self.assertRaises(ValueError):self.audit()
    def test_missing_and_duplicate_lineage(self):
        self.report['files'][0]['segments']=[]
        with self.assertRaises(ValueError):self.audit()
    def test_schema_column_missing(self):
        self.mutate(lambda t:t.drop(['NOTE']))
        with self.assertRaises(ValueError):self.audit()
    def test_wrong_partition(self):
        self.report['files'][0]['date']='2025-03-05'
        with self.assertRaises(ValueError):self.audit()
    def test_empty_population(self):
        with self.assertRaises(ValueError):verify(self.commit,[],self.report,[])
    def test_catalog_query_and_overlap(self):
        r=self.publication();other=copy.deepcopy(r);other['source_id']='other';other['source']['source_id']='other';other['archive']='taq_trades_20250303_csv'
        out=self.root/'catalog';c=build_catalog([r,other],[r['source'],other['source']],out)
        self.assertEqual(c['rows'],12);self.assertTrue(c['overlaps']);self.assertTrue((out/'browse.html').is_file())
        self.assertEqual(pq.ParquetFile(out/'catalog.parquet').metadata.num_rows,8)
        self.assertTrue(select_files(c,r['archive'],'2025-03-03','ABC',''))
        with self.assertRaises(ValueError):select_files(c,'','2025-03-03','ABC','')
        with self.assertRaises(ValueError):select_files(c,r['archive'],'2025-03-03','ABC',None)
    def test_incomplete_attempt_invisible(self):
        r=self.publication();r['status']='written'
        with self.assertRaises(ValueError):build_catalog([r],[r['source']],self.root/'catalog')
    def test_catalog_missing_source(self):
        r=self.publication()
        with self.assertRaises(ValueError):build_catalog([r],[r['source'],dict(source_id='missing')],self.root/'catalog')
    def test_interruption_restarts_without_overwrite(self):
        with self.assertRaises(FileExistsError):compact(self.commit,self.inputs,self.root/'out')
        r=compact(self.commit,self.inputs,self.root/'retry',target_bytes=1)
        self.assertEqual(verify(self.commit,self.inputs,r,[self.root/'retry'/f['file'] for f in r['files']])['rows'],6)
        self.assertGreater(len(r['files']),len(self.report['files']))
    def test_cloud_bound(self):
        ref=dict(sha256='a'*64,generation=1);image='private/image@sha256:'+'b'*64
        self.assertEqual(plan(image,ref,132,32,'full')['taskGroups'][0]['parallelism'],32)
        for n in [0,33]:
            with self.assertRaises(ValueError):plan(image,ref,132,n)
        with self.assertRaises(ValueError):plan('image:latest',ref,2,2)

if __name__=='__main__':unittest.main()

class StreamingVerifierTest(unittest.TestCase):
    def test_one_decode_per_multi_date_part_and_output_boundary(self):
        from unittest.mock import patch
        from collections import Counter
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source=root/'taq_trades_test_csv.csv'
            source.write_text('DATE,SYM_ROOT,SYM_SUFFIX,VALUE\n'+''.join(
                f'{day},BRK,A,000{i}\n' for i in range(40)
                for day in ['2025-03-03','2025-03-04','bad','']))
            c=convert(source,root/'input',source_id='stream',chunk_rows=7,chunk_bytes=1024**2)
            c['source']=dict(source_id='stream',name=source.name,generation=1)
            inputs=[root/'input'/p['file'] for p in c['parts']]
            r=compact(c,inputs,root/'out',target_bytes=1,row_group_rows=2)
            outputs=[root/'out'/f['file'] for f in r['files']]
            real=pq.ParquetFile;reads=Counter()
            class Counted:
                def __init__(self,path):self.path=str(path);self.reader=real(path)
                def __getattr__(self,name):return getattr(self.reader,name)
                def read(self,*args,**kwargs):reads[self.path]+=1;return self.reader.read(*args,**kwargs)
            with patch('verify_compact.pq.ParquetFile',Counted):result=verify(c,inputs,r,outputs)
            self.assertEqual(result['rows'],160)
            self.assertEqual(reads,Counter({str(p):1 for p in inputs}))
            self.assertGreater(len(outputs),len(inputs))

class InvalidDateQueryTest(unittest.TestCase):
    def test_invalid_and_empty_dates_remain_privately_queryable(self):
        fixture=CatalogTest();fixture.setUp()
        try:
            r=fixture.publication();c=build_catalog([r],[r['source']],fixture.root/'catalog-invalid')
            paths={f['file']:p for f,p in zip(fixture.report['files'],fixture.paths)}
            for day,price in [('2025-02-30','NULL'),('','0')]:
                chosen=select_files(c,r['archive'],day,'ABC','')
                self.assertTrue(chosen)
                q=query_paths([paths[f['file']] for f in chosen],day,'ABC','')
                self.assertEqual(q.num_rows,1);self.assertEqual(q['DATE'].to_pylist(),[day])
                self.assertEqual(q['PRICE'].to_pylist(),[price])
        finally:fixture.tearDown()
