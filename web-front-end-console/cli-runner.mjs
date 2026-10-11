import {exec,execFile} from 'node:child_process';
export function createCliRunner({limit=2,maxQueued=64,execImpl=exec,execFileImpl=execFile}={}) {
  if(!Number.isInteger(limit)||limit<1||limit>3)throw Error('CLI concurrency must be 1, 2 or 3.');
  let active=0;const queue=[];
  function pump(){while(active<limit&&queue.length){const job=queue.shift();active++;
    const done=(error,stdout)=>{active--;error?job.reject(error):job.resolve(stdout);pump();};
    try{job.run(done);}catch(error){done(error);}
  }}
  function schedule(run){return new Promise((resolve,reject)=>{
    if(queue.length>=maxQueued)return reject(Error('CLI read queue is full.'));
    queue.push({run,resolve,reject});pump();
  });}
  return {shell:(command,options)=>schedule(done=>execImpl(command,options,done)),
    args:(command,args,options)=>schedule(done=>execFileImpl(command,args,options,done)),
    stats:()=>({limit,active,queued:queue.length,maxQueued})};
}
