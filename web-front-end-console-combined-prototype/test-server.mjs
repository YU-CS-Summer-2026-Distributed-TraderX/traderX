import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';

// Real socket test of the disconnect that crashed server.mjs while connecting the desk.
test('console survives a reset WebSocket and blocks cloud reads in local mode',async()=>{
  const edge=http.createServer((req,res)=>res.end('{}'));
  edge.on('upgrade',(req,socket)=>{
    socket.on('error',()=>{});
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n');
    setTimeout(()=>socket.resetAndDestroy(),20);
  });
  edge.listen(0,'127.0.0.1');await once(edge,'listening');
  const portFinder=net.createServer();portFinder.listen(0,'127.0.0.1');await once(portFinder,'listening');
  const port=portFinder.address().port;await new Promise(r=>portFinder.close(r));
  const child=spawn(process.execPath,[fileURLToPath(new URL('../web-front-end-console/server.mjs',import.meta.url))],{
    env:{...process.env,PORT:String(port),HOST:'127.0.0.1',LOCAL_ONLY:'1',EDGE_PROXY:`127.0.0.1:${edge.address().port}`,AUTH_MASTER_SECRET:''},stdio:['ignore','pipe','pipe']});
  let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);
  try {
    for(let i=0;i<50&&!output.includes('[console]');i++) await new Promise(r=>setTimeout(r,50));
    assert.ok(output.includes('[console]'),output);
    for(let i=0;i<3;i++) {
      await new Promise((resolve,reject)=>{
        const socket=net.connect(port,'127.0.0.1',()=>socket.write('GET /nats-ws HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n'));
        socket.on('error',()=>{});socket.on('data',()=>socket.resetAndDestroy());socket.on('close',resolve);socket.setTimeout(2000,()=>{socket.destroy();reject(Error('socket timeout'));});
      });
    }
    await new Promise(r=>setTimeout(r,50));
    assert.equal(child.exitCode,null,output);
    assert.equal((await fetch(`http://127.0.0.1:${port}/healthz`)).status,200);
    const denied=await fetch(`http://127.0.0.1:${port}/gcs/list`);
    assert.equal(denied.status,503);assert.match((await denied.json()).error,/disabled/);
  } finally {child.kill();edge.closeAllConnections();edge.close();}
});
