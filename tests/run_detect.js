const fs=require('fs'); const D=require('../detector.js');
const labels=JSON.parse(fs.readFileSync((process.env.IMGS||'imgs')+'/labels.json'));
const opts={}; if(process.argv[2]) opts.maxDim=+process.argv[2];
const only=process.argv[3];
const stats={}; const byConf={}; const bad=[]; let tms=0;
function add(k,ok,exact,conf){const s=stats[k]||(stats[k]={n:0,totalOK:0,exact:0,hi:0,hiOK:0,fail:0});s.n++;if(ok)s.totalOK++;if(exact)s.exact++;if(conf==='high'){s.hi++;if(ok)s.hiOK++;}}
for(const L of labels){
  if(only && L.cat!==only) continue;
  const buf=fs.readFileSync(`${process.env.IMGS||'imgs'}/${L.file}.rgba`);
  const img={data:new Uint8ClampedArray(buf.buffer,buf.byteOffset,buf.length),width:L.w,height:L.h};
  const r=D.detect(img,opts); tms+=r.ms;
  const ok=r.ok&&r.total===L.total; const exact=r.ok&&r.counts&&((r.counts[0]===L.d1&&r.counts[1]===L.d2)||(r.counts[0]===L.d2&&r.counts[1]===L.d1));
  for(const k of [L.cat,'ALL']) add(k,ok,exact,r.confidence);
  const bc=byConf[r.confidence]||(byConf[r.confidence]={n:0,ok:0}); bc.n++; if(ok)bc.ok++;
  if(!ok) bad.push(`${L.file} ${L.cat} true=${L.d1}+${L.d2}=${L.total} got=${r.total} ${r.counts} conf=${r.confidence} cost=${r.cost&&r.cost.toFixed(3)}`);
}
for(const [k,s] of Object.entries(stats)) console.log(k.padEnd(9),'n='+s.n,'total%',(100*s.totalOK/s.n).toFixed(1),'dice-exact%',(100*s.exact/s.n).toFixed(1),'| high-conf',s.hi,'correct',s.hiOK);
console.log('CONF',JSON.stringify(byConf));
console.log('avg ms',(tms/stats.ALL.n).toFixed(0));
if(process.env.V) console.log(bad.join('\n'));
fs.writeFileSync("bad_"+(only||"all")+".txt",bad.join("\n"));
