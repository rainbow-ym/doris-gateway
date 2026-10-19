// Read metadata as a temporary least-privilege Doris user, without gateway registration.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),assert=require('assert/strict'),crypto=require('crypto');
(async()=>{
 const db='gateway_multi_acceptance_20261014',user='identity_probe_'+Date.now(),password=crypto.randomBytes(18).toString('hex');
 const root=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD')});
 let created=false,c;
 try{
  await root.query('CREATE USER `'+user+'` IDENTIFIED BY ?', [password]);created=true;
  await root.query('GRANT SELECT_PRIV ON `'+db+'`.AA TO `'+user+'`');
  c=await mysql.createConnection({host:'127.0.0.1',port:9030,user,password,database:'information_schema'});
  const sql="SELECT CATALOG_ID,DATABASE_ID,TABLE_ID FROM information_schema.metadata_name_ids WHERE CATALOG_NAME='internal' AND DATABASE_NAME=? AND TABLE_NAME=?";
  const [expected]=await root.execute(sql,[db,'AA']);
  const [actual]=await c.execute(sql,[db,'AA']);assert.equal(actual.length,1);assert.deepEqual(actual,expected);
  console.log('PASS table-level SELECT account can read authorized table physical identity without root');
 }finally{if(c)await c.end();if(created)await root.query('DROP USER `'+user+'`');await root.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
