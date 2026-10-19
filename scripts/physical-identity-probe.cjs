// Isolated synthetic database only. Does not alter gateway mappings or policies.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
const assert=require('assert/strict');
(async()=>{
 const db='gateway_multi_acceptance_20261014',name='identity_probe_'+Date.now(),renamed=name+'_renamed';
 const c=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
 let current='';
 try {
  const identity=async table=>{
   const [rows]=await c.execute('SELECT CATALOG_ID,DATABASE_ID,TABLE_ID FROM information_schema.metadata_name_ids WHERE CATALOG_NAME=? AND DATABASE_NAME=? AND TABLE_NAME=?',['internal',db,table]);
   assert.equal(rows.length,1);return JSON.stringify(rows[0]);
  };
  await c.query('CREATE TABLE `'+name+'` (id INT) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES("replication_num"="1")');current=name;
  const first=await identity(name);
  await c.query('ALTER TABLE `'+name+'` RENAME `'+renamed+'`');current=renamed;
  assert.equal(await identity(renamed),first);
  await c.query('DROP TABLE `'+renamed+'`');current='';
  await c.query('CREATE TABLE `'+renamed+'` (id INT) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES("replication_num"="1")');current=renamed;
  assert.notEqual(await identity(renamed),first);
  console.log('PASS Doris physical identity survives rename and changes after same-name same-schema replacement (root visibility only)');
 } finally {if(current)await c.query('DROP TABLE `'+current+'`');await c.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
