import {Injectable, inject, signal} from '@angular/core';
import {CONNECTED_RIG} from './rig';
@Injectable({providedIn:'root'})
export class DeskProfile {
  readonly passwordRequired=signal(true);
  readonly demoUrl=signal('');
  constructor(){
    if(!inject(CONNECTED_RIG)){this.passwordRequired.set(false);this.demoUrl.set('http://127.0.0.1:4321');return;}
    fetch('/desk-api/profile',{cache:'no-store'}).then(async r=>{
      if(!r.ok)return;const p=await r.json();
      if(typeof p.passwordRequired==='boolean')this.passwordRequired.set(p.passwordRequired);
      if(typeof p.demoConsoleUrl==='string'){
        const u=new URL(p.demoConsoleUrl);if(u.protocol==='https:' || (u.protocol==='http:' && ['localhost','127.0.0.1'].includes(u.hostname)))this.demoUrl.set(u.href);
      }
    }).catch(()=>{});
  }
}
