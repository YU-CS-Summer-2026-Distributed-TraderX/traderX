"""Closed traded-bill demo profile. Exact export lineage; never admits a fixed fixture as a trade."""
from datetime import date
from decimal import Decimal as D
import csv,io,json
from pathlib import Path
import bundle,pricing_result
from instrument_terms import decode
PROFILE='traderx-booked-bill-demo-v1'
SECURITY='UST-BILL-20261112'; DAY='2026-09-16'; ACCOUNT='17017'; SELLER='42422'; FACE=D('1000')

def require(ok,message):bundle.require(ok,message)

def prepare(original,output,proof,reference):
    original=Path(original);output=Path(output)
    payload={k:(original/(k+'.csv')).read_bytes() for k in bundle.KINDS}
    parsed=bundle.read_pair(payload)
    meta,rows=parsed['positions']; selected=[r for r in rows if r['accountId']==ACCOUNT and r['security']==SECURITY]
    require(len(selected)==1,'missing or duplicate actual booked bill position')
    require(not parsed['contracts'][1],'demo refuses OTC contracts')
    row=selected[0]; check_position(row,proof)
    debt=reference['debtEconomics'];require(reference['instrumentKey']==SECURITY and reference['currency']=='USD','reference identity mismatch')
    require(debt['issueDate']=='2026-08-13' and debt['maturityDate']=='2026-11-12' and debt['zeroCoupon']['couponRatePercent']==0 and debt['principalRepayment']['parAmount']==100,'unsupported reference economics')
    terms={'currency':'USD','issueDate':debt['issueDate'],'maturityDate':debt['maturityDate'],'couponRatePercent':'0','couponFrequency':'NONE','dayCount':'NOT_APPLICABLE','calendar':'NONE','businessDayAdjustment':'UNADJUSTED','paymentLagBusinessDays':0,'settlementDays':0,'firstCouponDate':None,'penultimateCouponDate':None,'stubConvention':'NONE','schedule':[],'faceDenomination':'100','redemptionFraction':'1','priceBasis':'clean-fraction-of-par','quantityUnit':'signed-currency-face'}
    # A documented model supplement, not a claim of market-standard settlement. Issue/maturity,
    # principal and currency come from the actual venue reference response above.
    term_doc={'schema':'traderx.instrument-terms.v1','entries':[{'identity':{'source':'positions','security':SECURITY},'instrumentType':'TREASURY','missingTerms':[],'provenance':{'origin':'supplied','description':'Venue reference economics plus explicit operator demo conventions: value booked trade-date face; calendar NONE, unadjusted maturity, zero settlement/payment lag. Not settlement-date or production risk valuation.'},'terms':terms}]}
    lines=payload['positions'].decode().splitlines(); prefix=[l for l in lines if l.startswith('#')];prefix=[('# rows=1' if l.startswith('# rows=') else l) for l in prefix]
    stream=io.StringIO();writer=csv.DictWriter(stream,fieldnames=bundle.KINDS['positions'][1],lineterminator='\n');writer.writeheader();writer.writerow(row)
    scoped={'positions':('\n'.join(prefix)+'\n'+stream.getvalue()).encode(),'contracts':payload['contracts']}
    t=bundle.encoded(term_doc); manifest=bundle.manifest_for(scoped,proof['epoch'],DAY+'T16:00:00-04:00','export',t)
    bundle.publish_directory(output,{**{k+'.csv':v for k,v in scoped.items()},'instrument-terms.json':t,'manifest.json':bundle.encoded(manifest)})
    return manifest

def check_position(row,proof):
    require(proof['profile']==PROFILE and proof['security']==SECURITY and proof['account']==ACCOUNT and proof['seller']==SELLER,'trade proof identity mismatch')
    require(proof['valuationDate']==DAY and proof['epoch']=='2026091601','unsupported date or epoch')
    require(proof['baselineBuyerFace']=='0' and proof['baselineSellerFace']=='0','nonempty scenario baseline')
    require(D(proof['buyerFace'])==FACE and D(proof['sellerFace'])==-FACE,'booking deltas mismatch')
    require(row['accountId']==ACCOUNT and row['security']==SECURITY and row['currency']=='USD' and row['instrumentType']=='TREASURY','export identity mismatch')
    require(D(row['quantity'])==FACE and D(row['contractMultiplier'])==1 and D(row['coupon'])==0 and row['maturityDate']=='2026-11-12','unsupported position economics')
    require(D(row['costBasis'])==D(proof['bookedPrice']) and D(row['closingMark'])>0 and row['markQuality']=='OK','invalid cost basis or mark')
    require(row['lastCouponDate']==row['accruedInterestFraction']=='','invalid bill accrual')
    for side,account,face in [('Buy',ACCOUNT,FACE),('Sell',SELLER,FACE)]:
        tr=proof['trades'][side];require(str(tr['accountId'])==account and tr['security']==SECURITY and tr['side']==side and D(str(tr['quantity']))==face and D(str(tr['price']))==D(proof['bookedPrice']) and tr['state'] in ('Processing','Settled') and not tr.get('rejectionReason'),'trade booking mismatch')
        require(tr['sourceOrderId']==proof['epoch']+'-'+str(proof['orders'][side]['orderRef']),'trade/order identity mismatch')
        require(proof['orders'][side]['kind']==1,'order not accepted')

