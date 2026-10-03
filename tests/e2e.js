const puppeteer=require('puppeteer-core'); const fs=require('fs');
const URL='http://localhost:8099/index.html'; const UD='/tmp/srr-ud'; const DL='/tmp/srr-dl';
fs.rmSync(UD,{recursive:true,force:true}); fs.rmSync(DL,{recursive:true,force:true}); fs.mkdirSync(DL,{recursive:true}); fs.mkdirSync('shots',{recursive:true});
const results=[]; const ok=(n,c,x)=>{results.push([n,!!c,x]);console.log(c?'PASS':'FAIL',n,x===undefined?'':JSON.stringify(x));};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function launch(cam){
  const b=await puppeteer.launch({executablePath:'/usr/bin/google-chrome',headless:'new',userDataDir:UD,
    args:['--no-sandbox','--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream',`--use-file-for-fake-video-capture=${cam}`,'--autoplay-policy=no-user-gesture-required']});
  const p=await b.newPage(); await p.setViewport({width:390,height:844,deviceScaleFactor:2,isMobile:true,hasTouch:true});
  p.on('dialog',d=>d.accept()); p.on('pageerror',e=>console.log('PAGEERR',e.message)); p.on('console',m=>{if(m.type()==='error')console.log('CONSOLE',m.text());});
  const cdp=await p.createCDPSession(); await cdp.send('Browser.setDownloadBehavior',{behavior:'allow',downloadPath:DL}).catch(()=>{});
  await p.goto(URL,{waitUntil:'load'});
  await p.waitForFunction(()=>document.getElementById('camMsg').hidden===true,{timeout:15000});
  return {b,p};
}
const txt=(p,sel)=>p.$eval(sel,e=>e.textContent);
async function main(){
  // ---- launch 1: capture a 6+1
  let {b,p}=await launch('/workspace/srr-test/cam_a.mjpeg');
  ok('camera started (fake device, video has frames)', await p.$eval('#video',v=>v.videoWidth>0),await p.$eval('#video',v=>[v.videoWidth,v.videoHeight]));
  ok('manifest linked', await p.$eval('link[rel=manifest]',e=>e.href));
  await p.screenshot({path:'shots/01_main_empty.png'});
  await p.click('#capture');
  await p.waitForFunction(()=>/Detected|Couldn/.test(document.getElementById('detectLine').textContent),{timeout:20000});
  console.log('lastDet',JSON.stringify(await p.evaluate(()=>window.__lastDet)));
  let line=await txt(p,'#detectLine'); ok('detector ran in browser on camera frame',/Detected/.test(line),line);
  ok('detected total is 7 (truth 6+1)',(await txt(p,'#reviewTotal'))==='7',await txt(p,'#reviewTotal'));
  await p.screenshot({path:'shots/02_review.png'});
  await p.click('#reviewLog'); await sleep(400);
  ok('roll logged; count=1', (await txt(p,'#rollCount'))==='1'); ok('SRR shows 1:1.0',(await txt(p,'#srr'))==='1:1.0',await txt(p,'#srr'));
  ok('7 shown red', await p.$eval('.item',e=>e.classList.contains('seven')&&getComputedStyle(e.querySelector('.t')).color==='rgb(255, 59, 59)'));
  // manual entry + tap-correct flow
  await p.click('#menuBtn'); await p.click('#mManual'); await p.waitForSelector('#reviewSheet:not([hidden])');
  await p.click('#reviewSheet .picker[data-die="0"] button[data-n="2"]'); await p.click('#reviewSheet .picker[data-die="1"] button[data-n="3"]');
  ok('manual total 5',(await txt(p,'#reviewTotal'))==='5'); await p.click('#reviewLog'); await sleep(300);
  ok('count=2, SRR 1:2.0',(await txt(p,'#rollCount'))==='2'&&(await txt(p,'#srr'))==='1:2.0',await txt(p,'#srr'));
  await b.close();
  // ---- launch 2: persistence + capture 4+5, then correct in review
  ({b,p}=await launch('/workspace/srr-test/cam_b.mjpeg'));
  ok('data persisted across restart (IndexedDB)',(await txt(p,'#rollCount'))==='2');
  await p.click('#cam'); // tap on preview
  await p.waitForFunction(()=>/Detected|Couldn/.test(document.getElementById('detectLine').textContent),{timeout:20000});
  ok('tap-on-preview capture; detected 9 (truth 4+5)',(await txt(p,'#reviewTotal'))==='9',await txt(p,'#detectLine'));
  await p.click('#reviewSheet .picker[data-die="1"] button[data-n="3"]');
  ok('tap-correct changes total to 7',(await txt(p,'#reviewTotal'))==='7');
  await p.click('#reviewLog'); await sleep(300);
  ok('3 rolls, SRR 1:1.5',(await txt(p,'#srr'))==='1:1.5',await txt(p,'#srr'));
  const items=await p.$$eval('.item',els=>els.map(e=>[e.querySelector('.t').textContent,e.querySelector('.r').firstChild.textContent,e.classList.contains('seven')]));
  ok('list newest-first with running SRR',JSON.stringify(items)==='[["7","1:1.5",true],["5","1:2.0",false],["7","1:1.0",true]]',items);
  await p.screenshot({path:'shots/03_list.png'});
  // detail: open photo, correct, delete
  await p.click('.item:nth-child(1)'); await p.waitForSelector('#detailSheet:not([hidden])'); await sleep(500);
  ok('detail shows saved photo',await p.$eval('#detailCanvas',c=>c.width>100),await p.$eval('#detailCanvas',c=>c.width));
  await p.screenshot({path:'shots/04_detail.png'});
  await p.click('#detailSheet .picker[data-die="0"] button[data-n="1"]'); await sleep(200);
  ok('detail correct updates list (1+3=4)',(await txt(p,'.item .t'))==='4',await txt(p,'.item .t'));
  await p.click('#detailDelete'); await sleep(300);
  ok('delete roll -> count 2',(await txt(p,'#rollCount'))==='2');
  const nph=await p.evaluate(()=>new Promise(r=>{const q=indexedDB.open('srr-tracker');q.onsuccess=()=>{const g=q.result.transaction('photos').objectStore('photos').count();g.onsuccess=()=>r(g.result)}}));
  ok('photo blobs in IndexedDB: 1 (one photo roll left after delete)',nph===1,nph);
  const blobInfo=await p.evaluate(()=>new Promise(r=>{const q=indexedDB.open('srr-tracker');q.onsuccess=()=>{const g=q.result.transaction('photos').objectStore('photos').getAll();g.onsuccess=()=>r(g.result.map(x=>[x.blob.type,x.blob.size]))}}));
  ok('photo is downscaled JPEG',blobInfo[0][0]==='image/jpeg'&&blobInfo[0][1]<400000,blobInfo);
  // export
  await p.click('#menuBtn'); await p.click('#mExport'); await sleep(1200);
  const files=fs.readdirSync(DL); ok('CSV exported',files.length===1,files);
  if(files.length){const csv=fs.readFileSync(DL+'/'+files[0],'utf8'); console.log(csv); ok('CSV has header+2 rows',csv.trim().split(/\r?\n/).length===3);}
  // settings: auto accept
  await p.click('#menuBtn'); await p.click('#mSettings'); await p.click('#setAuto'); await p.$eval('#setHigh',e=>{e.checked=false}); await p.click('#setClose');
  await b.close();
  // ---- launch 3: auto-accept
  ({b,p}=await launch('/workspace/srr-test/cam_c.mjpeg'));
  await p.click('#capture'); await p.waitForFunction(()=>/Detected/.test(document.getElementById('detectLine').textContent),{timeout:20000});
  ok('auto-accept bar shown',await p.$eval('#autoBar',e=>!e.hidden));
  await sleep(2600);
  ok('auto-accepted after ~2s untouched (count 3)',(await txt(p,'#rollCount'))==='3',await txt(p,'#rollCount'));
  // touching cancels
  await p.click('#capture'); await p.waitForFunction(()=>/Detected/.test(document.getElementById('detectLine').textContent),{timeout:20000});
  await p.click('#reviewSheet .picker[data-die="0"] button[data-n="2"]'); await sleep(2600);
  ok('touch cancels auto-accept (count still 3)',(await txt(p,'#rollCount'))==='3');
  await p.click('#reviewDiscard');
  // sessions
  await p.click('#menuBtn'); await p.click('#mNew'); await sleep(500);
  ok('new session -> 0 rolls',(await txt(p,'#rollCount'))==='0');
  await p.click('#menuBtn'); await p.click('#mSessions'); await sleep(300);
  ok('2 sessions listed',(await p.$$('.sess')).length===2);
  await p.screenshot({path:'shots/05_sessions.png'}); await p.click('#sClose');
  // offline
  await sleep(500);
  const sw=await p.evaluate(async()=>{const r=await navigator.serviceWorker.ready;return !!r.active;}); ok('service worker active',sw);
  await p.setOfflineMode(true); await p.reload({waitUntil:'load'});
  ok('loads offline',(await p.title())==='SRR Tracker' && (await txt(p,'#rollCount'))==='0');
  await p.setOfflineMode(false);
  await p.click('#menuBtn'); await p.screenshot({path:'shots/06_menu.png'});
  await b.close();
  const fail=results.filter(r=>!r[1]); console.log(`\n${results.length-fail.length}/${results.length} checks passed`); process.exit(fail.length?1:0);
}
main().catch(e=>{console.error('E2E ERROR',e);process.exit(2)});
