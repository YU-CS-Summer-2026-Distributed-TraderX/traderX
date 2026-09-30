import {deskOrderAction} from './desk-orders.mjs';
// Local demo identities: usernames are public selectors, not trader authentication.
// Admin capability is granted by a server password check and an HttpOnly session cookie.
import {randomBytes} from 'node:crypto';
import {existsSync,readFileSync,writeFileSync,renameSync,mkdirSync} from 'node:fs';
import {dirname} from 'node:path';
export function createDeskSessions({file,checkAdmin,upstream,enableAccount,now=Date.now}) {
  const users=file && existsSync(file)?JSON.parse(readFileSync(file,'utf8')):{};
  const sessions=new Map();
  let queue=Promise.resolve();
  function save(){if(file){mkdirSync(dirname(file),{recursive:true,mode:0o700});writeFileSync(file+'.tmp',JSON.stringify(users),{mode:0o600});renameSync(file+'.tmp',file);}}
  function session(req){const token=(req.headers.cookie??'').split(';').map(x=>x.trim()).find(x=>x.startsWith('tx_desk='))?.slice(8);const s=sessions.get(token);return s&&s.expires>now()?s:null;}
  function view(s){const u=users[s.key];return {id:s.key,name:u.name,roles:s.admin?['trader','admin']:['trader'],accounts:u.accounts};}
  function cookie(value,maxAge){return `tx_desk=${value}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${maxAge}`;}
  async function handle(req,path,body={}) {
    const s=session(req);
    if(path==='/desk-api/session' && req.method==='GET')return s?{status:200,body:view(s)}:{status:401,body:{error:'Sign in to your workspace.'}};
    if(path==='/desk-api/logout' && req.method==='POST'){for(const [t,v]of sessions)if(v===s)sessions.delete(t);return {status:200,body:{ok:true},cookie:cookie('',0)};}
    if(path==='/desk-api/login' && req.method==='POST') {
      const name=String(body.username??'').trim();
      if(!/^[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,47}$/.test(name))return {status:400,body:{error:'Use 1–48 letters, numbers, spaces, dots, underscores or hyphens.'}};
      const admin=!!body.adminPassword;
      if(admin&&!checkAdmin(body.adminPassword))return {status:401,body:{error:'Incorrect admin password.'}};
      const key='user:'+name.toLowerCase();
      if(!Object.hasOwn(users,key)){users[key]={name,accounts:[],requests:{}};save();}
      // Migrate existing demo workspaces before opening a trading session. The stable group is
      // their first account ID, persisted independently of future account-list changes.
      const u=users[key];
      if(u.accounts.length){
        u.selfMatchGroup??=u.accounts[0];save();
        try {for(const accountId of u.accounts)if(!await enableAccount(accountId,u.selfMatchGroup,false))
          return {status:503,body:{error:'Account protection is unavailable. Workspace was not opened.'}};
        }catch{return {status:503,body:{error:'Account protection is unavailable. Workspace was not opened.'}};}
      }
      const token=randomBytes(32).toString('hex'),record={key,admin,expires:now()+8*3600000};
      // Replace the session presented by this browser. An admin login never persists on the user.
      for(const [t,v]of sessions)if(v===s)sessions.delete(t);
      sessions.set(token,record);
      return {status:200,body:view(record),cookie:cookie(token,28800),admin};
    }
    if(!s)return {status:401,body:{error:'Sign in to your workspace.'}};
    if(path==='/desk-api/accounts' && req.method==='POST') {
      const name=String(body.displayName??'').trim(),key=body.requestId;
      if(!name||name.length>50||typeof key!=='string'||!/^[a-zA-Z0-9-]{16,64}$/.test(key))return {status:400,body:{error:'An account name (up to 50 characters) and request ID are required.'}};
      // Serialize creation and persist intent before sending. Retrying a timed-out create must not
      // silently allocate a second SQL account. A partial admission can be retried by its own id.
      const run=queue.then(async()=>{
        const u=users[s.key];u.requests??={};
        if(u.requests[key]){
          const prior=u.requests[key];if(prior.displayName!==name)return {status:409,body:{error:'Request ID already used for another account name.'}};
          return {status:prior.status,body:prior.body.user?{...prior.body,user:view(s)}:prior.body};
        }
        u.requests[key]={displayName:name,status:409,body:{error:'Creation outcome unknown. Inspect the account directory before trying again.'}};save();
        let r;
        try{r=await upstream('/account-service/account/',{method:'POST',body:{displayName:name}});}catch{return {status:u.requests[key].status,body:u.requests[key].body};}
        if(r.status!==200||!Number.isSafeInteger(r.body?.id)||r.body.id<=0)return {status:u.requests[key].status,body:u.requests[key].body};
        const id=r.body.id;u.accounts.push(id);u.selfMatchGroup??=id;save();
        let enabled=false;try{enabled=await enableAccount(id,u.selfMatchGroup);}catch{}
        const result={status:201,body:{user:view(s),account:r.body,enabled,error:enabled?null:'Account created. Engine admission failed; use Retry admission.'}};
        u.requests[key]={...result,displayName:name};save();return result;
      });queue=run.catch(()=>{});return run;
    }
    if(path==='/desk-api/accounts/admit' && req.method==='POST') {
      if(!users[s.key].accounts.includes(body.accountId))return {status:403,body:{error:'This account does not belong to this workspace.'}};
      let enabled=false;try{enabled=await enableAccount(body.accountId,users[s.key].selfMatchGroup);}catch{}
      return {status:enabled?200:503,body:{enabled,error:enabled?null:'Engine admission is unavailable. Try again when the rig is ready.'}};
    }
    if(path.startsWith('/desk-api/orders/') && req.method==='POST') return deskOrderAction(upstream,users[s.key].accounts,path.slice('/desk-api/orders/'.length),body);
    return {status:404,body:{error:'Unknown desk endpoint.'}};
  }
  return {handle,session, owns:(req,account)=>{const s=session(req);return !!s && users[s.key].accounts.includes(account);}};
}
