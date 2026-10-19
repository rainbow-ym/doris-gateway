// Isolated 3312/18086 only. Retains failed fixtures for diagnosis; never drops unrelated objects.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict');
const db='gateway_multi_acceptance_20261014',stage=process.argv[2],table='rename_recovery_'+stage;
assert(['registry','policy'].includes(stage));
function api(path,body){return new Promise((resolve,reject)=>{const q=http.request({hostname:'127.0.0.1',port:18086,path,method:body?'POST':'GET',auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json','X-Gateway-Lineage-Token':process.env.LINEAGE_INGRESS_TOKEN}},r=>{let s='';r.on('data',x=>s+=x);r.on('end',()=>{if(r.statusCode>=300)return reject(Error(s));try{resolve(JSON.parse(s));}catch(e){reject(e);}})});q.on('error',reject);q.end(body?JSON.stringify(body):undefined);});}
(async()=>{
 const options={host:'127.0.0.1',user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db};
 const c=await mysql.createConnection({...options,port:3312});
 try {
  if(process.argv.includes('--prepare')) {
   await c.query('CREATE TABLE '+table+' PROPERTIES("replication_num"="1") AS SELECT id_card FROM AA');
   const sql='ALTER TABLE '+table+' RENAME '+table+'_new';
   if(stage==='registry') {
    const mapping=(await api('/api/logical-databases')).find(x=>x.logicalName===db);assert(mapping);
    await api('/api/internal/sql/prepare',{storageUnit:mapping.storageUnit,rawSql:sql});
    const direct=await mysql.createConnection({...options,port:9030});
    try{await direct.query(sql);}finally{await direct.end();}
   }else await c.query(sql);
   console.log('PASS prepared isolated '+stage+' fixture; next stop worker for fault injection');return;
  }
  const tasks=await api('/api/managed-executions');
  const task=tasks.find(x=>x.targetTable===table&&x.statementType==='RENAME'&&x.status==='FAILED');assert(task);
  await assert.rejects(c.query('SELECT id_card FROM '+table+'_new LIMIT 1'));
  const versions=(await api('/api/versions')).length;
  await api('/api/managed-executions/'+task.executionId+'/reconcile',{});
  assert.equal((await api('/api/managed-executions')).find(x=>x.executionId===task.executionId).status,'COMMITTED');
  if(stage==='policy')assert.equal((await api('/api/versions')).length,versions);
  const [rows]=await c.execute('SELECT id_card FROM '+table+'_new LIMIT 1');assert.equal(rows[0].id_card,'110101********1234');
  await c.query('DROP TABLE '+table+'_new');
  console.log('PASS '+stage+' restart recovery, blocked before recovery, masked after recovery, fixture removed');
 }finally{await c.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
