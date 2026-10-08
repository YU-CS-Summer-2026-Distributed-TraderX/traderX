// Offline reader bridge. Only the pinned declaration expression, tape load, and print decode run.
const fs = require('fs');
const vm = require('vm');
const crypto = require('crypto');
const path = require('path');
const Module = require('module');
const PINS = {
  "main.js": "e8ed3d5b6a38aa12ff7e2a65b18c9a0f70544140221502249ef078f2f55e7a7f",
  "taq-replay.js": "e216b9328f22053550b17de201844adb453a7fdfc6967f2209be39cc73802691",
  "print-replay.js": "59034d3774fa6bdaeae849a022a21995a675b66a463ff554c5a2981eab42b914"
};
const READER_BYTES = {"main.js": 43299, "taq-replay.js": 23613, "print-replay.js": 24938};
const request = JSON.parse(fs.readFileSync(0, 'utf8'));
console.log = console.warn = () => {}; // producer detail is returned privately, never mixed into JSON
const result = {readerInputs:{}};
function pinned(name) {
  const p = path.join(request.publisherRoot, 'src', name);
  const infoRecord = {path:p,state:'invalid',expectedSha256:PINS[name],expectedBytes:READER_BYTES[name]};
  result.readerInputs[name] = infoRecord;
  const fd = fs.openSync(p, fs.constants.O_RDONLY | fs.constants.O_NONBLOCK);
  let bytes;
  try {
    const info = fs.fstatSync(fd);
    infoRecord.observedBytes = info.size;
    if (!info.isFile() || info.size !== READER_BYTES[name]) { throw Error(`unsupported reader size/type: ${name}`); }
    const buffer = Buffer.alloc(READER_BYTES[name] + 1);
    let length = 0;
    while (length < buffer.length) {
      const count = fs.readSync(fd, buffer, length, buffer.length - length, null);
      if (!count) { break; }
      length += count;
    }
    bytes = buffer.subarray(0, length);
  } finally { fs.closeSync(fd); }
  const hash = crypto.createHash('sha256').update(bytes).digest('hex');
  infoRecord.sha256 = hash;
  if (hash !== PINS[name]) { throw Error(`unsupported reader source: ${name}`); }
  infoRecord.state = 'valid';
  return {path:p,sha256:hash,bytes};
}
function compile(reader) {
  const loaded = new Module(reader.path, module);
  loaded.filename = reader.path;
  loaded.paths = Module._nodeModulePaths(path.dirname(reader.path));
  loaded._compile(reader.bytes.toString('utf8'), reader.path);
  return loaded.exports;
}
try {
  const main = pinned('main.js');
  const pattern = /const TICKERS = \(process\.env\.PRICE_TICKERS \|\| '([^'\\\n]*)'\)\s*\.split\(','\)\s*\.map\(\(ticker\) => ticker\.trim\(\)\.toUpperCase\(\)\)\s*\.filter\(Boolean\);/;
  const match = main.bytes.toString('utf8').match(pattern);
  if (!match) { throw Error('unsupported PRICE_TICKERS declaration form'); }
  const expression = match[0].replace('const TICKERS = ', '').replace(/;$/, '');
  const env = request.config;
  const effective = vm.runInNewContext(expression, {process:{env}}, {timeout:1000});
  const defaultSelected = !env.PRICE_TICKERS;
  result.declaration = {state:'valid',symbols:Array.from(effective),defaultSelected,
    defaultLiteral:match[1],rawTokens:(defaultSelected ? match[1] : env.PRICE_TICKERS).split(','),
    reader:{path:main.path,sha256:main.sha256},transform:'actual split, trim, toUpperCase, filter(Boolean)'};
} catch (e) { result.declaration = {state:'invalid',reason:String(e.message)}; }
if (request.tapeSnapshot) {
  try {
    const reader = pinned('taq-replay.js');
    process.env.TAQ_REPLAY_EXTRACT_PATH = request.tapeSnapshot;
    process.env.REPLAY_EPOCH_START_MS = '1'; // validation sentinel only; no replay position queried
    const tape = compile(reader);
    const extract = tape.load(1);
    if (!extract) { throw Error(tape.state.reason + ': ' + tape.state.error); }
    result.tape = {state:'valid',symbols:Object.keys(extract.prices),days:extract.days.map(d=>d.date),
      windowSeconds:extract.windowSeconds,sessionSeconds:extract.sessionSeconds,
      reader:{path:reader.path,sha256:reader.sha256},validationEpoch:'synthetic sentinel 1; no clock coverage claim'};
  } catch (e) { result.tape = {state:'invalid',reason:String(e.message)}; }
}
if (request.sampleSnapshot) {
  try {
    const reader = pinned('print-replay.js');
    const sample = compile(reader).decode(fs.readFileSync(request.sampleSnapshot));
    result.print = {state:'valid',symbols:sample.symbols,days:sample.days,
      windowSeconds:sample.windowSeconds,sessionSeconds:sample.sessionSeconds,
      reader:{path:reader.path,sha256:reader.sha256},semantics:'actual decoder; no load/enable/start/step/order submission'};
  } catch (e) { result.print = {state:'invalid',reason:String(e.message)}; }
}
process.stdout.write(JSON.stringify(result));
