const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
const http=require('http');
const assert=require('assert').strict;
function api(path){return new Promise((resolve,reject)=>{const r=http.get({hostname:'127.0.0.1',port:18080,path,auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD},res=>{let s='';res.on('data',x=>s+=x);res.on('end',()=>{try{if(res.statusCode!==200)throw Error('HTTP '+res.statusCode);resolve(JSON.parse(s))}catch(e){reject(e)}})});r.on('error',reject);});}
(async()=>{
 const health=await api('/api/health');assert.equal(health.version,'2026.10.14-multi-user.1');
 const bindings=await api('/api/account-bindings');assert(bindings.length>0);
 console.log('PASS 正式管理端版本与旧账号关联迁移，关联数='+bindings.length);
 const direct=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD')});
 let gateway;
 try{
  gateway=await mysql.createConnection({host:'192.168.232.132',port:3308,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:'DWD_人口'});
  const sql='SELECT `姓名`,`证件号码` FROM `DWD_人口`.`人口关系网` LIMIT 1';
  const [raw]=await direct.query(sql),[masked]=await gateway.query(sql);
  assert(raw.length>0&&masked.length>0);
  assert.notEqual(masked[0]['证件号码'],raw[0]['证件号码']);assert(String(masked[0]['证件号码']).includes('*'));
  console.log('PASS 正式 3308 root 登录及人口表返回脱敏（不输出敏感数据）');
 }finally{await direct.end();if(gateway)await gateway.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
