import copy,json,tempfile,unittest
from pathlib import Path
import bundle,pricing_result,treasury_trade as t
class TradedBill(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.original=self.root/'original';self.original.mkdir()
  self.row=dict(zip(bundle.POSITION_FIELDS,['17017',t.SECURITY,'TREASURY','1000','1','0.989680','0.989680','SIMULATED','OK','989.68','0','USD','CP','NS','0','2026-11-12','','']))
  self.proof={'profile':t.PROFILE,'security':t.SECURITY,'account':t.ACCOUNT,'seller':t.SELLER,'valuationDate':t.DAY,'epoch':'2026091601','baselineBuyerFace':'0','baselineSellerFace':'0','buyerFace':'1000','sellerFace':'-1000','bookedPrice':'0.989680','orders':{},'trades':{},'closeMillis':1000,'eodMark':{'closingPrice':0.989680,'quality':'OK','flagged':False,'sourceTickMillis':1000}}
  for side,acct,ref in [('Buy',17017,102),('Sell',42422,101)]:
   self.proof['orders'][side]={'kind':1,'orderRef':ref};self.proof['trades'][side]={'accountId':acct,'security':t.SECURITY,'side':side,'quantity':1000,'price':0.989680,'state':'Processing','sourceOrderId':'2026091601-'+str(ref),'rejectionReason':None}
  receipt={'sessionDate':t.DAY,'consensusSequence':200,'priceSnapshotVersion':2,'cutSha256':'c'*64}
  for kind,(schema,fields,count,title) in bundle.KINDS.items():
   text=f'# {title} schema={schema}\n'+''.join(f'# {k}={v}\n' for k,v in receipt.items())+f'# {count}={1 if kind=="positions" else 0}\n'+','.join(fields)+'\n'
   if kind=='positions':text+=','.join(self.row[f] for f in fields)+'\n'
   data=text.encode();(self.original/(kind+'.csv')).write_bytes(data);receipt['sha256' if kind=='positions' else 'contractsSha256']=bundle.digest(data)
  (self.original/'receipt.json').write_bytes(bundle.encoded(receipt));self.proof['exportHashes']={k:bundle.digest((self.original/(k+'.csv')).read_bytes()) for k in bundle.KINDS}
  self.reference={'instrumentKey':t.SECURITY,'currency':'USD','debtEconomics':{'issueDate':'2026-08-13','maturityDate':'2026-11-12','zeroCoupon':{'couponRatePercent':0},'principalRepayment':{'parAmount':100}}}
  self.out=self.root/'bundle';t.prepare(self.original,self.out,self.proof,self.reference)
 def tearDown(self):self.tmp.cleanup()
 def test_actual_eod_report_contract_and_no_quote_fallback(self):
  mark={'security':t.SECURITY,'quality':'OK','flagged':False,'closingPrice':0.98966}
  report={'sessionDate':t.DAY,'status':'PUBLISHED','flaggedCount':0,'instruments':[mark]}
  self.assertEqual(t.eod_mark(report),mark)
  for bad in [{**report,'instruments':[]},{**report,'instruments':[mark,mark]},{**report,'status':'DRAFT'},{**report,'instruments':None,'prices':[mark]}]:
   with self.assertRaises(ValueError):t.eod_mark(bad)
 def test_exact_export_scope_and_old_profile_refusal(self):
  manifest,parsed=bundle.validate(self.out);self.assertEqual(parsed['positions'][1],[self.row]);self.assertEqual(manifest['inputOrigin'],'export')
  with self.assertRaisesRegex(ValueError,'exact provisional'):pricing_result.inputs(self.out)
 def test_unsupported_terms_and_inconsistent_booking(self):
  for key,value in [('buyerFace','2000'),('epoch','changed'),('bookedPrice','1'),('valuationDate','2025-06-02')]:
   p=copy.deepcopy(self.proof);p[key]=value
   with self.assertRaises(ValueError):t.check_position(self.row,p)
 def test_corrupt_result_and_identity_are_rejected(self):
  for raw in [b'{}',b'{"bundleId":"wrong"}',b'{"value":NaN}']:
   with self.assertRaises(ValueError):t.validate(self.out,self.original,self.proof,self.reference,raw)
 def test_stale_mark_and_export_substitution_are_rejected(self):
  p=copy.deepcopy(self.proof);p['eodMark']['sourceTickMillis']=-999999
  with self.assertRaisesRegex(ValueError,'stale'):t.validate(self.out,self.original,p,self.reference,b'{}')
  p=copy.deepcopy(self.proof);p['exportHashes']['positions']='0'*64
  with self.assertRaisesRegex(ValueError,'binding'):t.validate(self.out,self.original,p,self.reference,b'{}')
if __name__=='__main__':unittest.main()
