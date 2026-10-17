// Isolated test ports only. Rejected statements must not reach Doris.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),assert=require('assert/strict');
const db='gateway_multi_acceptance_20261014',table='txn_guard_'+Date.now();
(async()=>{
 const c=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
 const d=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
 async function denied(sql,prepared=false){
  await assert.rejects(()=>prepared?c.execute(sql):c.query(sql),e=>/显式事务/.test(e.message));
 }
 async function masked(){const [r]=await c.execute('SELECT id_card FROM AA WHERE id=?',[1]);assert.equal(r[0].id_card,'110101********1234');}
 try {
  await c.query('START TRANSACTION');
  await denied('/* DBeaver */ CREATE TABLE `'+table+'` PROPERTIES("replication_num"="1") AS SELECT id_card FROM AA');
  const [tables]=await d.query('SHOW TABLES LIKE ?',[table]);assert.equal(tables.length,0);
  await denied('INSERT INTO AA SELECT * FROM AA WHERE 1=2');
  await denied('DELETE FROM AA WHERE 1=2',true);
  await c.query('ROLLBACK');await masked();
  console.log('PASS BEGIN 内注释 CTAS、INSERT SELECT、预编译 DELETE 拒绝，未建物理表，ROLLBACK 后仍脱敏');
  await c.query('SET autocommit=0');
  await denied('INSERT INTO AA SELECT * FROM AA WHERE 1=2',true);
  await c.query('ROLLBACK');await c.query('SET autocommit=1');await masked();
  console.log('PASS 关闭自动提交时预编译写入拒绝，恢复自动提交后查询正常');
  await c.query('CREATE TABLE `'+table+'` PROPERTIES("replication_num"="1") AS SELECT id_card FROM AA');
  const [r]=await c.query('SELECT id_card FROM `'+table+'` LIMIT 1');assert(r[0].id_card.includes('*'));
  await c.query('DROP TABLE `'+table+'`');
  console.log('PASS 自动提交 CTAS 继续继承保护并正常删除');
 } finally {await c.end();await d.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
