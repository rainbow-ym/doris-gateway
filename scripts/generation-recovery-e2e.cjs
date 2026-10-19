const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict');
const db='gateway_multi_acceptance_20261014',table='generation_recovery_fixture';
function api(path,method='GET'){return new Promise((resolve,reject)=>{const q=http.request({hostname:'127.0.0.1',port:18086,path,method,auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD},r=>{let s='';r.on('data',x=>s+=x);r.on('end',()=>{try{resolve({status:r.statusCode,body:JSON.parse(s)});}catch(e){reject(e);}})});q.on('error',reject);q.end();});}
(async()=>{
 const c=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:'1',database:db});
 try{
  if(process.argv.includes('--prepare')) {
   // No IF EXISTS: do not overwrite a retained failed test.
   await c.query('CREATE TABLE '+table+' PROPERTIES("replication_num"="1") AS SELECT id_card FROM AA');
   await c.query('DROP TABLE '+table);
   await c.query('CREATE TABLE '+table+' PROPERTIES("replication_num"="1") AS SELECT id_card FROM AA');
   console.log('PASS 已通过网关同名删表重建，等待冷库注入旧任务');return;
  }
  const rows=(await api('/api/managed-executions')).body;
  const old=rows.find(x=>x.targetTable===table&&x.message==='GENERATION_RECOVERY_FIXTURE');assert(old);
  const before=(await api('/api/managed-objects')).body.find(x=>x.tableName===table);assert.equal(before.status,'ACTIVE');
  const result=await api('/api/managed-executions/'+old.executionId+'/reconcile','POST');assert(result.status>=400);
  const after=(await api('/api/managed-objects')).body.find(x=>x.tableName===table);assert.deepEqual(after,before);
  assert.equal((await api('/api/managed-executions')).body.find(x=>x.executionId===old.executionId).status,'STALE_EXECUTION');
  const [data]=await c.query('SELECT id_card FROM '+table+' WHERE id_card IS NOT NULL LIMIT 1');assert(data[0].id_card.includes('*'));
  await c.query('DROP TABLE '+table);
  console.log('PASS 重启后旧任务被隔离，同名新表的状态及脱敏不受影响，测试表已删除');
 }finally{await c.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
