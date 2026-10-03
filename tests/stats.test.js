// node tests/stats.test.js
const S = require('../stats.js'); const assert = require('assert');
const R = a => a.map(t => ({ total: t }));
assert.strictEqual(S.fmtRatio(12, 2), '1:6.0');
assert.strictEqual(S.fmtRatio(5, 0), '\u2014');
assert.deepStrictEqual(S.running(R([7, 5, 7, 8])).map(r => r.ratio), ['1:1.0', '1:2.0', '1:1.5', '1:2.0']);
const s = S.summary(R([7, 2, 3, 4, 5, 6]));
assert.strictEqual(s.ratio, '1:6.0'); assert.strictEqual(s.perRollStr, '0.167'); assert.strictEqual(s.pct, '16.7%');
assert.strictEqual(S.summary([]).rolls, 0);
const csv = S.toCSV([{ name: 'a,"b"', rolls: [{ ts: 0, d1: 3, d2: 4, total: 7 }, { ts: 1000, d1: 1, d2: 1, total: 2, corrected: true }] }]).trim().split('\r\n');
assert.strictEqual(csv.length, 3); assert(csv[1].startsWith('"a,""b""",1,'));
console.log('stats tests OK');
