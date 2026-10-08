"""Bounded pure SELECT child. Ingester Python source is parsed as data, never imported."""
import ast
import collections
import datetime
import decimal
import hashlib
import json
import math
from pathlib import Path
import sys

PINS = {'CT':'d45e46fa37b20409038e6a68a69735a665b177779240b8df4456b0471e944d24',
        'CQ':'c42f8a56778daaafab1f271c68abd886b8c3f1a2bc22c57691f829cbb2844003'}


def extract_select(source, kind):
    data = source.encode('utf-8')
    if hashlib.sha256(data).hexdigest() != PINS[kind]:
        raise ValueError('unsupported ingester source hash')
    tree = ast.parse(source)
    ingest = [n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='ingest']
    if len(ingest)!=1:
        raise ValueError('cannot isolate ingest function')
    calls = [n for n in ast.walk(ingest[0]) if isinstance(n,ast.Call)
             and isinstance(n.func,ast.Attribute) and n.func.attr=='execute']
    if len(calls)!=1 or len(calls[0].args)!=1 or not isinstance(calls[0].args[0],ast.JoinedStr):
        raise ValueError('unsupported SQL expression structure')
    segments=[]
    for item in calls[0].args[0].values:
        if isinstance(item,ast.Constant) and isinstance(item.value,str):
            segments.append(item.value)
        elif isinstance(item,ast.FormattedValue) and isinstance(item.value,ast.Name) and item.format_spec is None and item.conversion==-1:
            if item.value.id not in ('csv_path','out_dir'):
                raise ValueError('unsupported SQL dynamic input')
            segments.append('__INPUT__' if item.value.id=='csv_path' else '__OUTPUT__')
        else:
            raise ValueError('unsupported SQL interpolation')
    wrapper=''.join(segments)
    marker=") TO '__OUTPUT__' ("
    if not wrapper.startswith('COPY (SELECT ') or wrapper.count(marker)!=1:
        raise ValueError('cannot faithfully isolate COPY read/projection portion')
    query=wrapper[len('COPY ('):wrapper.index(marker)]
    if query.count("'__INPUT__'")!=1 or ';' in query:
        raise ValueError('unsupported reader parameter form')
    query=query.replace("'__INPUT__'",'?')
    a=query.index('FROM read_csv(');b=query.index('WHERE ',a)
    fields=query[len('SELECT '):a].strip()
    reader=query[a+len('FROM '):b].strip()
    predicate=query[b+len('WHERE '):].strip()
    if not reader.startswith('read_csv(?, header=true, auto_detect=true)') or not predicate:
        raise ValueError('unsupported raw reader/filter form')
    return query,fields,reader,predicate


def atom(value, dtype):
    out={'duckdbType':str(dtype),'isNull':value is None}
    if isinstance(value,float) and not math.isfinite(value):
        out['specialValue']=repr(value)
    elif isinstance(value,(datetime.date,datetime.time,datetime.datetime,decimal.Decimal)):
        out['value']=str(value)
    elif value is None or isinstance(value,(str,int,float,bool)):
        out['value']=value
    else:
        raise ValueError('unsupported reader identity scalar')
    return out


def key(value):
    return json.dumps(value,sort_keys=True,ensure_ascii=True,allow_nan=False)


