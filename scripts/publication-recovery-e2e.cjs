// Only isolated 18086 / 3312, with an offline-injected test journal. No SQL replay.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict');
const db='gateway_multi_acceptance_20261014';
function request(path,method='GET',body,authenticated=true){return new Promise((resolve,reject)=>{
 const r=http.request({hostname:'127.0.0.1',port:18086,path,method,auth:authenticated?process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD:undefined,headers:{'Content-Type':'application/json'}},s=>{let b='';s.on('data',x=>b+=x);s.on('end',()=>{let value;try{value=JSON.parse(b)}catch(e){value={message:b}}resolve({status:s.statusCode,value});});});r.on('error',reject);r.end(body?JSON.stringify(body):undefined);
});}
async function api(path,method,body){const r=await request(path,method,body);assert(r.status<300,JSON.stringify(r.value));return r.value;}
(async()=>{
 const versions=await api('/api/versions');const pending=versions.find(r=>r.databaseName===db&&r.message==='RECOVERY_FIXTURE');assert(pending,'Run offline fixture on a cold isolated copy first');
 const path='/api/versions/'+pending.id+'/recovery';
 assert.equal((await request(path,'GET',undefined,false)).status,401);assert.equal((await request(path,'POST',{expectedActiveVersion:0},false)).status,401);
 console.log('PASS 未认证请求不能查看或执行发布恢复');
 assert((await request('/api/publish','POST',{database:db})).status>=400);
 console.log('PASS 重启后未完成日志仍阻止覆盖发布');
 const control=await mysql.createConnection({host:'127.0.0.1',port:3312,user:process.env.PROXY_ADMIN_USER,password:process.env.PROXY_ADMIN_PASSWORD,database:db});
 const gateway=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
 try {
  await control.query('DROP MASK RULE AA');
  const p=await api(path);assert.equal(p.runtimeMatches,false);assert(p.statementCount>0);
  const [rows]=await gateway.execute('SELECT id_card FROM AA WHERE id=?',[1]);assert.equal(rows[0].id_card,'110101********1234');
  console.log('PASS 运行规则不完整时仍使用旧生效快照脱敏');
  assert((await request(path,'POST',{expectedActiveVersion:p.expectedActiveVersion+1})).status>=400);
  const result=await api(path,'POST',{expectedActiveVersion:p.expectedActiveVersion});assert.equal(result.status,'RECOVERED_ABORTED');
  const verified=await api(path);assert.equal(verified.runtimeMatches,true);assert.equal(verified.expectedActiveVersion,p.expectedActiveVersion);
  console.log('PASS 拒绝过期确认，按生效快照修复并读回校验，生效版本未切换');
  await api('/api/publish','POST',{database:db});
  assert.equal((await api(path,'POST',{expectedActiveVersion:p.expectedActiveVersion})).status,'RECOVERED_ABORTED');
  const audits=await api('/api/audit');assert(audits.some(x=>x.action==='POLICY_RECOVERY_COMPLETED'&&x.detail.includes('version='+pending.id)));
  console.log('PASS 恢复解除发布阻断、重复确认幂等、完成审计已持久化');
 } finally {await Promise.all([control.end(),gateway.end()]);}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
