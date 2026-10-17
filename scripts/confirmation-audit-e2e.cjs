// Isolated instance only. Read-only business query and correlated audit inspection.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict'),crypto=require('crypto');
function audit(){return new Promise((resolve,reject)=>{
 const req=http.get({hostname:'127.0.0.1',port:18086,path:'/api/audit',auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD},r=>{
  let text='';r.on('data',x=>text+=x);r.on('end',()=>{try{assert.equal(r.statusCode,200);resolve(JSON.parse(text));}catch(e){reject(e);}});
 });req.on('error',reject);
});}
(async()=>{
 const c=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:'gateway_multi_acceptance_20261014'});
 const marker='confirmation_'+Date.now(),sql='SELECT id_card FROM AA WHERE id=1 /* '+marker+' */';
 try {
  const [rows]=await c.query(sql);assert.equal(rows[0].id_card,'110101********1234');
  const digest=crypto.createHash('sha256').update(sql).digest('hex');
  const entries=(await audit()).filter(x=>String(x.detail).includes('sqlDigest='+digest));
  assert.equal(entries.length,2);assert.deepEqual(entries.map(x=>x.action).sort(),['USER_SQL_EXECUTED','USER_SQL_STARTED']);
  const ids=entries.map(x=>/requestId=([a-f0-9-]{36})/.exec(x.detail)?.[1]);assert(ids[0]);assert.equal(ids[0],ids[1]);
  assert(!JSON.stringify(entries).includes(marker));assert(!JSON.stringify(entries).includes('110101'));
  console.log('PASS 真实网关查询脱敏，STARTED/EXECUTED 审计请求编号一致且不记录 SQL 原文或结果');
 }finally{await c.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
