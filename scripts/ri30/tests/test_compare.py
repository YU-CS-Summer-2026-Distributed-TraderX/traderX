import duckdb
import pyarrow as pa
import pyarrow.parquet as pq
import pytest
from compare import compare_files


def test_exact_multiset_not_just_counts(tmp_path):
    def file(name, prices):
        path=tmp_path/name
        pq.write_table(pa.table({'DATE':['2025-03-03']*len(prices),'PRICE':prices}),path)
        return [str(path)]
    a=file('a.parquet',['1.00','1.00','2.00'])
    same=file('same.parquet',['2.00','1.00','1.00'])
    altered=file('altered.parquet',['1.00','2.00','2.00'])
    extra=file('extra.parquet',['1.00','1.00','2.00','3.00'])
    con=duckdb.connect()
    assert compare_files(con,a,same,['DATE','PRICE'],'2025-03-03')['verdict']=='identical_multiset'
    r=compare_files(con,a,altered,['DATE','PRICE'],'2025-03-03')
    assert r['daily_rows_missing_from_monthly']==1 and r['monthly_rows_extra_to_daily']==1
    assert compare_files(con,a,extra,['DATE','PRICE'],'2025-03-03')['verdict']=='daily_contained'
    with pytest.raises(ValueError): compare_files(con,a,[],['DATE','PRICE'],'2025-03-03')
