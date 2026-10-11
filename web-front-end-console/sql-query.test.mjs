import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

function queryHarness() {
  const source=fs.readFileSync(new URL('./server.mjs',import.meta.url),'utf8');
  const start=source.indexOf('const sqlQuery =');
  const end=source.indexOf('\n\nfunction eodChain',start);
  assert.ok(start>=0 && end>start);
  const calls=[];
  const sandbox={NS:'traderx',podByLabel:label=>{assert.equal(label,'app=eod-price-db');return 'db-proof-pod';},
    execFileSync:(...args)=>{calls.push(args);return Buffer.from('7\n');}};
  vm.runInNewContext(source.slice(start,end)+'\nglobalThis.run=sqlQuery;',sandbox);
  return {run:sandbox.run,calls};
}

test('SQL bridge reads credentials from the database runtime environment',()=>{
  const {run,calls}=queryHarness();assert.equal(run('SELECT 7'),'7');
  const [command,args,options]=calls[0];assert.equal(command,'kubectl');
  assert.deepEqual(Array.from(args.slice(0,8)),['-n','traderx','exec','db-proof-pod','--','sh','-ec',
    'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -N -B -e "$1"']);
  assert.equal(options.shell,undefined);assert.equal(options.timeout,25000);
  assert.ok(!args.join(' ').includes('-ptraderx'));
});

test('query text is a positional argument rather than host shell code',()=>{
  const {run,calls}=queryHarness();const query='SELECT "$(touch /tmp/must-not-execute)";';
  run(query);assert.equal(calls[0][1].at(-1),query);assert.equal(calls[0][1].at(-2),'sh');
  assert.equal(calls[0][2].shell,undefined);
});
