// Inject only into the stopped isolated Proxy's mounted outbox; never run against production paths.
const fs=require('fs'),http=require('http'),crypto=require('crypto'),assert=require('assert/strict');
const root='/data/gateway-recovery-test-20261016',queue=root+'/logs/audit-outbox',manifest=root+'/audit-restart-fixture.json';
function api(path,body){return new Promise((resolve,reject)=>{const req=http.request({hostname:'127.0.0.1',port:18086,path,method:body?'POST':'GET',auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json','X-Gateway-Lineage-Token':process.env.LINEAGE_INGRESS_TOKEN}},r=>{let s='';r.on('data',x=>s+=x);r.on('end',()=>{try{assert(r.statusCode<300);resolve(JSON.parse(s));}catch(e){reject(e);}})});req.on('error',reject);req.end(body?JSON.stringify(body):undefined);});}
(async()=>{
 if(process.argv.includes('--prepare')) {
  assert(!fs.existsSync(manifest),'fixture already exists');
  const ids=[crypto.randomUUID(),crypto.randomUUID()];fs.mkdirSync(queue,{recursive:true,mode:0o700});
  for(let i=0;i<2;i++) {
   const payload={username:'audit_restart_fixture',database:'gateway_multi_acceptance_20261014',sqlDigest:'synthetic-no-sql',outcome:'EXECUTED',requestId:ids[i]};
   if(i===1)await api('/api/internal/sql/audit',payload); // Simulate DB committed receipt but acknowledgement lost.
   const fd=fs.openSync(queue+'/'+ids[i]+'-EXECUTED.pending','wx',0o600);fs.writeSync(fd,JSON.stringify(payload));fs.fsyncSync(fd);fs.closeSync(fd);
  }
  fs.writeFileSync(manifest,JSON.stringify(ids),{flag:'wx',mode:0o600});
  console.log('已注入两条隔离审计消息：未投递、已落库但确认丢失');return;
 }
 const ids=JSON.parse(fs.readFileSync(manifest,'utf8'));
 for(let n=0;n<25;n++) {
  if(ids.every(id=>!fs.existsSync(queue+'/'+id+'-EXECUTED.pending')))break;
  await new Promise(resolve=>setTimeout(resolve,1000));
 }
 const rows=await api('/api/audit');
 for(const id of ids) {assert(!fs.existsSync(queue+'/'+id+'-EXECUTED.pending'));assert.equal(rows.filter(r=>r.action==='USER_SQL_EXECUTED'&&r.detail.includes(id)).length,1);}
 console.log('PASS Proxy 重启后补投持久消息，已落库消息重复发送仍只有一条审计；未执行业务 SQL');
})().catch(e=>{console.error(e.message);process.exitCode=1;});
