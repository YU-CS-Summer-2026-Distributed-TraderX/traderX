#!/usr/bin/env python3
"""Assemble synthetic Java-exported Treasury/SOFR exchange cases with explicit reference terms."""
import argparse
from pathlib import Path
import os
import bundle
import bridge
import instrument_terms
from coordinator import load_json

EPOCH = 'synthetic-shared-examples-v1'
VALUATION = '2025-06-02T16:00:00-04:00'


def reference(parsed):
    entries = []
    for kind, (_, rows) in parsed.items():
        for row in rows:
            identity = ({'source':kind, 'security':row['security']} if kind=='positions' else
                        {'source':kind, 'contractId':row['contractId'], 'clusterEpoch':EPOCH})
            if any(e['identity']==identity for e in entries):
                continue
            product = row['instrumentType' if kind=='positions' else 'productType']
            if product == 'TREASURY':
                zero = bundle.decimal(row['coupon'], 'coupon') == 0
                terms = {'currency':row['currency'], 'issueDate':'2025-06-02' if zero else '2024-12-15',
                         'maturityDate':row['maturityDate'], 'couponRatePercent':row['coupon'],
                         'couponFrequency':'NONE' if zero else '6M', 'dayCount':'NOT_APPLICABLE' if zero else 'ACT/ACT (ICMA)',
                         'calendar':'NONE', 'businessDayAdjustment':'UNADJUSTED',
                         'paymentLagBusinessDays':0, 'settlementDays':0,
                         'firstCouponDate':None if zero else '2025-06-15',
                         'penultimateCouponDate':None if zero else '2026-06-15', 'stubConvention':'NONE',
                         'schedule':[], 'faceDenomination':'100', 'redemptionFraction':'1',
                         'priceBasis':'clean-fraction-of-par', 'quantityUnit':'signed-currency-face'}
                if not zero:
                    dates = ['2024-12-15','2025-06-15','2025-12-15','2026-06-15','2026-12-15']
                    terms['schedule'] = [{'startDate':a,'endDate':b,'paymentDate':b} for a,b in zip(dates,dates[1:])]
                description = ('Synthetic reference supplement. Coupon/maturity/currency agree with exporter. '
                    'Issue, denomination, redemption and explicit regular schedule are fixture assumptions. '
                    'Calendar NONE, unadjusted payments, zero lag and same-day settlement deliberately match '
                    'the exporter session-date accrual basis; these are not asserted market conventions.')
            else:
                mapping = {'currency':'currency','payReceive':'payReceive','notional':'notional',
                           'fixedRate':'fixedRate','floatIndex':'floatIndex','effectiveDate':'effectiveDate',
                           'maturityDate':'maturityDate','fixedPaymentFrequency':'paymentFrequency','fixedDayCount':'dayCount'}
                terms = {key:row[field] for key,field in mapping.items()}
                description = ('Synthetic booking using the current compiled USD-SOFR-1Y-ACT360 convention. '
                    'Only exported terms supplied; no inferred calendars, floating tenor, schedules or compounding.')
            entries.append({'identity':identity,'instrumentType':product,
                            'provenance':{'origin':'synthetic','description':description}, 'terms':terms,
                            'missingTerms':sorted(instrument_terms.REQUIRED[product]-set(terms))})
    return {'schema':instrument_terms.SCHEMA,'entries':entries}


def assemble(exports, output):
    exports, output = Path(exports), Path(output)
    bundle.require(not output.exists(), 'output already exists')
    output.mkdir(mode=0o700, parents=True)
    for name in ('bill','note','sofr'):
        source=exports/name
        receipts=list((source/'receipts').glob('*.ready.json'))
        bundle.require(len(receipts)==1, 'one exporter receipt required per case')
        event=load_json(receipts[0])
        payloads={kind:(source/(kind+'.csv')).read_bytes() for kind in bundle.KINDS}
        bridge.validate_event(event,payloads,'2025-06-02')
        cut=(source/'cut.txt').read_bytes()
        bundle.require(bundle.digest(cut)==event['cutSha256'], 'exported cut hash mismatch')
        parsed=bundle.read_pair(payloads)
        rows, contracts=parsed['positions'][1],parsed['contracts'][1]
        bundle.require(len(rows)==(0 if name=='sofr' else 2) and len(contracts)==(1 if name=='sofr' else 0), 'wrong case population')
        if rows:
            bundle.require({r['quantity'] for r in rows}=={'100000','-100000'}, 'long/short face positions required')
            bundle.require(all(r['security']==('UST-BILL-20251202' if name=='bill' else 'UST-NOTE-20261215')
                               and r['markQuality']=='SYNTHETIC' for r in rows),'unexpected fixture instrument')
        else:
            bundle.require(contracts[0]['floatIndex']=='USD-SOFR' and contracts[0]['paymentFrequency']=='1Y'
                           and contracts[0]['dayCount']=='ACT/360','wrong SOFR fixture conventions')
        target=output/name;target.mkdir()
        (target/'cut.txt').write_bytes(cut)
        terms=target/'instrument-terms.json';terms.write_bytes(bundle.encoded(reference(parsed)))
        for version in ('v1','v2'):
            bundle.build(source/'positions.csv',source/'contracts.csv',target/version,EPOCH,VALUATION,'synthetic',
                         terms if version=='v2' else None)
        terms.unlink()  # the hash-pinned v2 artifact is the single delivered copy
        case={'schema':'traderx.shared-example.v1','synthetic':True,'example':name,
              'expectedWorkerOutcome':'unsupported' if name=='sofr' else 'financial-validation-pending',
              'expectationIsObservedResult':False,
              'reason':'SOFR convention representation incomplete; no generic IBOR substitution' if name=='sofr' else
                       'Alex must price using an explicitly selected assumed curve; no market inputs supplied',
              'exportReceiptChecks':'hashes-counts-cut-adjacent-witness',
              'quiesceWitnessSequence':str(event['quiesceWitnessSequence'])}
        (target/'case.json').write_bytes(bundle.encoded(case))
    return output


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exports',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args();os.umask(0o077)
    print(assemble(args.exports,args.output))
