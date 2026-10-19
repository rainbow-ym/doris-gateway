const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),assert=require('assert/strict'),cp=require('child_process'),http=require('http');
const e=Object.fromEntries(JSON.parse(cp.execFileSync('docker',['inspect','doris-security-gateway-admin']))[0].Config.Env.map(x=>{const i=x.indexOf('=');return[x.slice(0,i),x.slice(i+1)];}));
const api=(path,method='GET',body)=>new Promise((resolve,reject)=>{const q=http.request({host:'127.0.0.1',port:Number(e.ADMIN_PORT),path:'/api/'+path,method,auth:e.ADMIN_USERNAME+':'+e.ADMIN_PASSWORD,headers:{'Content-Type':'application/json'}},r=>{let s='';r.on('data',x=>s+=x);r.on('end',()=>{if(r.statusCode>=300)return reject(Error('API '+path+' status '+r.statusCode));try{resolve(JSON.parse(s));}catch(err){reject(err);}});});q.on('error',reject);q.end(body?JSON.stringify(body):undefined);});
(async()=>{
 const host=process.argv.includes('--staged')?'127.0.0.1':'192.168.232.132';
 const c=await mysql.createConnection({host,port:3308,user:'root',password:process.env.DORIS_TEST_PASSWORD,database:'DWD_人口'});
 try{
  console.log('CHECK ordinary aliased query');
  const [rows]=await c.query('SELECT `证件号码` AS doc, `姓名` AS person FROM `人口关系网` LIMIT 5');assert(rows.length>0);
  assert(rows.some(r=>String(r.doc).includes('*')),'expected masked document');
  console.log('CHECK nested aliased prepared query');
  const [nested]=await c.execute('SELECT t.alias_doc FROM (SELECT `证件号码` AS alias_doc FROM `人口关系网`) t WHERE ? = 1 LIMIT 5',[1]);
  assert(nested.some(r=>String(r.alias_doc).includes('*')),'nested alias protection');
  console.log('PASS production root login, document masking, aliases, subquery, server prepared query (values withheld)');
  const mappings=await api('logical-databases');assert.equal(mappings.length,3);assert(mappings.every(x=>x.status==='ACTIVE'));
  for(const m of mappings){const v=await api('upgrade/policies/verify','POST',{database:m.logicalName});assert(v);}
  console.log('PASS three active databases policy verification; absent mappings removed');
  if(!process.argv.includes('--write'))return;
  const table='release_acceptance_'+Date.now(),renamed=table+'_renamed';let current='';
  try{
   await c.query('/* DBeaver release blackbox */ CREATE TABLE `'+table+'` PROPERTIES("replication_num"="1") AS SELECT `证件号码` AS protected_doc FROM `人口关系网` LIMIT 3');current=table;
   const check=async()=>{const [r]=await c.execute('SELECT protected_doc AS result_alias FROM `'+current+'` LIMIT 3');assert(r.length&&r.some(x=>String(x.result_alias).includes('*')));};
   await check();console.log('PASS commented CTAS materialized lineage inherited');
   await c.query('INSERT INTO `'+current+'` SELECT `证件号码` FROM `人口关系网` LIMIT 1');await check();
   await c.query('ALTER TABLE `'+current+'` ADD COLUMN remark VARCHAR(64)');await check();
   await c.query('/* client tag */ ALTER TABLE `'+current+'` RENAME `'+renamed+'`');current=renamed;await check();
   console.log('PASS INSERT SELECT, ALTER, RENAME retain output protection');
   await c.query('START TRANSACTION');try{await assert.rejects(c.query('DELETE FROM `'+current+'`'));}finally{await c.query('ROLLBACK');}
   await check();console.log('PASS explicit transaction mutation rejected; rollback leaves protected data');
  } finally {if(current)await c.query('DROP TABLE `'+current+'`');}
  await assert.rejects(c.query('SELECT * FROM `'+renamed+'`'));console.log('PASS DROP removes gateway visibility; synthetic table cleaned');
 }finally{await c.end();}
})().catch(err=>{console.error(err.message);process.exitCode=1;});
