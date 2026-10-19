const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),assert=require('assert/strict');
(async()=>{
 const c=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:process.env.DORIS_TEST_PASSWORD});
 try {
  const [rows]=await c.query("SELECT TABLE_SCHEMA,TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('gateway_mask_test','DWD_人口') AND (TABLE_NAME LIKE 'release_bb_%' OR TABLE_NAME LIKE 'release_acceptance_%')");
  assert.equal(rows.length,0,'retained temporary table');
  const [users]=await c.query('SHOW ALL GRANTS');
  assert(!users.some(x=>Object.values(x).some(v=>/^发布(明文|脱敏)_/.test(String(v)))),'retained temporary user');
  console.log('PASS no temporary release tables or users remain in Doris');
 }finally{await c.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
