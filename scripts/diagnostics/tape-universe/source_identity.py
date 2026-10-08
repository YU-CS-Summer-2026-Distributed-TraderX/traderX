#!/usr/bin/env python3
"""Inspect reader-observed CT/CQ identity loss with pinned current pure SELECT only."""
import argparse
import collections
import csv
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
spec=importlib.util.spec_from_file_location('universe_common',HERE/'coverage.py')
common=importlib.util.module_from_spec(spec);spec.loader.exec_module(common)
MAX_BYTES=128*1024*1024
PINS={'CT':'d45e46fa37b20409038e6a68a69735a665b177779240b8df4456b0471e944d24',
      'CQ':'c42f8a56778daaafab1f271c68abd886b8c3f1a2bc22c57691f829cbb2844003'}


def key(value):
    return json.dumps(value,sort_keys=True,ensure_ascii=True,allow_nan=False)


def summarize(rows, projected):
    tuples={};symbols={}
    for row in rows:
        identity={'root':row['root'],'suffix':row['suffix']}
        token=key(identity)
        entry=tuples.setdefault(token,{'identity':identity,'observationRows':[]})
        entry['observationRows'].append(row['row'])
        if projected:
            symbol=row['outputSymbol'];symbol_key=key(symbol)
            group=symbols.setdefault(symbol_key,{'outputSymbol':symbol,'distinctTupleKeys':set(),'observationRows':[]})
            group['distinctTupleKeys'].add(token);group['observationRows'].append(row['row'])
    repeats=[v for _,v in sorted(tuples.items()) if len(v['observationRows'])>1]
    groups=[]
    for _,group in sorted(symbols.items()):
        members=[tuples[t] for t in sorted(group.pop('distinctTupleKeys'))]
        groups.append({**group,'distinctSourceTuples':members,'distinctTupleCount':len(members),
                       'collapse':len(members)>1,'lostTupleDistinctions':max(0,len(members)-1)})
    return {'observations':len(rows),'distinctReaderTuples':len(tuples),
            'readerTupleGroups':[v for _,v in sorted(tuples.items())],'repeatedTupleGroups':repeats,'repeatExtraObservations':sum(len(v['observationRows'])-1 for v in repeats),
            'outputGroups':groups if projected else None,
            'collapseGroups':[v for v in groups if v['collapse']] if projected else None,
            'lostTupleDistinctions':sum(v['lostTupleDistinctions'] for v in groups) if projected else None}


def analyze(args):
    role,data=common.snapshot(args.input,args.max_input_bytes)
    name='ingest_taq_trades.py' if args.kind=='CT' else 'ingest_taq_quotes.py'
    source_role,source=common.snapshot(Path(args.tick_store_root)/name,16384)
    report={'schema':1,'analysis':'incomplete','kind':args.kind,'input':role,'source':source_role,
        'proof':None,'unfilteredReader':None,'eligibleProjection':None,
        'semantics':'reader-observed opaque root/suffix values only; no canonical ticker/vendor/share-class/financial meaning inferred',
        'diagnostic':{'scriptSha256':common.digest(Path(__file__).read_bytes()),
            'readerSha256':common.digest((HERE/'identity_reader.py').read_bytes()),
            'commonSha256':common.digest((HERE/'coverage.py').read_bytes()),
            'budgets':{'inputBytes':args.max_input_bytes,'readerRows':args.max_rows,'outputBytes':args.max_output_bytes,'childSeconds':20},
            'limits':'raw lexical values may be normalized by actual CSV null/type inference; no real corpus/cloud/store evidence; pure SELECT only, never ingestion/COPY/Parquet/GCS'}}
    if source is None or data is None:
        return report
    if source_role['sha256']!=PINS[args.kind]:
        source_role.update(state='invalid',reason='unsupported ingester source hash; no execution')
        return report
    try:
        source_text=source.decode('utf-8')
        reader=csv.reader(io.StringIO(data.decode('utf-8')),strict=True)
        header=next(reader,None)
        if not header or len(header)!=len(set(header)) or not {'SYM_ROOT','SYM_SUFFIX'}.issubset(header):
            raise ValueError('raw identity proof requires unique explicit SYM_ROOT/SYM_SUFFIX headers')
        # DuckDB performs actual schema/type/filter validation. No Python ticker/eligibility normalization.
        with tempfile.TemporaryDirectory(prefix='source-identity-') as directory:
            captured=Path(directory)/'captured.csv';captured.write_bytes(data)
            request={'sourceCode':source_text,'kind':args.kind,'csvSnapshot':str(captured),'maxRows':args.max_rows}
            proc=subprocess.run([args.reader_python,'-B',str(HERE/'identity_reader.py')],input=json.dumps(request),
                text=True,capture_output=True,timeout=20,check=True,
                env={k:os.environ[k] for k in ('PATH','TMPDIR') if k in os.environ})
            proof=json.loads(proc.stdout)
            if 'reason' in proof:
                proof['reason']=proof['reason'].replace(str(captured),role['suppliedPath'])
        report['proof']={k:v for k,v in proof.items() if k not in ('unfiltered','eligible')}
        if proof['state']!='valid':
            report['proof']['reason']=proof.get('reason','reader refused')
            return report
        raw=proof['unfiltered'];eligible=proof['eligible']
        report['unfilteredReader']=summarize(raw,False)
        report['unfilteredReader']['excludedObservationRows']=[r['row'] for r in raw if not r['eligible']]
        report['eligibleProjection']=summarize(eligible,True)
        report['analysis']='complete'
    except (ValueError,UnicodeError,csv.Error,OSError,subprocess.SubprocessError,RecursionError) as error:
        report['proof']={'state':'invalid','reason':type(error).__name__+': '+str(error)}
    return report


def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--kind',choices=('CT','CQ'),required=True)
    p.add_argument('--input')
    p.add_argument('--reader-python',default=sys.executable,help='existing offline Python interpreter with cached DuckDB')
    p.add_argument('--tick-store-root',default=str(ROOT/'specs/YU07-historical-tick-store/generation/runtime-overrides/tick-store'))
    p.add_argument('--output')
    p.add_argument('--max-input-bytes',type=int,default=MAX_BYTES)
    p.add_argument('--max-rows',type=int,default=10000)
    p.add_argument('--max-output-bytes',type=int,default=4*1024*1024)
    args=p.parse_args(argv)
    if not 0<args.max_input_bytes<=MAX_BYTES or min(args.max_rows,args.max_output_bytes)<=0:
        p.error('input budget must be1..128MiB; row/output budgets must be positive')
    report=analyze(args)
    try:
        data=(json.dumps(report,sort_keys=True,indent=2,ensure_ascii=True,allow_nan=False)+'\n').encode()
        if len(data)>args.max_output_bytes:
            raise ValueError('identity report exceeds diagnostic output budget')
        output=common.private_output(args.output,data)
    except (ValueError,OSError) as error:
        print('output refused: '+str(error),file=sys.stderr);return 2
    counts={k:report['eligibleProjection'][k] for k in ('observations','distinctReaderTuples','repeatExtraObservations','lostTupleDistinctions')} if report['analysis']=='complete' else None
    print(json.dumps({'analysis':report['analysis'],'counts':counts,'privateOutput':output},sort_keys=True))
    return 0 if report['analysis']=='complete' else 2


if __name__=='__main__':sys.exit(main())
