/* Pure SRR logic (no DOM). SRR here = rolls per seven (e.g. 1:6.0). Random expectation is 1:6.0. */
(function (root) {
  'use strict';
  function fmtRatio(rolls, sevens) {            // "1:6.0"
    if (!sevens) return rolls ? '\u2014' : '\u2014';
    return '1:' + (rolls / sevens).toFixed(1);
  }
  function fmtPerRoll(rolls, sevens) {          // "0.167"
    if (!rolls) return '\u2014';
    return (sevens / rolls).toFixed(3);
  }
  function fmtPct(rolls, sevens) { return rolls ? (100 * sevens / rolls).toFixed(1) + '%' : '\u2014'; }
  // rolls: array of {total} in chronological order -> array of running stats after each roll
  function running(rolls) {
    var sevens = 0;
    return rolls.map(function (r, i) {
      if (r.total === 7) sevens++;
      var n = i + 1;
      return { n: n, sevens: sevens, ratio: fmtRatio(n, sevens), perRoll: sevens / n, perRollStr: fmtPerRoll(n, sevens), srr: sevens ? n / sevens : null };
    });
  }
  function summary(rolls) {
    var sevens = rolls.filter(function (r) { return r.total === 7; }).length, n = rolls.length;
    return { rolls: n, sevens: sevens, ratio: fmtRatio(n, sevens), perRoll: n ? sevens / n : 0, perRollStr: fmtPerRoll(n, sevens), pct: fmtPct(n, sevens) };
  }
  function csvEscape(v) { v = (v === null || v === undefined) ? '' : String(v); return /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v; }
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function localStr(ts) { var d = new Date(ts); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()); }
  function toCSV(sessions) {                    // sessions: [{name, startedAt, rolls:[...]}]
    var head = ['session', 'roll_no', 'timestamp_iso', 'local_time', 'die1', 'die2', 'total', 'is_seven', 'sevens_so_far', 'running_srr_rolls_per_seven', 'running_sevens_per_roll', 'user_corrected', 'detected_die1', 'detected_die2', 'detect_confidence', 'has_photo'];
    var lines = [head.join(',')];
    sessions.forEach(function (s) {
      var run = running(s.rolls);
      s.rolls.forEach(function (r, i) {
        var d = r.detected || {};
        lines.push([s.name, i + 1, new Date(r.ts).toISOString(), localStr(r.ts), r.d1 == null ? '' : r.d1, r.d2 == null ? '' : r.d2, r.total, r.total === 7 ? 1 : 0, run[i].sevens,
          run[i].srr ? run[i].srr.toFixed(2) : '', run[i].perRoll.toFixed(4), r.corrected ? 1 : 0,
          d.d1 == null ? '' : d.d1, d.d2 == null ? '' : d.d2, d.confidence || '', r.photoId ? 1 : 0].map(csvEscape).join(','));
      });
    });
    return lines.join('\r\n') + '\r\n';
  }
  var api = { fmtRatio: fmtRatio, fmtPerRoll: fmtPerRoll, fmtPct: fmtPct, running: running, summary: summary, toCSV: toCSV };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.SRRStats = api;
})(typeof self !== 'undefined' ? self : this);
