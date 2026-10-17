const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict');
const db='gateway_multi_acceptance_20261014',suffix=Date.now(),users=['单个中文_'+suffix,'批量中文_'+suffix],password=require('./test-secrets.cjs')('UNICODE_TEST_PASSWORD');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function api(path,method='GET',body){return new Promise((resolve,reject)=>{const r=http.request({hostname:'127.0.0.1',port:18086,path,method,auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json'}},s=>{let b='';s.on('data',x=>b+=x);s.on('end',()=>{try{const result=JSON.parse(b);if(s.statusCode>=300)throw Error(result.message||JSON.stringify(result));resolve(result)}catch(e){reject(e)}})});r.on('error',reject);r.end(body?JSON.stringify(body):undefined);});}
async function masked(user){const c=await mysql.createConnection({host:'127.0.0.1',port:3312,user,password,database:db});try{const [r]=await c.execute('SELECT id_card FROM AA WHERE id=?',[1]);assert.equal(r[0].id_card,'110101********1234');await assert.rejects(c.query('INSERT INTO AA VALUES(999,"禁止写入","123")'));}finally{await c.end();}}
(async()=>{
 const direct=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD')});
 const gateway=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});const created=[];
 try {
  for(const user of users){await direct.query("CREATE USER '"+user+"'@'%' IDENTIFIED BY '"+password+"'");created.push(user);await direct.query("GRANT SELECT_PRIV ON "+db+".AA TO '"+user+"'@'%'");}
  const source=await api('/api/connections','POST',{name:users[0],host:'127.0.0.1',port:9030,username:users[0],password,databasePattern:db,tablePattern:'%'});
  await api('/api/connections/'+source.id+'/onboard','POST',{databases:[db]});await masked(users[0]);console.log('PASS 已有中文账号单个接入、原名登录、预编译脱敏及拒绝越权写入');
  const csv='username,password,databasePattern\n'+users[1]+','+password+','+db+'\n';
  const task=await api('/api/initialization/preview','POST',{host:'127.0.0.1',port:9030,csv});
  for(let n=0;;n++){const items=await api('/api/initialization/tasks/'+task.id);if(items[0].status==='READY')break;if(items[0].status==='FAILED'||n>=60)throw Error(JSON.stringify(items));await sleep(200);}
  await api('/api/initialization/tasks/'+task.id+'/confirm','POST');
  for(let n=0;;n++){const items=await api('/api/initialization/tasks/'+task.id);if(items[0].status==='SUCCEEDED')break;if(items[0].status==='FAILED'||n>=60)throw Error(JSON.stringify(items));await sleep(200);}
  await masked(users[1]);console.log('PASS 已有中文账号CSV验证、确认接入、网关登录及脱敏');
  await assert.rejects(api('/api/initialization/preview','POST',{host:'127.0.0.1',port:9030,csv:csv+users[1]+','+password+','+db+'\n'}),/第 3.*第 2.*重复/);
  console.log('PASS 重复中文账号单独报告记录位置，不误报格式错误');
 } finally {
  for(const user of created){try{await gateway.query("DROP USER '"+user+"'@'%'");}catch(e){await direct.query("DROP USER IF EXISTS '"+user+"'@'%'");}}
  await Promise.all([direct.end(),gateway.end()]);
 }
})().catch(e=>{console.error(e.message);process.exitCode=1;});
