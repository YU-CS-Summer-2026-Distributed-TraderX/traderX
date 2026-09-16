import { TestBed } from '@angular/core/testing';
import { EodJobs } from './eod-jobs';
import { Api } from './api';

describe('EOD jobs read surface', () => {
  let response: any;
  beforeEach(() => {
    response = {status:200,body:{schema:'traderx.eod-job-status.v1',availability:'AVAILABLE',usableForRisk:false,observedAt:'2025-06-02T20:00:00Z',jobs:[]}};
    TestBed.configureTestingModule({imports:[EodJobs],providers:[{provide:Api,useValue:{load:async()=>response}}]});
  });
  async function render() {
    const f=TestBed.createComponent(EodJobs);f.detectChanges();await f.whenStable();f.detectChanges();return f;
  }
  it('distinguishes an empty coordinator from unavailable status',async()=>{
    const f=await render();expect(f.nativeElement.textContent).toContain('No EOD jobs');
    response={status:503,body:{availability:'UNAVAILABLE'}};
    await f.componentInstance.refresh();f.detectChanges();
    expect(f.nativeElement.textContent).toContain('EOD job status unavailable');
    expect(f.nativeElement.textContent).not.toContain('No EOD jobs');
  });
  it('shows W0 coverage and integrity without claiming pricing',async()=>{
    response.body.jobs=[{job_id:'job',bundle_id:'bundle',status:'W0_VALIDATED',clusterEpoch:'epoch',valuationTime:'date',
      cut:{sessionDate:'date',consensusSequence:'42',priceSnapshotVersion:'1',cutSha256:'hash'},
      resultIntegrity:'VERIFIED',selectedW0Result:true,profile:{adapter:'alex-w0-local-v1'},
      coverage:{itemCount:2},attempts:[{attempt_id:'attempt',status:'W0_VALIDATED',error:null}]}];
    const f=await render();expect(f.nativeElement.textContent).toContain('W0 outcomes validated');
    expect(f.nativeElement.textContent).toContain('Pricing unavailable');
    expect(f.nativeElement.textContent).toContain('VERIFIED');
    expect(f.nativeElement.textContent).toContain('Current validated W0 result');
  });
  it('labels transport and execution states distinctly',async()=>{
    const f=await render();
    expect(f.componentInstance.label('QUEUED')).toBe('Pending');
    expect(f.componentInstance.label('RUNNING')).toContain('awaiting result');
    expect(f.componentInstance.label('FAILED')).toBe('Failed');
    expect(f.componentInstance.label('MOCK_COMPLETE')).toBe('Mock transport complete');
  });
});
