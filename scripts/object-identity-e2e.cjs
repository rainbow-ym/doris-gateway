// Isolated fixture only. Enables explicit identity baseline on the synthetic database.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
const http=require('http'),assert=require('assert/strict');
const db='gateway_identity_'+Date.now();
function api(path,method='GET',body){return new Promise((resolve,reject)=>{
 const q=http.request({hostname:'127.0.0.1',port:18086,path:'/api/'+path,method,auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json'}},r=>{
  let text='';r.on('data',x=>text+=x);r.on('end',()=>{if(r.statusCode>=300)return reject(Error(text));try{resolve(JSON.parse(text));}catch(e){reject(e);}});
 });q.on('error',reject);q.end(body?JSON.stringify(body):undefined);
});}
(async()=>{
 const secret=require('./test-secrets.cjs')('DORIS_TEST_PASSWORD');
 const direct=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:secret});
 let current='',replaced=false,gateway,connectionId,created=false;const users=[],sessions=[];
 try{
  await direct.query('CREATE DATABASE `'+db+'`');created=true;
  await direct.query('USE `'+db+'`');
  await direct.query('CREATE TABLE AA (id INT,id_card VARCHAR(32)) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES("replication_num"="1")');
  await direct.query("INSERT INTO AA VALUES(1,'110101199001011234')");
  connectionId=(await api('connections','POST',{name:db,host:'127.0.0.1',port:9030,username:'root',password:secret,databasePattern:db,tablePattern:'%'})).id;
  const jobs=(await api('connections/'+connectionId+'/onboard','POST',{databases:[db]})).jobs;assert.equal(jobs.length,1);
  for(let i=0;i<120;i++){
   const job=(await api('onboarding/jobs')).find(x=>x.id===jobs[0]);
   if(job&&job.status==='SUCCEEDED')break;
   if(job&&job.status==='FAILED')throw Error('Onboarding failed: '+job.message);
   if(i===119)throw Error('Onboarding timeout');
   await new Promise(r=>setTimeout(r,500));
  }
  gateway=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:secret,database:db});
  const path='object-identities/'+db,preview=await api(path+'/preview');assert.equal(preview.length,1);
  await api(path+'/confirm','POST',preview);
  const password=require('crypto').randomBytes(18).toString('hex');
  for(const scope of ['database','table']){
   const username='identity_'+scope+'_'+Date.now();
   await gateway.query('CREATE USER `'+username+'` IDENTIFIED BY ?', [password]);users.push(username);
   await gateway.query('GRANT SELECT_PRIV ON `'+db+'`.'+(scope==='database'?'*':'AA')+' TO `'+username+'`');
   sessions.push(await mysql.createConnection({host:'127.0.0.1',port:3312,user:username,password,database:db}));
  }
  await direct.query('CREATE TABLE external_source (id INT,id_card VARCHAR(32)) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES("replication_num"="1")');
  await direct.query("INSERT INTO external_source VALUES(1,'110101199001011234')");
  await api('connections/'+connectionId+'/scan','POST');
  const external=(await api('managed-objects')).find(x=>x.databaseName===db&&x.tableName==='external_source');assert(external);
  await assert.rejects(()=>gateway.query('SELECT * FROM external_source'));
  const sourcePath='object-identities/new-sources/'+external.id;
  const sourcePreview=await api(sourcePath+'/preview');
  await api(sourcePath+'/confirm','POST',sourcePreview);
  await assert.rejects(()=>gateway.query('SELECT * FROM external_source'));
  console.log('PASS external source identity confirmation alone does not open query access');
  await api('managed-objects/'+external.id+'/approve','POST');
  const [externalRows]=await gateway.query('SELECT id_card FROM external_source');assert.equal(externalRows[0].id_card,'110101********1234');
  console.log('PASS external source approval publishes protection before activation');
  const [inheritedAccess]=await sessions[0].query('SELECT id_card FROM external_source');assert.equal(inheritedAccess[0].id_card,'110101********1234');
  await assert.rejects(()=>sessions[1].query('SELECT id_card FROM external_source'),/权限|denied|privilege/i);
  console.log('PASS existing database-grant session accesses admitted source, table-only account remains denied');
  await assert.rejects(()=>api(sourcePath+'/confirm','POST',sourcePreview));
  console.log('PASS repeated new-source confirmation cannot reset an activated object');
  for(let round=0;round<2;round++){
   await gateway.query('CREATE TABLE normal_recreate PROPERTIES("replication_num"="1") AS SELECT id_card AS inherited FROM AA');
   const [rows]=await gateway.query('SELECT inherited FROM normal_recreate LIMIT 1');assert.equal(rows[0].inherited,'110101********1234');
   await gateway.query('DROP TABLE normal_recreate');
  }
  console.log('PASS gateway DROP and same-name CREATE use new generation without reusing old identity');
  const name='identity_e2e_'+Date.now(),renamed=name+'_renamed';
  await gateway.query('CREATE TABLE `'+name+'` PROPERTIES("replication_num"="1") AS SELECT id_card AS inherited FROM AA');current=name;
  const check=async()=>{const [r]=await gateway.query('SELECT inherited FROM `'+current+'` LIMIT 1');assert.equal(r[0].inherited,'110101********1234');};
  await check();console.log('PASS strict identity CTAS captures identity and inherits protection');
  await gateway.query('ALTER TABLE `'+name+'` RENAME `'+renamed+'`');current=renamed;
  await check();console.log('PASS rename preserves physical identity and protection');
  await gateway.query('INSERT INTO `'+current+'` SELECT id_card FROM AA');await check();
  console.log('PASS strict identity INSERT SELECT remains usable');
  // Recreate identical schema/data externally: only physical ID distinguishes the object.
  const [ddl]=await direct.query('SHOW CREATE TABLE `'+current+'`');
  const create=Object.values(ddl[0]).find(x=>typeof x==='string'&&/^CREATE TABLE/.test(x));assert(create);
  await direct.query('DROP TABLE `'+current+'`');replaced=true;
  await direct.query(create);
  await assert.rejects(()=>gateway.query('SELECT * FROM `'+current+'`'),/同名对象|物理身份|旧映射/);
  await assert.rejects(()=>gateway.query('SELECT * FROM `'+current+'`'),/物理身份|旧映射/);
  console.log('PASS same-name same-schema external replacement denied and stays quarantined');
  await assert.rejects(()=>api(path+'/confirm','POST',preview));
  console.log('PASS stale administrator baseline cannot clear quarantine');
 }finally{
  // Drop only this run's synthetic database; retain detached audit/identity records for diagnosis.
  for(const session of sessions)await session.end();
  for(const username of users)await gateway.query('DROP USER `'+username+'`');
  if(gateway)await gateway.end();
  if(connectionId)await api('logical-databases/'+db,'DELETE');
  if(created)await direct.query('DROP DATABASE `'+db+'`');
  await direct.end();
 }
})().catch(e=>{console.error(e.message);process.exitCode=1;});
