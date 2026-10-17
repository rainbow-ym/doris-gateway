const http=require('http'),assert=require('assert/strict'),cp=require('child_process'),mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
const user='gw_fault_'+Date.now(),password=require('./test-secrets.cjs')('FAULT_TEST_PASSWORD'),db='gateway_multi_acceptance_20261014';
function api(path,body){return new Promise((resolve,reject)=>{
 const r=http.request({hostname:'127.0.0.1',port:18086,path,method:body?'POST':'GET',auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,
 headers:{'Content-Type':'application/json','X-Gateway-Lineage-Token':process.env.LINEAGE_INGRESS_TOKEN}},res=>{let b='';res.on('data',s=>b+=s);res.on('end',()=>{try{resolve({status:res.statusCode,body:JSON.parse(b)})}catch(e){reject(e)}})});r.on('error',reject);r.end(body?JSON.stringify(body):'');
});}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 let restarted=false,root;
 try{
  cp.execFileSync('docker',['stop','gateway-account-proxy-test'],{stdio:'ignore'});
  const created=await api('/api/internal/sql/account-command',{actor:'root',database:db,sql:"CREATE USER "+user+" IDENTIFIED BY '"+password+"'"});
  assert.equal(created.status,400);assert.match(created.body.message,/Doris 操作已成功/);
  const task=(await api('/api/account-lifecycle/tasks')).body.find(t=>t.username===user);
  assert.equal(task.status,'SYNC_PENDING');console.log('PASS 故障注入：Doris 成功但网关停机，同步任务持久化且不误报整体成功');
  cp.execFileSync('docker',['start','gateway-account-proxy-test'],{stdio:'ignore'});restarted=true;
  for(let i=0;i<45;i++){
   await sleep(1000);
   const result=await api('/api/account-lifecycle/tasks/'+task.id+'/retry',{});
   if(result.body.status==='SUCCEEDED')break;
   if(i===44)throw Error('retry timeout');
  }
  console.log('PASS 恢复后仅重试同步，无需重放 CREATE USER');
  root=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
  await root.query('GRANT SELECT_PRIV ON '+db+'.AA TO '+user);
  const c=await mysql.createConnection({host:'127.0.0.1',port:3312,user,password,database:db});
  try{const [r]=await c.query('SELECT id_card FROM AA LIMIT 1');assert(String(r[0].id_card).includes('*'))}finally{await c.end()}
  await root.query('DROP USER '+user);
  console.log('PASS root 在已接入数据库连接中授权，恢复后的新用户正常脱敏访问并删除');
 }finally{if(root)await root.end();if(!restarted)cp.execFileSync('docker',['start','gateway-account-proxy-test'],{stdio:'ignore'});}
})().catch(e=>{console.error('FAIL',e.message);process.exitCode=1});
