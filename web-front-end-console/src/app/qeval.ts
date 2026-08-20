/**
 * A deliberately small q evaluator for the captured store — enough to run the `select` statements
 * txstore.q actually contains, and nothing else.
 *
 * The rule that matters here is REFUSE RATHER THAN APPROXIMATE. There is no q process behind this
 * page; this is JavaScript reading rows the bridge already fetched. So anything outside the subset
 * throws with the reason, and the panel shows the reason. A q box that quietly returns a plausible
 * number for a statement it did not really understand would be the worst thing on this console:
 * every other panel here is careful about the difference between a measurement and a guess, and an
 * evaluator that guesses would launder one into the other.
 *
 * Supported, which is exactly the shape of .tx.fills / .tx.orders and their obvious variations:
 *
 *   [.name:{[] ] [0!] select <cols> [by <cols>] from <txTrade|txOrder> [where <conds>] [}]
 *
 *   column exprs   count i · sum|avg|min|max|first|last <col> · (sum <a>*<b>)%sum <c> · <col>
 *   conditions     <col>=<value> · <col><>|<|>|<=|>= <value> · <col> like "PAT*"
 *   values         123 · 1.5 · `SYM · "text"
 */

export interface QResult { columns: string[]; rows: (string | number)[][]; }

type Row = Record<string, string | number>;

interface Agg { name: string; kind: 'count' | 'sum' | 'avg' | 'min' | 'max' | 'first' | 'last' | 'wavg' | 'col';
  col?: string; num?: string; den?: string; }

/** Split on commas that are not inside parentheses — `(sum px*qty)%sum qty` contains one. */
function splitTop(s: string): string[] {
  const out: string[] = [];
  let depth = 0, start = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '(') depth++;
    else if (c === ')') depth--;
    else if (c === ',' && depth === 0) { out.push(s.slice(start, i)); start = i + 1; }
  }
  out.push(s.slice(start));
  return out.map(x => x.trim()).filter(Boolean);
}

function parseAgg(raw: string): Agg {
  // `name:expr`, but not the `:` of a `.tx.foo:` — that is stripped before we get here.
  const named = /^([A-Za-z_]\w*)\s*:\s*([\s\S]+)$/.exec(raw);
  const name = named ? named[1] : raw.trim();
  const expr = (named ? named[2] : raw).trim();

  let m = /^\(\s*sum\s+(\w+)\s*\*\s*(\w+)\s*\)\s*%\s*sum\s+(\w+)$/.exec(expr);
  if (m) return { name, kind: 'wavg', num: m[1], col: m[2], den: m[3] };

  m = /^count\s+i$/.exec(expr);
  if (m) return { name, kind: 'count' };

  m = /^(sum|avg|min|max|first|last)\s+(\w+)$/.exec(expr);
  if (m) return { name, kind: m[1] as Agg['kind'], col: m[2] };

  m = /^(\w+)$/.exec(expr);
  if (m) return { name, kind: 'col', col: m[1] };

  throw new Error(`unsupported column expression: ${expr}`);
}

interface Cond { col: string; op: string; value: string | number; }

function parseValue(v: string): string | number {
  v = v.trim();
  if (v.startsWith('`')) return v.slice(1);
  if (/^"(.*)"$/.test(v)) return v.slice(1, -1);
  const n = Number(v);
  if (!Number.isNaN(n) && v !== '') return n;
  throw new Error(`unsupported value: ${v} (expected a number, \`symbol or "string")`);
}

function parseWhere(s: string): Cond[] {
  return splitTop(s).map(c => {
    let m = /^(\w+)\s+like\s+("[^"]*")$/.exec(c.trim());
    if (m) return { col: m[1], op: 'like', value: parseValue(m[2]) };
    m = /^(\w+)\s*(<>|<=|>=|=|<|>)\s*(.+)$/.exec(c.trim());
    if (m) return { col: m[1], op: m[2], value: parseValue(m[3]) };
    throw new Error(`unsupported condition: ${c.trim()}`);
  });
}

