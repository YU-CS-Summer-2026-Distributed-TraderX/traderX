import {exec,execFile} from 'node:child_process';
// Background reads (the provenance panel's per-cut `gcloud storage cat`, ~6 s each) take their own
// lane: they start only when no foreground read is waiting, and hold at most limit-1 slots, so an
// operator's read always has one. In one FIFO a cold EOD page queued ~10 gcloud processes ahead of
// the chain's sequential reads and pushed it past the load balancer's 60 s timeout.
export function createCliRunner({limit=2,maxQueued=64,execImpl=exec,execFileImpl=execFile}={}) {
  if(!Number.isInteger(limit)||limit<1||limit>3)throw Error('CLI concurrency must be 1, 2 or 3.');
  const bgLimit=Math.max(1,limit-1);
  let active=0,bgActive=0;const queue=[],bgQueue=[];
  function start(job,bg){active++;if(bg)bgActive++;
    const done=(error,stdout)=>{active--;if(bg)bgActive--;error?job.reject(error):job.resolve(stdout);pump();};
    try{job.run(done);}catch(error){done(error);}
  }
  function pump(){
    while(active<limit&&queue.length)start(queue.shift(),false);
    while(active<limit&&!queue.length&&bgQueue.length&&bgActive<bgLimit)start(bgQueue.shift(),true);
  }
  function schedule(run,bg=false){return new Promise((resolve,reject)=>{
    const q=bg?bgQueue:queue;
    if(q.length>=maxQueued)return reject(Error('CLI read queue is full.'));
    q.push({run,resolve,reject});pump();
  });}
  return {shell:(command,options)=>schedule(done=>execImpl(command,options,done)),
    args:(command,args,options)=>schedule(done=>execFileImpl(command,args,options,done)),
    background:(command,options)=>schedule(done=>execImpl(command,options,done),true),
    stats:()=>({limit,active,queued:queue.length,bgActive,bgQueued:bgQueue.length,maxQueued})};
}
