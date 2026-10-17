const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),assert=require('assert/strict'),crypto=require('crypto');
(async()=>{
 const db='DWD_人口',user='gw_release_'+Date.now(),password=crypto.randomBytes(24).toString('hex');
 const root=await mysql.createConnection({host:'192.168.232.132',port:3308,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
 let created=false,c;
 try{
  const start=Date.now();
  await root.query("/* release validation */ CREATE USER "+user+" IDENTIFIED BY '"+password+"'");created=true;
  await root.query('GRANT SELECT_PRIV ON `DWD_人口`.`人口关系网` TO '+user);
  c=await mysql.createConnection({host:'192.168.232.132',port:3308,user,password,database:db});
  const [rows]=await c.query('SELECT `姓名`,`证件号码` FROM `人口关系网` LIMIT 1');
  assert(rows.length>0);assert(String(rows[0]['证件号码']).includes('*'));
  console.log('PASS 正式 3308 创建用户、表级授权自动注册并脱敏查询，耗时 '+(Date.now()-start)+'ms；不打印敏感结果');
  await root.query('DROP USER '+user);created=false;
  let denied=false;try{await c.query('SELECT `证件号码` FROM `人口关系网` LIMIT 1')}catch{denied=true}assert(denied);
  console.log('PASS 正式删除测试账号，已有连接不能继续访问；保留同步审计记录 '+user);
 }finally{
  if(c)await c.end();
  if(created)try{await root.query('DROP USER '+user)}catch(e){console.error('测试用户需检查清理：'+user)}
  await root.end();
 }
})().catch(e=>{console.error('FAIL',e.message);process.exitCode=1});