const passes = (r: Row, c: Cond): boolean => {
  const v = r[c.col];
  if (v === undefined) throw new Error(`unknown column in where: ${c.col}`);
  switch (c.op) {
    case '=': return String(v) === String(c.value);
    case '<>': return String(v) !== String(c.value);
    case '<': return Number(v) < Number(c.value);
    case '>': return Number(v) > Number(c.value);
    case '<=': return Number(v) <= Number(c.value);
    case '>=': return Number(v) >= Number(c.value);
    // q's `like` is glob, not SQL: * is the wildcard and ? is a single character.
    case 'like': {
      const pat = '^' + String(c.value).replace(/[.+^${}()|[\]\\]/g, '\\$&')
        .replace(/\*/g, '.*').replace(/\?/g, '.') + '$';
      return new RegExp(pat, 'i').test(String(v));
    }
    default: throw new Error(`unsupported operator: ${c.op}`);
  }
};

function apply(a: Agg, rows: Row[]): string | number {
  const nums = (c: string) => rows.map(r => Number(r[c] ?? 0));
  switch (a.kind) {
    case 'count': return rows.length;
    case 'sum': return +nums(a.col!).reduce((x, y) => x + y, 0).toFixed(6);
    case 'avg': return rows.length ? +(nums(a.col!).reduce((x, y) => x + y, 0) / rows.length).toFixed(6) : 0;
    case 'min': return Math.min(...nums(a.col!));
    case 'max': return Math.max(...nums(a.col!));
    case 'first': return rows[0]?.[a.col!] ?? '';
    case 'last': return rows[rows.length - 1]?.[a.col!] ?? '';
    case 'wavg': {
      const den = nums(a.den!).reduce((x, y) => x + y, 0);
      if (!den) return 0;
      const num = rows.reduce((s, r) => s + Number(r[a.num!] ?? 0) * Number(r[a.col!] ?? 0), 0);
      return +(num / den).toFixed(6);
    }
    case 'col': return rows[0]?.[a.col!] ?? '';
  }
}

export function runQ(src: string, tables: Record<string, Row[]>): QResult {
  let s = src.trim();
  if (!s) throw new Error('nothing to run');

  // `.tx.fills:{[] … }` — strip the definition wrapper and evaluate the body.
  const fn = /^[.\w]+\s*:\s*\{\s*\[\s*\]\s*([\s\S]*?)\}\s*;?\s*$/.exec(s);
  if (fn) s = fn[1].trim();
  // 0! unkeys a keyed table; every result here is already a plain list of rows.
  s = s.replace(/^0\s*!\s*/, '').replace(/;\s*$/, '').trim();

  const m = /^select\s+([\s\S]+?)(?:\s+by\s+([\w\s,]+?))?\s+from\s+([\w.]+)(?:\s+where\s+([\s\S]+))?$/i.exec(s);
  if (!m) {
    throw new Error('only `select … [by …] from <table> [where …]` is supported here — '
      + 'for anything else run it in a real q session against the capture directory');
  }
  const [, colsSrc, bySrc, tableName, whereSrc] = m;

  const table = tables[tableName];
  if (!table) {
    throw new Error(`unknown table: ${tableName} (this store has ${Object.keys(tables).join(' and ')})`);
  }

  const aggs = splitTop(colsSrc).map(parseAgg);
  const byCols = bySrc ? bySrc.split(',').map(c => c.trim()).filter(Boolean) : [];
  const conds = whereSrc ? parseWhere(whereSrc) : [];

  for (const c of [...byCols, ...aggs.map(a => a.col).filter(Boolean) as string[]]) {
    if (table.length && table[0][c] === undefined) {
      throw new Error(`unknown column: ${c} (${tableName} has ${Object.keys(table[0]).join(', ')})`);
    }
  }

  const filtered = conds.length ? table.filter(r => conds.every(c => passes(r, c))) : table;

  if (!byCols.length) {
    return { columns: aggs.map(a => a.name), rows: [aggs.map(a => apply(a, filtered))] };
  }
  // An explicit escape, not a literal control byte: an invisible separator in the source is
  // invisible to grep and to the next reader, and joining/splitting on '' would shatter every
  // multi-character group value into one column per character.
  const SEP = '\u0001';
  const groups = new Map<string, Row[]>();
  for (const r of filtered) {
    const k = byCols.map(c => String(r[c])).join(SEP);
    groups.set(k, [...(groups.get(k) ?? []), r]);
  }
  return {
    columns: [...byCols, ...aggs.map(a => a.name)],
    rows: [...groups.entries()].map(([k, rs]) => [...k.split(SEP), ...aggs.map(a => apply(a, rs))]),
  };
}
