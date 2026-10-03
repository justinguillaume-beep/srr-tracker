/* SRR Tracker — app logic. All data local (IndexedDB + localStorage). */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const S = window.SRRStats, DD = window.DiceDetector;

  // ---------- settings ----------
  const defaults = { auto: false, delay: 2, onlyHigh: true, vibrate: true };
  let settings = Object.assign({}, defaults, safeJSON(localStorage.getItem('srr.settings')));
  function safeJSON(s) { try { return JSON.parse(s) || {}; } catch (e) { return {}; } }
  function saveSettings() { localStorage.setItem('srr.settings', JSON.stringify(settings)); }

  // ---------- IndexedDB ----------
  let db;
  function openDB() {
    return new Promise((res, rej) => {
      const q = indexedDB.open('srr-tracker', 1);
      q.onupgradeneeded = () => {
        const d = q.result;
        d.createObjectStore('sessions', { keyPath: 'id', autoIncrement: true });
        d.createObjectStore('rolls', { keyPath: 'id', autoIncrement: true }).createIndex('sessionId', 'sessionId');
        d.createObjectStore('photos', { keyPath: 'id', autoIncrement: true });
      };
      q.onsuccess = () => res(q.result);
      q.onerror = () => rej(q.error);
    });
  }
  const rq = r => new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
  const st = (n, m) => db.transaction(n, m || 'readonly').objectStore(n);
  const dbPut = (n, o) => rq(st(n, 'readwrite').put(o));
  const dbGet = (n, k) => rq(st(n).get(k));
  const dbDel = (n, k) => rq(st(n, 'readwrite').delete(k));
  const dbAll = n => rq(st(n).getAll());
  const rollsOf = sid => rq(st('rolls').index('sessionId').getAll(sid)).then(a => a.sort((x, y) => x.ts - y.ts || x.id - y.id));

  // ---------- state ----------
  let session = null, rolls = [], stream = null, wantCamera = true;
  const video = $('video');

  function sessionName(d) { return 'Session ' + d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) + ' ' + d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }); }
  async function newSession() {
    const now = new Date();
    const id = await dbPut('sessions', { name: sessionName(now), startedAt: now.getTime() });
    return dbGet('sessions', id);
  }
  async function loadSession(id) {
    session = id ? await dbGet('sessions', id) : null;
    if (!session) {
      const all = await dbAll('sessions');
      session = all.length ? all[all.length - 1] : await newSession();
    }
    localStorage.setItem('srr.session', String(session.id));
    rolls = await rollsOf(session.id);
    render();
  }

  // ---------- render ----------
  function render() {
    const sum = S.summary(rolls), run = S.running(rolls);
    $('rollCount').textContent = sum.rolls;
    $('srr').textContent = sum.ratio;
    $('srrSub').textContent = sum.rolls ? `SRR \u00b7 ${sum.sevens} sevens \u00b7 ${sum.perRollStr}/roll (${sum.pct})` : 'SRR (rolls per seven)';
    const list = $('list');
    if (!rolls.length) { list.innerHTML = '<div class="empty">No rolls yet.<br>Point the camera at the dice and tap CAPTURE.</div>'; return; }
    let h = '';
    for (let i = rolls.length - 1; i >= 0; i--) {
      const r = rolls[i], u = run[i];
      h += `<div class="item${r.total === 7 ? ' seven' : ''}" data-id="${r.id}"><div class="n">#${i + 1}</div><div class="t">${r.total}</div>` +
        `<div class="flag">${r.corrected ? '\u270e' : ''}</div><div class="r">${u.ratio}<small>${u.perRollStr}/roll</small></div></div>`;
    }
    list.innerHTML = h;
  }
  $('list').addEventListener('click', e => {
    const it = e.target.closest('.item'); if (it) openDetail(+it.dataset.id);
  });

  // ---------- toast / sheets ----------
  let toastT;
  function toast(msg, ms) { const t = $('toast'); t.textContent = msg; t.hidden = false; clearTimeout(toastT); toastT = setTimeout(() => t.hidden = true, ms || 2200); }
  const show = id => $(id).hidden = false, hide = id => $(id).hidden = true;
  const buzz = () => { if (settings.vibrate && navigator.vibrate) navigator.vibrate(30); };

  // ---------- camera ----------
  async function startCamera() {
    wantCamera = true;
    stopCamera();
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      $('camText').textContent = 'Camera unavailable. It needs HTTPS (or localhost) and a browser with camera support.';
      $('camMsg').hidden = false; return;
    }
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false });
      video.srcObject = stream;
      await video.play();
      $('camMsg').hidden = true;
    } catch (e) {
      let m = 'Could not start camera: ' + (e.name || e.message);
      if (e.name === 'NotAllowedError') m = 'Camera permission denied. Allow camera for this site in browser/phone settings, then tap Try again.';
      if (e.name === 'NotFoundError') m = 'No camera found.';
      $('camText').textContent = m; $('startCam').textContent = 'Try again'; $('camMsg').hidden = false;
    }
  }
  function stopCamera() { if (stream) { stream.getTracks().forEach(t => t.stop()); stream = null; } video.srcObject = null; }
  $('startCam').addEventListener('click', startCamera);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { if (stream) { stopCamera(); $('camMsg').hidden = false; $('camText').textContent = 'Camera paused'; } }
    else if (wantCamera) startCamera();
  });

  // ---------- overlay drawing ----------
  const DIE_COL = ['#00e5ff', '#ffd400'];
  function drawPhoto(cv, src, pips, maxW) {
    const sw = src.width || src.naturalWidth, sh = src.height || src.naturalHeight;
    const k = Math.min(1, (maxW || 900) / sw);
    cv.width = Math.round(sw * k); cv.height = Math.round(sh * k);
    const c = cv.getContext('2d'); c.drawImage(src, 0, 0, cv.width, cv.height);
    (pips || []).forEach(p => {
      c.beginPath(); c.arc(p.x * cv.width, p.y * cv.height, Math.max(5, p.r * Math.max(cv.width, cv.height) * 1.35), 0, 7);
      c.lineWidth = Math.max(2, cv.width / 300); c.strokeStyle = DIE_COL[p.die] || '#0ff'; c.stroke();
    });
  }

  // ---------- pickers ----------
  function buildPicker(el, die, onPick) {
    el.innerHTML = [1, 2, 3, 4, 5, 6].map(n => `<button data-n="${n}" aria-label="Die ${die + 1}: ${n}">${n}</button>`).join('');
    el.onclick = e => { const b = e.target.closest('button'); if (b) onPick(die, +b.dataset.n); };
  }
  function paintPickers(root, vals) {
    root.querySelectorAll('.picker').forEach(p => {
      const v = vals[+p.dataset.die];
      p.querySelectorAll('button').forEach(b => b.classList.toggle('sel', +b.dataset.n === v));
    });
  }
  function paintTotal(el, vals) {
    const ok = vals[0] && vals[1], tot = ok ? vals[0] + vals[1] : null;
    el.textContent = ok ? tot : '?';
    el.parentElement.classList.toggle('seven', tot === 7);
    return tot;
  }

  // ---------- capture + review ----------
  let review = null, autoTimer = null;
  function cancelAuto() { clearTimeout(autoTimer); autoTimer = null; $('autoBar').hidden = true; }

  async function capture() {
    if (review) return;
    if (!stream || !video.videoWidth) { toast('Start the camera first'); return; }
    const vw = video.videoWidth, vh = video.videoHeight, k = Math.min(1, 1024 / Math.max(vw, vh));
    const cv = document.createElement('canvas'); cv.width = Math.round(vw * k); cv.height = Math.round(vh * k);
    cv.getContext('2d').drawImage(video, 0, 0, cv.width, cv.height);
    buzz();
    startReview(cv);
  }
  function startReview(cv) {
    review = { cv, ts: Date.now(), d: [null, null], det: null, touched: false, blob: null };
    $('reviewCanvas').parentElement.hidden = !cv;
    if (cv) {
      drawPhoto($('reviewCanvas'), cv, []);
      review.blobP = new Promise(res => cv.toBlob(res, 'image/jpeg', 0.72));
    }
    $('detectLine').innerHTML = cv ? 'Reading dice\u2026' : 'Manual entry \u2014 tap each die.';
    $('reviewLog').disabled = true;
    buildPicker($('reviewSheet').querySelectorAll('.picker')[0], 0, pickReview);
    buildPicker($('reviewSheet').querySelectorAll('.picker')[1], 1, pickReview);
    paintPickers($('reviewSheet'), review.d); paintTotal($('reviewTotal'), review.d);
    show('reviewSheet');
    if (cv) setTimeout(runDetection, 30);
  }
  function runDetection() {
    const r = review; if (!r) return;
    let det;
    try { det = DD.detect(r.cv.getContext('2d').getImageData(0, 0, r.cv.width, r.cv.height)); }
    catch (e) { det = { ok: false, reason: String(e) }; }
    if (review !== r) return;
    r.det = det; window.__lastDet = { ms: det.ms, total: det.total, confidence: det.confidence };
    if (det.ok && !r.touched) r.d = det.counts.slice();
    if (det.ok) drawPhoto($('reviewCanvas'), r.cv, det.pips);
    const cls = { high: 'hi', medium: 'med', low: 'lo' }[det.confidence] || 'lo';
    $('detectLine').innerHTML = det.ok
      ? `Detected <b class="${cls}">${det.total}</b> (${det.counts[0]}+${det.counts[1]}) \u00b7 <b class="${cls}">${det.confidence} confidence</b> \u2014 tap a number to fix${det.hint ? '<br>' + det.hint : ''}`
      : `<b class="lo">Couldn't read the dice.</b> Tap each die\u2019s value.`;
    refreshReview();
    if (settings.auto && det.ok && !r.touched && (!settings.onlyHigh || det.confidence === 'high')) startAuto();
  }
  function startAuto() {
    const bar = $('autoBar'), inner = bar.firstElementChild, ms = Math.max(500, settings.delay * 1000);
    bar.hidden = false; inner.style.transition = 'none'; inner.style.transform = 'scaleX(1)';
    void inner.offsetWidth; inner.style.transition = `transform ${ms}ms linear`; inner.style.transform = 'scaleX(0)';
    autoTimer = setTimeout(() => logReview(), ms);
  }
  function pickReview(die, n) {
    if (!review) return;
    review.touched = true; cancelAuto(); review.d[die] = n; refreshReview();
  }
  function refreshReview() {
    paintPickers($('reviewSheet'), review.d);
    const tot = paintTotal($('reviewTotal'), review.d);
    $('reviewLog').disabled = tot == null;
  }
  $('reviewSheet').addEventListener('pointerdown', cancelAuto);
  $('reviewDiscard').addEventListener('click', () => { cancelAuto(); review = null; hide('reviewSheet'); });
  $('reviewLog').addEventListener('click', () => logReview());

  async function logReview() {
    const r = review; if (!r || r.d[0] == null || r.d[1] == null) return;
    cancelAuto(); review = null; hide('reviewSheet');
    let photoId = null;
    if (r.cv) { const blob = await r.blobP; photoId = await dbPut('photos', { blob, ts: r.ts }); }
    const det = r.det;
    const total = r.d[0] + r.d[1];
    const roll = {
      sessionId: session.id, ts: r.ts, d1: r.d[0], d2: r.d[1], total, photoId,
      corrected: !!(r.cv && (!det || !det.ok || det.total !== total)),
      detected: det && det.ok ? { d1: det.counts[0], d2: det.counts[1], total: det.total, confidence: det.confidence, pips: det.pips } : null
    };
    roll.id = await dbPut('rolls', roll);
    rolls.push(roll); render(); buzz();
    $('list').scrollTop = 0;
  }

  // ---------- detail ----------
  let detailRoll = null, detailURL = null;
  async function openDetail(id) {
    const roll = rolls.find(r => r.id === id); if (!roll) return;
    detailRoll = roll;
    const idx = rolls.indexOf(roll) + 1;
    $('detailTitle').textContent = `Roll #${idx} \u00b7 ` + new Date(roll.ts).toLocaleString();
    buildPicker($('detailSheet').querySelectorAll('.picker')[0], 0, pickDetail);
    buildPicker($('detailSheet').querySelectorAll('.picker')[1], 1, pickDetail);
    refreshDetail();
    const cv = $('detailCanvas'); cv.width = cv.height = 0; $('noPhoto').hidden = true;
    show('detailSheet');
    if (roll.photoId != null) {
      const p = await dbGet('photos', roll.photoId);
      if (p && p.blob && detailRoll === roll) {
        if (detailURL) URL.revokeObjectURL(detailURL);
        detailURL = URL.createObjectURL(p.blob);
        const im = new Image(); im.onload = () => { if (detailRoll === roll) drawPhoto(cv, im, roll.detected && roll.detected.pips); }; im.src = detailURL;
      } else $('noPhoto').hidden = false;
    } else $('noPhoto').hidden = false;
  }
  function refreshDetail() {
    const v = [detailRoll.d1, detailRoll.d2];
    paintPickers($('detailSheet'), v); paintTotal($('detailTotal'), v);
  }
  async function pickDetail(die, n) {
    if (!detailRoll) return;
    if (die === 0) detailRoll.d1 = n; else detailRoll.d2 = n;
    detailRoll.total = detailRoll.d1 + detailRoll.d2;
    detailRoll.corrected = !detailRoll.detected || detailRoll.detected.total !== detailRoll.total || detailRoll.corrected;
    await dbPut('rolls', detailRoll); refreshDetail(); render();
  }
  function closeDetail() { hide('detailSheet'); detailRoll = null; if (detailURL) { URL.revokeObjectURL(detailURL); detailURL = null; } }
  $('detailClose').addEventListener('click', closeDetail);
  $('detailDone').addEventListener('click', closeDetail);
  $('detailDelete').addEventListener('click', async () => {
    if (!detailRoll || !confirm('Delete this roll?')) return;
    const r = detailRoll; closeDetail();
    if (r.photoId != null) await dbDel('photos', r.photoId);
    await dbDel('rolls', r.id); rolls = rolls.filter(x => x.id !== r.id); render(); toast('Roll deleted');
  });

  // ---------- menu / sessions / export ----------
  $('menuBtn').addEventListener('click', () => { $('sessionName').textContent = session.name; show('menuSheet'); });
  $('mClose').addEventListener('click', () => hide('menuSheet'));
  $('mNew').addEventListener('click', async () => {
    if (!confirm('Start a new session? The current one is kept under All sessions.')) return;
    hide('menuSheet'); session = await newSession(); await loadSession(session.id); toast('New session started');
  });
  $('mReset').addEventListener('click', async () => {
    if (!confirm('Reset this session? This deletes all its rolls and photos.')) return;
    hide('menuSheet');
    for (const r of rolls) { if (r.photoId != null) await dbDel('photos', r.photoId); await dbDel('rolls', r.id); }
    rolls = []; render(); toast('Session reset');
  });
  $('mManual').addEventListener('click', () => { hide('menuSheet'); if (!review) startReview(null); });
  $('mSessions').addEventListener('click', async () => { hide('menuSheet'); await renderSessions(); show('sessionsSheet'); });
  $('sClose').addEventListener('click', () => hide('sessionsSheet'));
  async function renderSessions() {
    const all = (await dbAll('sessions')).reverse(); let h = '';
    for (const s of all) {
      const rs = await rollsOf(s.id), sm = S.summary(rs);
      h += `<div class="sess${s.id === session.id ? ' cur' : ''}"><div class="info"><b>${esc(s.name)}</b><span>${sm.rolls} rolls \u00b7 SRR ${sm.ratio}</span></div>` +
        `<button class="btn" data-use="${s.id}">Open</button><button class="btn danger" data-del="${s.id}">Delete</button></div>`;
    }
    $('sessionList').innerHTML = h;
  }
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  $('sessionList').addEventListener('click', async e => {
    const b = e.target.closest('button'); if (!b) return;
    if (b.dataset.use) { await loadSession(+b.dataset.use); hide('sessionsSheet'); }
    if (b.dataset.del) {
      if (!confirm('Delete that session and all its rolls/photos?')) return;
      const id = +b.dataset.del;
      for (const r of await rollsOf(id)) { if (r.photoId != null) await dbDel('photos', r.photoId); await dbDel('rolls', r.id); }
      await dbDel('sessions', id);
      if (id === session.id) await loadSession(null);
      await renderSessions();
    }
  });
  async function exportCSV(allSessions) {
    hide('menuSheet');
    const sess = allSessions ? await dbAll('sessions') : [session];
    const data = [];
    for (const s of sess) data.push({ name: s.name, startedAt: s.startedAt, rolls: await rollsOf(s.id) });
    const csv = S.toCSV(data);
    const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-');
    const name = `srr-${allSessions ? 'all' : 'session'}-${stamp}.csv`;
    const file = new File([csv], name, { type: 'text/csv' });
    if (navigator.canShare && navigator.canShare({ files: [file] }) && matchMedia('(pointer:coarse)').matches) {
      try { await navigator.share({ files: [file], title: name }); return; } catch (e) { if (e.name === 'AbortError') return; }
    }
    const a = document.createElement('a'); a.href = URL.createObjectURL(file); a.download = name;
    document.body.appendChild(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
    toast('Exported ' + name);
  }
  $('mExport').addEventListener('click', () => exportCSV(false));
  $('mExportAll').addEventListener('click', () => exportCSV(true));

  // ---------- settings UI ----------
  $('mSettings').addEventListener('click', () => {
    hide('menuSheet');
    $('setAuto').checked = settings.auto; $('setDelay').value = settings.delay; $('setHigh').checked = settings.onlyHigh; $('setVibrate').checked = settings.vibrate;
    show('settingsSheet');
  });
  $('setClose').addEventListener('click', () => {
    settings.auto = $('setAuto').checked; settings.delay = Math.min(10, Math.max(1, parseFloat($('setDelay').value) || 2));
    settings.onlyHigh = $('setHigh').checked; settings.vibrate = $('setVibrate').checked; saveSettings(); hide('settingsSheet');
  });

  // ---------- capture triggers ----------
  $('capture').addEventListener('click', capture);
  $('cam').addEventListener('click', e => { if ($('camMsg').hidden) capture(); });

  // ---------- init ----------
  (async () => {
    try { db = await openDB(); } catch (e) { toast('Storage unavailable: ' + e.message, 8000); return; }
    if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
    await loadSession(+localStorage.getItem('srr.session') || null);
    startCamera();
    window.__srr = { get rolls() { return rolls; }, get session() { return session; } };   // handy for debugging/tests
  })();
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
})();