def validate(source,original,proof,reference,data):
    source=Path(source);original=Path(original)
    manifest,parsed=bundle.validate(source)
    require(manifest['inputOrigin']=='export' and manifest['clusterEpoch']==proof['epoch'] and manifest['cut']['sessionDate']==DAY and manifest['valuationTime']==DAY+'T16:00:00-04:00','bundle identity/date mismatch')
    require(len(parsed['positions'][1])==1 and not parsed['contracts'][1],'profile row scope mismatch')
    payload={k:(original/(k+'.csv')).read_bytes() for k in bundle.KINDS}; full=bundle.read_pair(payload)
    receipt=decode((original/'receipt.json').read_bytes())
    require(bundle.digest(payload['positions'])==receipt['sha256'] and bundle.digest(payload['contracts'])==receipt['contractsSha256'],'original receipt/hash mismatch')
    require(all(str(receipt[k])==str(manifest['cut'][k]) for k in bundle.STAMP),'cut receipt mismatch')
    require(full['positions'][0]['sessionDate']==DAY and full['positions'][0]['cutSha256']==manifest['cut']['cutSha256'],'original cut mismatch')
    selected=[r for r in full['positions'][1] if r['accountId']==ACCOUNT and r['security']==SECURITY]
    require(selected==parsed['positions'][1],'scoped position differs from original export')
    row=selected[0];check_position(row,proof)
    require(proof['exportHashes']=={k:bundle.digest(v) for k,v in payload.items()},'run/export binding mismatch')
    require(D(row['closingMark'])==D(str(proof['eodMark']['closingPrice'])) and proof['eodMark']['quality']=='OK' and not proof['eodMark']['flagged'],'EOD mark mismatch')
    require(abs(proof['closeMillis']-proof['eodMark']['sourceTickMillis'])<60000,'stale EOD mark')
    terms=decode((source/'instrument-terms.json').read_bytes())['entries'][0]['terms']
    require(reference['instrumentKey']==SECURITY and terms['issueDate']==reference['debtEconomics']['issueDate']=='2026-08-13' and terms['maturityDate']=='2026-11-12','terms reference mismatch')
    require(terms=={'currency':'USD','issueDate':'2026-08-13','maturityDate':'2026-11-12','couponRatePercent':'0','couponFrequency':'NONE','dayCount':'NOT_APPLICABLE','calendar':'NONE','businessDayAdjustment':'UNADJUSTED','paymentLagBusinessDays':0,'settlementDays':0,'firstCouponDate':None,'penultimateCouponDate':None,'stubConvention':'NONE','schedule':[],'faceDenomination':'100','redemptionFraction':'1','priceBasis':'clean-fraction-of-par','quantityUnit':'signed-currency-face'},'unsupported demo terms')
    require(date.fromisoformat(terms['issueDate'])<=date.fromisoformat(DAY)<date.fromisoformat(terms['maturityDate']),'unsupported valuation date')
    pricing_result.validate_inputs(manifest,selected,terms,data)
    result=decode(data);priced=D(str(result['items'][0]['calculations']['npv']['value']));ref=pricing_result.reference(row,terms,DAY)[0]['value'].value
    return {'instrument':SECURITY,'currency':'USD','quantity':'1000','signedFaceUsd':'1000','bookedPrice':proof['bookedPrice'],'bookedPriceUnit':'fraction of par','valuationDate':DAY,'pricingUsd':format(priced,'f'),'referenceUsd':format(ref,'f'),'differenceUsd':format(priced-ref,'f'),'toleranceUsd':'0.00000001','withinTolerance':True,'rateSensitivity':'unsupported','executionLocation':'local','assumedCurve':'Flat 3%','usableForRisk':False}


def eod_mark(report):
    """Admit the actual EodReport API shape; never substitute a quote on a missing field."""
    require(report.get('status')=='PUBLISHED' and report.get('flaggedCount')==0, 'EOD quality gate refused')
    require(report.get('sessionDate')==DAY and isinstance(report.get('instruments'),list), 'invalid EOD report')
    marks=[r for r in report['instruments'] if r.get('security')==SECURITY]
    require(len(marks)==1 and marks[0].get('quality')=='OK' and marks[0].get('flagged') is False, 'invalid bill closing mark')
    return marks[0]
