// Order-preserving JSON for the OpenAPI tools. A plain JS object reorders integer-like keys ("400" before
// "403"), which would break byte-equality with docs/api/openapi-v1.json, so objects are Maps here.
// The writer imitates Jackson's DefaultPrettyPrinter (object indenter = 2 spaces + LF), which is how
// OpenApiPathsContractTest writes the document:  "k" : v  one member per line, empty object `{ }`,
// arrays inline `[ a, b ]`, empty array `[ ]`.

export function parseJson(text) {
  let i = 0;
  const ws = () => { while (i < text.length && ' \t\r\n'.includes(text[i])) i++; };
  const value = () => {
    ws();
    const c = text[i];
    if (c === '{') {
      i++; const m = new Map(); ws();
      if (text[i] === '}') { i++; return m; }
      for (;;) {
        ws(); const k = string(); ws();
        if (text[i++] !== ':') throw new Error('expected : at ' + i);
        m.set(k, value()); ws();
        const n = text[i++];
        if (n === '}') return m;
        if (n !== ',') throw new Error('expected , or } at ' + i);
      }
    }
    if (c === '[') {
      i++; const a = []; ws();
      if (text[i] === ']') { i++; return a; }
      for (;;) {
        a.push(value()); ws();
        const n = text[i++];
        if (n === ']') return a;
        if (n !== ',') throw new Error('expected , or ] at ' + i);
      }
    }
    if (c === '"') return string();
    const lit = /^(?:true|false|null|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(text.slice(i, i + 40));
    if (!lit) throw new Error('bad token at ' + i);
    i += lit[0].length;
    return JSON.parse(lit[0]);
  };
  const string = () => {
    const start = i++;
    while (text[i] !== '"') i += text.charCodeAt(i) === 92 ? 2 : 1;
    i++;
    return JSON.parse(text.slice(start, i));
  };
  const v = value(); ws();
  if (i !== text.length) throw new Error('trailing content at ' + i);
  return v;
}

export function writeJson(v, depth = 0) {
  if (v === null) return 'null';
  if (Array.isArray(v)) return v.length === 0 ? '[ ]' : '[ ' + v.map((x) => writeJson(x, depth)).join(', ') + ' ]';
  if (v instanceof Map) {
    if (v.size === 0) return '{ }';
    const pad = '  '.repeat(depth + 1);
    return '{\n' + [...v].map(([k, x]) => pad + JSON.stringify(k) + ' : ' + writeJson(x, depth + 1)).join(',\n')
      + '\n' + '  '.repeat(depth) + '}';
  }
  return JSON.stringify(v);
}

export const toDocument = (doc) => writeJson(doc) + '\n';
