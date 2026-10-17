// Runs only against the isolated 3310 / 18081 acceptance instance.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
const http=require('http');
const assert=require('assert').strict;
const db='gateway_multi_acceptance_20261014';
const password=require('./test-secrets.cjs')('MULTI_USER_TEST_PASSWORD');
const users=['gw_multi_b_20261014','gw_multi_c_20261014'];
function api(path,method='GET',data){return new Promise((resolve,reject)=>{
  const body=data?JSON.stringify(data):'';
  const request=http.request({hostname:'127.0.0.1',port:Number(process.env.ACCEPTANCE_ADMIN_PORT||18081),path,method,auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,
    headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(body)}},response=>{let text='';response.on('data',x=>text+=x);response.on('end',()=>{let result;try{result=JSON.parse(text)}catch(e){return reject(e)};response.statusCode<300?resolve(result):reject(Error(JSON.stringify(result)));});});
  request.on('error',reject);request.end(body);
});}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function source(user,pass){let rows=await api('/api/connections');let row=rows.find(r=>r.username===user);if(row)return row.id;
  return (await api('/api/connections','POST',{name:'多用户验收 '+user,host:'127.0.0.1',port:9030,username:user,password:pass,databasePattern:db,tablePattern:'%'})).id;}
async function onboard(id){await api('/api/connections/'+id+'/onboard','POST',{databases:[db]});for(let i=0;i<40;i++){
  const jobs=(await api('/api/onboarding/jobs')).filter(x=>x.connectionId===id);if(!jobs.length||jobs[0].status==='SUCCEEDED')return;
  if(jobs[0].status==='FAILED')throw Error('接入失败 '+JSON.stringify(jobs[0]));await sleep(500);
}throw Error('接入超时');}
async function denied(c,sql){try{await c.query(sql)}catch(e){assert.match(e.message,/denied|privilege|权限/i);return;}throw Error('权限拒绝未生效：'+sql);}
async function main(){
 const root=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD')});
 const opened=[root];
 try {
  await root.query('CREATE DATABASE IF NOT EXISTS '+db);
  await root.query('CREATE TABLE IF NOT EXISTS '+db+'.AA (id INT, name VARCHAR(64), id_card VARCHAR(64)) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES("replication_num"="1")');
  await root.query('CREATE TABLE IF NOT EXISTS '+db+'.UU (id INT, id_card VARCHAR(64), copy_value VARCHAR(64)) UNIQUE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES("replication_num"="1","enable_unique_key_merge_on_write"="true")');
  await root.query('INSERT INTO '+db+'.UU VALUES (1,"110101199001011234","")');
  const [count]=await root.query('SELECT COUNT(*) AS n FROM '+db+'.AA');
  if(!count[0].n)await root.query('INSERT INTO '+db+'.AA VALUES (1,"张三","110101199001011234")');
  for(const user of users)await root.query("CREATE USER IF NOT EXISTS '"+user+"'@'%' IDENTIFIED BY '"+password+"'");
  await root.query("GRANT SELECT_PRIV ON "+db+".* TO '"+users[0]+"'@'%'");
  await root.query("GRANT SELECT_PRIV ON "+db+".AA TO '"+users[1]+"'@'%'");
  const owner=await source('root',require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'));await onboard(owner);
  await api('/api/connections/'+owner+'/scan','POST');
  for(const o of await api('/api/managed-objects'))if(o.tableName==='UU'&&o.databaseName===db&&['UNMANAGED','DRIFTED'].includes(o.status))await api('/api/managed-objects/'+o.id+'/approve','POST');
  const bid=await source(users[0],password),cid=await source(users[1],password);await onboard(bid);await onboard(cid);
  console.log('PASS 三账号共享同一逻辑库');
  await api('/api/user-output-policies','POST',{username:users[0],logicalName:db,sensitiveType:'ID_CARD',action:'CLEAR'});
  const b=await mysql.createConnection({host:'127.0.0.1',port:Number(process.env.ACCEPTANCE_PROXY_PORT||3310),user:users[0],password,database:db});opened.push(b);
  const c=await mysql.createConnection({host:'127.0.0.1',port:Number(process.env.ACCEPTANCE_PROXY_PORT||3310),user:users[1],password,database:db});opened.push(c);
  for(let i=0;i<3;i++) {
   const [br]=await b.query('SELECT id_card AS alias_id FROM AA');assert.equal(br[0].alias_id,'110101199001011234');
   const [cr]=await c.execute('SELECT id_card AS alias_id FROM AA WHERE id=?',[1]);assert.equal(cr[0].alias_id,'110101********1234');
  }
  console.log('PASS B 明文、C 脱敏，交替查询与服务端预编译');
  for(let i=0;i<5;i++) {
    const [br,cr]=await Promise.all([b.execute('SELECT id_card FROM AA WHERE id=?',[1]),c.execute('SELECT id_card FROM AA WHERE id=?',[1])]);
    assert.equal(br[0][0].id_card,'110101199001011234');assert.equal(cr[0][0].id_card,'110101********1234');
  }
  console.log('PASS 并发用户预编译查询身份及策略隔离');
  const gr=await mysql.createConnection({host:'127.0.0.1',port:Number(process.env.ACCEPTANCE_PROXY_PORT||3310),user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});opened.push(gr);
  await gr.query('UPDATE UU SET copy_value=id_card WHERE id=1');
  await root.query("GRANT SELECT_PRIV ON "+db+".UU TO '"+users[1]+"'@'%'");
  assert.equal((await c.query('SELECT copy_value FROM UU'))[0][0].copy_value,'110101********1234');
  await denied(c,"UPDATE UU SET copy_value='bad' WHERE id=1");
  console.log('PASS UPDATE 敏感字段传播及无写权限拒绝');
  const zz='ZZ_'+Date.now();
  await gr.query('/* DBeaver acceptance */ CREATE TABLE '+zz+' PROPERTIES("replication_num"="1") AS SELECT id,name,id_card FROM AA');
  const [br]=await b.query('SELECT id_card FROM '+zz);assert.equal(br[0].id_card,'110101199001011234');
  await denied(c,'SELECT id_card FROM '+zz);
  console.log('PASS root CTAS 后 B 可读新表、C 单表权限未扩散');
  await root.query("GRANT SELECT_PRIV ON "+db+'.'+zz+" TO '"+users[1]+"'@'%'");
  assert.equal((await c.query('SELECT id_card FROM '+zz))[0][0].id_card,'110101********1234');
  console.log('PASS 后续单表授权立即生效，CTAS 标签对 C 脱敏');
  await gr.query('INSERT INTO '+zz+' SELECT id,name,id_card FROM AA');
  assert.equal((await root.query('SELECT id_card FROM '+db+'.'+zz))[0][0].id_card,'110101199001011234');
  console.log('PASS INSERT SELECT 保留物理明文并共享标签');
  await gr.query('ALTER TABLE '+zz+' ADD COLUMN remark VARCHAR(64)');
  await gr.query('ALTER TABLE '+zz+' RENAME '+zz+'_renamed');
  assert.equal((await b.query('SELECT id_card FROM '+zz+'_renamed'))[0][0].id_card,'110101199001011234');
  console.log('PASS ALTER 与 RENAME 后 B 库级权限保持正确');
  await root.query("REVOKE SELECT_PRIV ON "+db+".* FROM '"+users[0]+"'@'%'");
  await denied(b,'SELECT id_card FROM AA');
  await root.query("GRANT SELECT_PRIV ON "+db+".* TO '"+users[0]+"'@'%'");
  assert.equal((await b.query('SELECT id_card FROM AA'))[0][0].id_card,'110101199001011234');
  console.log('PASS 已有会话撤权立即拒绝、重新授权立即恢复');
  await denied(c,'INSERT INTO AA VALUES (2,"李四","110101199001011235")');
  console.log('PASS 只读账号不能写入');
  await gr.query('DELETE FROM '+zz+'_renamed WHERE id=1');
  assert.equal((await b.query('SELECT COUNT(*) AS n FROM '+zz+'_renamed'))[0][0].n,0);
  await gr.query('DROP TABLE '+zz+'_renamed');
  console.log('PASS 网关 DROP 同步');
  await api('/api/connections/'+bid+'/refresh-permissions','POST');
  await api('/api/connections/'+cid+'/refresh-permissions','POST');
  console.log('PASS 授权快照与对象可见性刷新');
  const projected=await api('/api/connections/'+bid+'/access');
  assert(projected.some(p=>p.tableName==='AA'&&p.privilege==='SELECT_PRIV'&&p.state==='ALLOWED'));
  assert(projected.filter(p=>p.tableName===zz+'_renamed'&&p.privilege!=='VISIBILITY').every(p=>p.state==='DENIED'));
  console.log('PASS 库级权限投影与删除对象失效');
 } finally {for(const c of opened)await c.end();}
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
