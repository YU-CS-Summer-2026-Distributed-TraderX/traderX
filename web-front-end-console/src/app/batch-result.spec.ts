import { TestBed } from '@angular/core/testing';
import { Api } from './api';
import { BatchResultPanel } from './batch-result';
describe('Cloud Batch portfolio', () => {
  let reply: any;
  beforeEach(() => {
    reply = {status:200, body:{schema:'traderx.batch-result.v1',availability:'AVAILABLE',status:'VERIFIED',
      usableForRisk:false,synthetic:true,financialValidation:false,scenarioRiskAvailable:false,
      market:{rate:'0.05',compounding:'continuous',day_count:'ACT/365F',asof:'2025-06-02'},
      baseNpvUsd:98507.14563826029,tradeCount:1,accountId:'22214',runId:'risk-cpu-test-01',
      currentPortfolioComparison:'NOT_CHECKED',cut:{cutSha256:'c'.repeat(64),sessionDate:'2025-06-02',consensusSequence:'8'}}};
    TestBed.configureTestingModule({imports:[BatchResultPanel],providers:[{provide:Api,useValue:{load:async()=>reply}}]});
  });
  it('shows account, cut, NPV and honest unavailable measures', async () => {
    const f=TestBed.createComponent(BatchResultPanel);f.detectChanges();await f.whenStable();f.detectChanges();
    expect(f.nativeElement.textContent).toContain('98,507.15');
    expect(f.nativeElement.textContent).toContain('5%');
    expect(f.nativeElement.textContent).not.toContain('3%');
    expect(f.nativeElement.textContent).toContain('22214');
    expect(f.nativeElement.textContent).toContain('sequence 8');
    expect(f.nativeElement.textContent).toContain('Not performed');
    expect(f.nativeElement.textContent).toContain('Unavailable');
  });
  it('clears an accepted price when refresh fails and supports empty state', async () => {
    const f=TestBed.createComponent(BatchResultPanel);f.detectChanges();await f.whenStable();f.detectChanges();
    reply={status:503,body:{code:'VERIFICATION_FAILED'}};
    await f.componentInstance.refresh();f.detectChanges();
    expect(f.nativeElement.querySelector('[data-batch-npv]')).toBeNull();
    expect(f.nativeElement.textContent).toContain('could not be verified');
    reply={status:503,body:{code:'NOT_CONFIGURED'}};
    await f.componentInstance.refresh();f.detectChanges();
    expect(f.nativeElement.textContent).toContain('No Batch result has been selected');
  });
  it('refuses malformed and overclaimed successful responses', async () => {
    const f=TestBed.createComponent(BatchResultPanel);
    for (const mutation of [{financialValidation:true},{tradeCount:0},{baseNpvUsd:null},{cut:null}]) {
      const original=reply; reply={status:200,body:{...original.body,...mutation}};
      await f.componentInstance.refresh(); f.detectChanges();
      expect(f.componentInstance.result()).toBeNull(); reply=original;
    }
  });
});