def run(request):
    try:
        import duckdb
        import _duckdb
    except ImportError:
        return {'state':'unavailable','reason':'cached DuckDB unavailable; no install attempted'}
    query,fields,reader,predicate=extract_select(request['sourceCode'],request['kind'])
    bound=int(request['maxRows'])+1
    queries={
        'unfilteredReader':f'SELECT row_number() OVER () AS __diagnostic_row, SYM_ROOT AS observed_root, SYM_SUFFIX AS observed_suffix, ({predicate}) AS source_eligible FROM {reader} LIMIT {bound}',
        'currentSelect':f'SELECT symbol FROM ({query} LIMIT {bound}) AS __current',
        'eligibleLineage':f'SELECT __diagnostic_row, observed_root, observed_suffix, symbol FROM (SELECT __diagnostic_row, SYM_ROOT AS observed_root, SYM_SUFFIX AS observed_suffix, {fields} FROM (SELECT row_number() OVER () AS __diagnostic_row, * FROM {reader}) AS __source WHERE {predicate} LIMIT {bound}) AS __lineage'}
    con=duckdb.connect(':memory:',config={'memory_limit':'128MiB','threads':1,
        'autoinstall_known_extensions':'false','autoload_known_extensions':'false','temp_directory':''})
    try:
        source_schemas = {}
        schema_queries = {'reader':f'SELECT * FROM {reader} LIMIT 0','projection':query+' LIMIT 0'}
        for name, sql in schema_queries.items():
            result = con.execute(sql,[request['csvSnapshot']])
            source_schemas[name] = [(c[0],str(c[1])) for c in result.description]
        datasets={};schemas={}
        for name,sql in queries.items():
            if not sql.startswith('SELECT ') or ';' in sql or 'COPY (' in sql:
                raise ValueError('refused non-pure-SELECT query')
            result=con.execute(sql,[request['csvSnapshot']])
            datasets[name]=result.fetchall()
            schemas[name]=[(c[0],str(c[1])) for c in result.description]
            if len(datasets[name])>request['maxRows']:
                raise ValueError('reader population exceeds diagnostic row budget; no truncated verdict')
        raw=datasets['unfilteredReader'];lineage=datasets['eligibleLineage'];actual=datasets['currentSelect']
        if not raw or not actual:
            raise ValueError('empty raw or zero eligible current SELECT population is not proof')
        columns=dict(schemas['unfilteredReader']);line_cols=dict(schemas['eligibleLineage'])
        symbol_index=[c[0] for c in schemas['currentSelect']].index('symbol')
        lineage_symbol_index=[c[0] for c in schemas['eligibleLineage']].index('symbol')
        expected_ids={r[0] for r in raw if r[3] is True}
        if expected_ids!={r[0] for r in lineage} or len(lineage)!=len(actual):
            raise ValueError('annotated lineage disagrees with current SELECT eligibility/count')
        if collections.Counter(key(atom(r[symbol_index],dict(schemas['currentSelect'])['symbol'])) for r in actual)!=collections.Counter(key(atom(r[lineage_symbol_index],line_cols['symbol'])) for r in lineage):
            raise ValueError('actual current SELECT and lineage output-symbol multisets disagree')
        raw_by_id={r[0]:r for r in raw}
        eligible=[]
        for row in lineage:
            observed=raw_by_id[row[0]]
            if key([atom(row[1],columns['observed_root']),atom(row[2],columns['observed_suffix'])])!=key([atom(observed[1],columns['observed_root']),atom(observed[2],columns['observed_suffix'])]):
                raise ValueError('source tuple lineage mismatch')
            eligible.append({'row':row[0],'root':atom(row[1],columns['observed_root']),
                'suffix':atom(row[2],columns['observed_suffix']),
                'outputSymbol':atom(row[lineage_symbol_index],line_cols['symbol'])})
        unfiltered=[{'row':r[0],'root':atom(r[1],columns['observed_root']),
                     'suffix':atom(r[2],columns['observed_suffix']),'eligible':r[3] is True} for r in raw]
        binary=Path(_duckdb.__file__);h=hashlib.sha256()
        with binary.open('rb') as stream:
            for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
        return {'state':'valid','duckdb':{'version':duckdb.__version__,'extensionPath':str(binary),
            'extensionSha256':h.hexdigest(),'python':sys.executable,'memorySetting':'128MiB; not total process RSS','spillDisabled':True,'extensionAutoInstallAndLoad':False},
            'extractedCurrentSelect':{'sql':query,'sha256':hashlib.sha256(query.encode()).hexdigest()},
            'materialization':'identity-bearing columns only; current full SELECT retained as inner query, no price/timestamp conversion or financial proof',
            'queries':{name:{'sql':sql,'sha256':hashlib.sha256(sql.encode()).hexdigest()} for name,sql in queries.items()},
            'schemaQueries':{name:{'sql':sql,'sha256':hashlib.sha256(sql.encode()).hexdigest()} for name,sql in schema_queries.items()},
            'schemas':schemas,'sourceSchemas':source_schemas,'predicate':predicate,'unfiltered':unfiltered,'eligible':eligible,
            'actualCurrentSelectCount':len(actual),'actualSelectLineageAgreement':True}
    finally:
        con.close()


if __name__=='__main__':
    try:
        response=run(json.loads(sys.stdin.read()))
    except Exception as error:
        response={'state':'invalid','reason':type(error).__name__+': '+str(error)}
    sys.stdout.write(json.dumps(response,sort_keys=True,ensure_ascii=True,allow_nan=False))
