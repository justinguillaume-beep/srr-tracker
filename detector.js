/* Dice pip detector — plain JS, no dependencies.
 * Input : {data: Uint8ClampedArray RGBA, width, height}  (ImageData-like)
 * Output: {ok, total, counts:[a,b], confidence:'high'|'medium'|'low', cost, margin, pips:[{x,y,r,die}], ...}
 *
 * Pipeline
 *  1. downscale (area average) to <=640px, grayscale, 3x3 blur
 *  2. for both polarities (dark pips on light dice / light pips on coloured dice) and several
 *     structuring-element sizes: morphological top-hat (closing-img  or  img-opening) isolates
 *     small blobs of "pip size"; threshold -> connected components
 *  3. keep blobs that are round (aspect + fill ratio), not on the border, and consistent in size
 *  4. split the pips into exactly two dice by brute-force over all 2-partitions, scoring each
 *     group against standard die faces 1-6 (rotation free, shared die size) + min-distance rule
 *  5. choose the candidate (polarity, scale) that explains the most pips with a good fit.
 */
(function (root) {
  'use strict';

  var RADII = [2, 3, 4, 6, 8, 11, 15, 20, 27];
  var A = 0.27;                       // pip offset from die centre, in die sides
  var TEMPLATES = {
    1: [[0, 0]],
    2: [[-A, -A], [A, A]],
    3: [[-A, -A], [0, 0], [A, A]],
    4: [[-A, -A], [A, -A], [-A, A], [A, A]],
    5: [[-A, -A], [A, -A], [0, 0], [-A, A], [A, A]],
    6: [[-A, -A], [A, -A], [-A, 0], [A, 0], [-A, A], [A, A]]
  };
  var RATIOS = [3.8, 4.2, 4.6, 5.0, 5.4, 5.8, 6.3];   // die side / pip diameter
  var MIN_SEP = 0.40;                // pips on different dice are >= this many sides apart
  var THETAS = []; for (var t = 0; t < 180; t += 3) THETAS.push(t * Math.PI / 180);

  // ---------- image prep ----------
  function prep(img, maxDim) {
    var W = img.width, H = img.height, d = img.data;
    var f = Math.max(W, H) / maxDim; if (f < 1) f = 1;
    var w = Math.max(1, Math.round(W / f)), h = Math.max(1, Math.round(H / f));
    var g = new Float32Array(w * h), cr = new Uint8Array(w * h), cg = new Uint8Array(w * h), cb = new Uint8Array(w * h);
    for (var y = 0; y < h; y++) {
      var y0 = Math.floor(y * H / h), y1 = Math.max(y0 + 1, Math.floor((y + 1) * H / h));
      for (var x = 0; x < w; x++) {
        var x0 = Math.floor(x * W / w), x1 = Math.max(x0 + 1, Math.floor((x + 1) * W / w));
        var s = 0, n = 0, sr = 0, sg = 0, sb = 0;
        for (var yy = y0; yy < y1; yy++) for (var xx = x0; xx < x1; xx++) {
          var i = (yy * W + xx) * 4;
          sr += d[i]; sg += d[i + 1]; sb += d[i + 2]; n++;
        }
        s = 0.299 * sr + 0.587 * sg + 0.114 * sb;
        g[y * w + x] = s / n; cr[y * w + x] = sr / n; cg[y * w + x] = sg / n; cb[y * w + x] = sb / n;
      }
    }
    // 3x3 box blur
    var b = new Uint8Array(w * h);
    for (var y2 = 0; y2 < h; y2++) for (var x2 = 0; x2 < w; x2++) {
      var s2 = 0, n2 = 0;
      for (var dy = -1; dy <= 1; dy++) { var py = y2 + dy; if (py < 0 || py >= h) continue;
        for (var dx = -1; dx <= 1; dx++) { var px = x2 + dx; if (px < 0 || px >= w) continue; s2 += g[py * w + px]; n2++; } }
      b[y2 * w + x2] = (s2 / n2 + 0.5) | 0;
    }
    function med(a) { var hh = new Uint32Array(256), k; for (k = 0; k < a.length; k += 7) hh[a[k]]++; var tot = Math.ceil(a.length / 7), acc = 0; for (k = 0; k < 256; k++) { acc += hh[k]; if (acc >= tot / 2) return k; } return 128; }
    return { gray: b, w: w, h: h, scale: W / w, cr: cr, cg: cg, cb: cb, bg: [med(cr), med(cg), med(cb)] };
  }

  // ---------- morphology (square SE, van Herk-style deque) ----------
  function morph1D(src, dst, W, H, r, horizontal, isMax) {
    var len = horizontal ? W : H, lines = horizontal ? H : W;
    var step = horizontal ? 1 : W, lineStep = horizontal ? W : 1;
    var dq = new Int32Array(len + 1);
    for (var l = 0; l < lines; l++) {
      var base = l * lineStep, head = 0, tail = 0, next = 0;
      for (var i = 0; i < len; i++) {
        var hi = Math.min(len - 1, i + r);
        while (next <= hi) {
          var v = src[base + next * step];
          if (isMax) { while (tail > head && src[base + dq[tail - 1] * step] <= v) tail--; }
          else { while (tail > head && src[base + dq[tail - 1] * step] >= v) tail--; }
          dq[tail++] = next; next++;
        }
        var lo = i - r;
        while (dq[head] < lo) head++;
        dst[base + i * step] = src[base + dq[head] * step];
      }
    }
  }
  function morph(src, W, H, r, isMax) {
    var tmp = new Uint8Array(W * H), out = new Uint8Array(W * H);
    morph1D(src, tmp, W, H, r, true, isMax);
    morph1D(tmp, out, W, H, r, false, isMax);
    return out;
  }
  function tophat(gray, W, H, r, dark) {
    var out = new Uint8Array(W * H), i;
    if (dark) { // closing - img
      var cl = morph(morph(gray, W, H, r, true), W, H, r, false);
      for (i = 0; i < out.length; i++) out[i] = cl[i] - gray[i] > 0 ? cl[i] - gray[i] : 0;
    } else {    // img - opening
      var op = morph(morph(gray, W, H, r, false), W, H, r, true);
      for (i = 0; i < out.length; i++) out[i] = gray[i] - op[i] > 0 ? gray[i] - op[i] : 0;
    }
    return out;
  }

  // ---------- blobs ----------
  function extractBlobs(th, gray, W, H, R) {
    var hist = new Uint32Array(256), i;
    for (i = 0; i < th.length; i++) hist[th[i]]++;
    var target = th.length * 0.002, acc = 0, p = 255;
    for (; p > 0; p--) { acc += hist[p]; if (acc >= target) break; }
    var thr = Math.max(24, Math.round(0.5 * p));
    var label = new Int32Array(W * H), stack = new Int32Array(W * H), blobs = [], nl = 0;
    var maxArea = Math.PI * Math.pow(2.9 * R + 2, 2) / 4 * 1.2;
    for (var s = 0; s < W * H; s++) {
      if (th[s] < thr || label[s]) continue;
      nl++; var sp = 0; stack[sp++] = s; label[s] = nl;
      var area = 0, sx = 0, sy = 0, minx = W, maxx = 0, miny = H, maxy = 0, border = false, big = false, con = 0;
      while (sp) {
        var q = stack[--sp], x = q % W, y = (q - x) / W;
        area++; sx += x; sy += y; con += th[q];
        if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y;
        if (x === 0 || y === 0 || x === W - 1 || y === H - 1) border = true;
        if (x > 0 && !label[q - 1] && th[q - 1] >= thr) { label[q - 1] = nl; stack[sp++] = q - 1; }
        if (x < W - 1 && !label[q + 1] && th[q + 1] >= thr) { label[q + 1] = nl; stack[sp++] = q + 1; }
        if (y > 0 && !label[q - W] && th[q - W] >= thr) { label[q - W] = nl; stack[sp++] = q - W; }
        if (y < H - 1 && !label[q + W] && th[q + W] >= thr) { label[q + W] = nl; stack[sp++] = q + W; }
      }
      var bw = maxx - minx + 1, bh = maxy - miny + 1;
      if (border || area < 14 || area > maxArea) continue;
      var aspect = bw / bh; if (aspect < 0.65 || aspect > 1.55) continue;
      var fill = area / (bw * bh); if (fill < 0.62 || fill > 0.93) continue;
      blobs.push({ x: sx / area, y: sy / area, area: area, d: 2 * Math.sqrt(area / Math.PI),
                   contrast: con / area, fill: fill, aspect: aspect });
    }
    return blobs;
  }

  // Sample a ring around a blob: returns {rgb, spread} (spread = p90-p10 of gray on ring)
  function ringInfo(b, pr) {
    var r = b.d / 2, rr = Math.max(r * 1.55, r + 2.2), N = 16, gs = [], R = 0, G = 0, B = 0, cnt = 0;
    var Rs = [], Gs = [], Bs = [];
    for (var k = 0; k < N; k++) {
      var a = 2 * Math.PI * k / N, x = Math.round(b.x + rr * Math.cos(a)), y = Math.round(b.y + rr * Math.sin(a));
      if (x < 0 || y < 0 || x >= pr.w || y >= pr.h) continue;
      var i = y * pr.w + x; gs.push(pr.gray[i]); Rs.push(pr.cr[i]); Gs.push(pr.cg[i]); Bs.push(pr.cb[i]);
    }
    if (gs.length < N * 0.75) return null;
    function q(a, p) { a = a.slice().sort(function (u, v) { return u - v; }); return a[Math.min(a.length - 1, Math.floor(p * a.length))]; }
    return { rgb: [q(Rs, 0.5), q(Gs, 0.5), q(Bs, 0.5)], spread: q(gs, 0.75) - q(gs, 0.25) };
  }
  function cdist(a, b) { return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])); }
  function validateOnDie(blobs, pr) {
    return blobs.filter(function (b) {
      var ri = ringInfo(b, pr); if (!ri) return false;
      b.ring = ri.rgb;
      return ri.spread < 0.5 * b.contrast && cdist(ri.rgb, pr.bg) >= 45;   // surrounded by uniform "body" that differs from the table
    });
  }

  function filterConsistent(blobs) {
    if (blobs.length < 2) return blobs;
    var best = [], i, j;
    for (i = 0; i < blobs.length; i++) {
      var grp = [];
      for (j = 0; j < blobs.length; j++) if (Math.abs(blobs[j].d / blobs[i].d - 1) <= 0.28 && (!blobs[i].ring || cdist(blobs[i].ring, blobs[j].ring) < 70)) grp.push(blobs[j]);
      if (grp.length > best.length) best = grp;
    }
    if (best.length > 12) {
      best.sort(function (a, b) { return (b.contrast * (1 - Math.abs(b.fill - 0.785))) - (a.contrast * (1 - Math.abs(a.fill - 0.785))); });
      best = best.slice(0, 12);
    }
    return best;
  }

  // ---------- dice fitting ----------
  function popcount(m) { var c = 0; while (m) { m &= m - 1; c++; } return c; }

  function subsetSSE(P, idx, s) {           // best-rotation SSE of pips idx vs face template, die side s
    var n = idx.length;
    if (n === 1) return 0;
    var cx = 0, cy = 0, i, j;
    for (i = 0; i < n; i++) { cx += P[idx[i]].x; cy += P[idx[i]].y; }
    cx /= n; cy /= n;
    var tpl = TEMPLATES[n], best = Infinity, dist = new Float64Array(n * n), used = new Uint8Array(n * 2);
    for (var ti = 0; ti < THETAS.length; ti++) {
      var c = Math.cos(THETAS[ti]), sn = Math.sin(THETAS[ti]);
      for (j = 0; j < n; j++) {
        var tx = cx + s * (tpl[j][0] * c - tpl[j][1] * sn), ty = cy + s * (tpl[j][0] * sn + tpl[j][1] * c);
        for (i = 0; i < n; i++) { var dx = P[idx[i]].x - tx, dy = P[idx[i]].y - ty; dist[i * n + j] = dx * dx + dy * dy; }
      }
      used.fill(0); var sum = 0;
      for (var k = 0; k < n; k++) {
        var bi = -1, bj = -1, bv = Infinity;
        for (i = 0; i < n; i++) if (!used[i]) for (j = 0; j < n; j++) if (!used[n + j] && dist[i * n + j] < bv) { bv = dist[i * n + j]; bi = i; bj = j; }
        used[bi] = 1; used[n + bj] = 1; sum += bv;
      }
      if (sum < best) best = sum;
    }
    return best;
  }

  function fitDice(P) {
    var n = P.length;
    if (n < 2 || n > 12) return null;
    var d = 0, i, j; for (i = 0; i < n; i++) d += P[i].d; d /= n;
    var sList = RATIOS.map(function (r) { return r * d; });
    // pairwise distances
    var D = []; for (i = 0; i < n; i++) { D.push([]); for (j = 0; j < n; j++) D[i].push(Math.hypot(P[i].x - P[j].x, P[i].y - P[j].y)); }
    var cache = {};
    function sse(mask, si) {
      var key = mask * 8 + si;
      if (cache[key] !== undefined) return cache[key];
      var idx = []; for (var k = 0; k < n; k++) if (mask & (1 << k)) idx.push(k);
      var s = sList[si], v;
      var maxd = 0; for (var a = 0; a < idx.length; a++) for (var b = a + 1; b < idx.length; b++) if (D[idx[a]][idx[b]] > maxd) maxd = D[idx[a]][idx[b]];
      v = (maxd > 0.95 * s) ? Infinity : subsetSSE(P, idx, s);
      cache[key] = v; return v;
    }
    var full = (1 << n) - 1, results = [];
    var maskCount = 1 << (n - 1);
    for (var m = 0; m < maskCount; m++) {
      var mA = (m << 1) | 1, mB = full ^ mA;
      if (!mB) continue;
      var nA = popcount(mA), nB = n - nA;
      if (nA > 6 || nB > 6) continue;
      var bestC = Infinity, bestS = -1;
      for (var si = 0; si < sList.length; si++) {
        var s = sList[si], a1 = sse(mA, si); if (a1 === Infinity) continue;
        var b1 = sse(mB, si); if (b1 === Infinity) continue;
        var pen = 0;
        for (i = 0; i < n; i++) if (mA & (1 << i)) for (j = 0; j < n; j++) if (mB & (1 << j)) {
          var lim = MIN_SEP * s; if (D[i][j] < lim) pen += 2 * (lim - D[i][j]) / s;
        }
        var cost = Math.sqrt((a1 + b1) / n) / s + pen + 0.015 * Math.abs(Math.log(RATIOS[si] / 5.0));
        if (cost < bestC) { bestC = cost; bestS = si; }
      }
      if (bestS >= 0) results.push({ mA: mA, nA: nA, nB: nB, cost: bestC, si: bestS });
    }
    if (!results.length) return null;
    results.sort(function (a, b) { return a.cost - b.cost; });
    var top = results[0], total = top.nA + top.nB, alt = Infinity;
    // all partitions of n pips give the same total, so "alternatives" = other candidate pip counts (handled by caller)
    var altSplit = Infinity;
    for (i = 1; i < results.length; i++) {
      if (Math.min(results[i].nA, results[i].nB) !== Math.min(top.nA, top.nB) || true) {
        var sa = [results[i].nA, results[i].nB].sort().join(), sb = [top.nA, top.nB].sort().join();
        if (sa !== sb) { altSplit = results[i].cost; break; }
      }
    }
    var groups = [[], []];
    for (i = 0; i < n; i++) groups[(top.mA & (1 << i)) ? 0 : 1].push(i);
    return { cost: top.cost, total: total, groups: groups, margin: altSplit - top.cost, s: sList[top.si] };
  }

  // ---------- main ----------
  function detect(img, opts) {
    opts = opts || {};
    var t0 = (typeof performance !== 'undefined' ? performance.now() : Date.now());
    var pr = prep(img, opts.maxDim || 640), W = pr.w, H = pr.h, cands = [];
    var radii = opts.radii || RADII;
    ['dark', 'light'].forEach(function (pol) {
      radii.forEach(function (R) {
        if (R * 2 + 1 > Math.min(W, H) / 2) return;
        var th = tophat(pr.gray, W, H, R, pol === 'dark');
        var pips = filterConsistent(validateOnDie(extractBlobs(th, pr.gray, W, H, R), pr));
        if (pips.length < 2) { cands.push({ pol: pol, R: R, n: pips.length, fit: null }); return; }
        var fit = fitDice(pips);
        cands.push({ pol: pol, R: R, n: pips.length, pips: pips, fit: fit });
      });
    });
    var good = cands.filter(function (c) { return c.fit && c.fit.cost <= 0.075; });
    var pick = null;
    if (good.length) {
      good.sort(function (a, b) { return (b.n - a.n) || (a.fit.cost - b.fit.cost); });
      pick = good[0];
    } else {
      var any = cands.filter(function (c) { return c.fit; });
      any.sort(function (a, b) { return a.fit.cost - b.fit.cost; });
      pick = any[0] || null;
    }
    var ms = (typeof performance !== 'undefined' ? performance.now() : Date.now()) - t0;
    if (!pick) return { ok: false, total: null, counts: null, confidence: 'none', pips: [], ms: ms, reason: 'fewer than 2 pips found' };
    var f = pick.fit, counts = f.groups.map(function (g) { return g.length; });
    // order dice left-to-right
    var cxs = f.groups.map(function (g) { var s = 0; g.forEach(function (i) { s += pick.pips[i].x; }); return s / g.length; });
    var order = cxs[0] <= cxs[1] ? [0, 1] : [1, 0];
    var pips = [];
    order.forEach(function (gi, di) {
      f.groups[gi].forEach(function (i) { var p = pick.pips[i]; pips.push({ x: p.x / W, y: p.y / H, r: p.d / 2 / Math.max(W, H), die: di }); });
    });
    var votes = good.filter(function (c) { return c.n === pick.n; }).length;   // scales/polarities agreeing on this pip count
    var conf = 'low';
    if (f.cost <= 0.06 && votes >= 3) conf = 'high';
    else if (f.cost <= 0.09 && votes >= 2) conf = 'medium';
    var avgD = 0; pick.pips.forEach(function (p) { avgD += p.d; }); avgD /= pick.pips.length;
    var hint = '';
    if (avgD < 6.5) { conf = 'low'; hint = 'Dice look small \u2014 move the phone closer.'; }
    return { ok: true, hint: hint, total: f.total, counts: [counts[order[0]], counts[order[1]]], confidence: conf,
             cost: f.cost, margin: f.margin, votes: votes, pips: pips, polarity: pick.pol, R: pick.R, ms: ms, workW: W, workH: H };
  }

  var api = { detect: detect, _internals: { prep: prep, tophat: tophat, extractBlobs: extractBlobs, fitDice: fitDice } };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.DiceDetector = api;
})(typeof self !== 'undefined' ? self : this);
