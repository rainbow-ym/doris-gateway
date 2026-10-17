const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict');
const user='gw_external_'+Date.now(),password=require('./test-secrets.cjs')('EXTERNAL_TEST_PASSWORD'),db='gateway_multi_acceptance_20261014';
function api(path,body){return new Promise((resolve,reject)=>{const req=http.request({hostname:'127.0.0.1',port:18086,path,method:body?'POST':'GET',auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json'}},res=>{let b='';res.on('data',s=>b+=s);res.on('end',()=>{try{if(res.statusCode!==200)throw Error('HTTP '+res.statusCode);resolve(JSON.parse(b))}catch(e){reject(e)}})});req.on('error',reject);req.end(body?JSON.stringify(body):'')});}
(async()=>{
 const direct=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD')}),root=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
 let c;
 try{
  await direct.query("CREATE USER "+user+" IDENTIFIED BY '"+password+"'");
  try{await root.query('GRANT SELECT_PRIV ON '+db+'.AA TO '+user);throw Error('expected pending credential')}catch(e){assert.match(e.message,/Doris 操作已成功/)}
  let task=(await api('/api/account-lifecycle/tasks')).find(t=>t.username===user);assert.equal(task.status,'NEEDS_CREDENTIALS');
  await api('/api/connections',{name:'外部用户验收-'+user,host:'127.0.0.1',port:9030,username:user,password,databasePattern:db,tablePattern:'%'});
  assert.equal((await api('/api/account-lifecycle/tasks/'+task.id+'/retry',{})).status,'SUCCEEDED');
  // A manual retry has no proxy callback; first connection may conservatively require one reconnect.
  for(let i=0;i<2;i++){c=await mysql.createConnection({host:'127.0.0.1',port:3312,user,password,database:db});try{await c.query('SELECT id_card FROM AA LIMIT 1');break}catch(e){await c.end();if(i===1)throw e;}}
  try{await root.query("CREATE USER IF NOT EXISTS "+user+" IDENTIFIED BY 'different'");throw Error('expected password conflict')}catch(e){assert.match(e.message,/密码不同/)}
  const [rows]=await c.query('SELECT id_card FROM AA LIMIT 1');assert(String(rows[0].id_card).includes('*'));
  await root.query('DROP USER '+user);
  console.log('PASS 外部创建用户授权后提示补凭据，登记后可恢复；IF NOT EXISTS 不覆盖既有密码或阻断有效用户');
 }finally{if(c)await c.end();await root.end();await direct.end();}
})().catch(e=>{console.error('FAIL',e.message);process.exitCode=1});
