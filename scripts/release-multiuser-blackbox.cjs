const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict'),cp=require('child_process'),crypto=require('crypto');
const e=Object.fromEntries(JSON.parse(cp.execFileSync('docker',['inspect','doris-security-gateway-admin']))[0].Config.Env.map(x=>{const i=x.indexOf('=');return[x.slice(0,i),x.slice(i+1)];}));
function api(path,method='GET',body){return new Promise((resolve,reject)=>{const q=http.request({host:'127.0.0.1',port:18082,path:'/api/'+path,method,auth:e.ADMIN_USERNAME+':'+e.ADMIN_PASSWORD,headers:{'Content-Type':'application/json'}},r=>{let s='';r.on('data',x=>s+=x);r.on('end',()=>{if(r.statusCode>=300)return reject(Error('API '+path+' status '+r.statusCode));try{resolve(JSON.parse(s));}catch(e){reject(e);}})});q.on('error',reject);q.end(body?JSON.stringify(body):undefined);});}
const db='gateway_mask_test',suffix=Date.now(),table='release_bb_'+suffix,copy=table+'_copy';
const users=['发布明文_'+suffix,'发布脱敏_'+suffix],password=crypto.randomBytes(18).toString('hex'),replacement=crypto.randomBytes(18).toString('hex');
const opts={host:'192.168.232.132',port:3308,database:db},opened=[],created=[];let tableMade=false,copyMade=false;
async function connect(user,pass){const c=await mysql.createConnection({...opts,user,password:pass});opened.push(c);return c;}
const q=s=>'`'+s.replace(/`/g,'``')+'`';
async function denied(fn){await assert.rejects(fn,/denied|权限|授权|凭据|用户|password|Access|不存在|not exist|Unknown database|不可用/i);}
(async()=>{
 const root=await connect('root',process.env.DORIS_TEST_PASSWORD);
 try {
  await root.query('CREATE TABLE '+q(table)+' PROPERTIES("replication_num"="1") AS SELECT 1 AS id, \'张三\' AS name, \'110101199001011234\' AS id_card, \'\' AS copy_value');tableMade=true;
  for(const user of users){await root.query("/* client tag */ CREATE USER '"+user+"'@'%' IDENTIFIED BY '"+password+"'");created.push(user);}
  assert((await api('connections')).filter(x=>users.includes(x.username)).length===2);
  await root.query('GRANT SELECT_PRIV ON '+db+'.* TO '+q(users[0]));
  await root.query('GRANT SELECT_PRIV ON '+db+'.'+q(table)+' TO '+q(users[1]));
  await api('user-output-policies','POST',{username:users[0],logicalName:db,sensitiveType:'ID_CARD',action:'CLEAR'});
  const b=await connect(users[0],password);let c=await connect(users[1],password);
  const read=async(conn,t=table)=>{const [r]=await conn.execute('SELECT id_card AS doc FROM '+q(t)+' WHERE id=?',[1]);return r[0].doc;};
  assert.equal(await read(b),'110101199001011234');assert.equal(await read(c),'110101********1234');
  await Promise.all(Array.from({length:10},async(_,i)=>assert.equal(await read(i%2?b:c),i%2?'110101199001011234':'110101********1234')));
  console.log('PASS Chinese CREATE USER auto registration, grants, per-user clear/masked policies and concurrent prepared queries');
  await root.query('CREATE TABLE '+q(copy)+' PROPERTIES("replication_num"="1") AS SELECT id,id_card FROM '+q(table));copyMade=true;
  assert.equal(await read(b,copy),'110101199001011234');await denied(()=>read(c,copy));
  await root.query('GRANT SELECT_PRIV ON '+db+'.'+q(copy)+' TO '+q(users[1]));c=await connect(users[1],password);assert.equal(await read(c,copy),'110101********1234');
  await denied(()=>c.query('INSERT INTO '+q(table)+' VALUES(2,"拒绝","123","")'));
  console.log('PASS CTAS shared lineage, database grant inheritance, table-only grant isolation and read-only enforcement');
  await root.query('REVOKE SELECT_PRIV ON '+db+'.'+q(table)+' FROM '+q(users[1]));await denied(()=>read(c));
  await root.query('GRANT SELECT_PRIV ON '+db+'.'+q(table)+' TO '+q(users[1]));c=await connect(users[1],password);assert.equal(await read(c),'110101********1234');
  await root.query("SET PASSWORD FOR '"+users[1]+"'@'%' = PASSWORD('"+replacement+"')");
  await denied(()=>read(c));await denied(()=>connect(users[1],password));
  const fresh=await connect(users[1],replacement);assert.equal(await read(fresh),'110101********1234');
  console.log('PASS revoke affects existing session, regrant works after reconnect, password rotation rejects old credentials/session');
  await root.query("DROP USER '"+users[1]+"'@'%'");created.splice(created.indexOf(users[1]),1);
  await denied(()=>read(fresh));await denied(()=>connect(users[1],replacement));
  const tasks=(await api('account-lifecycle/tasks')).filter(t=>users.includes(t.username));
  assert(!JSON.stringify(tasks).includes(password)&&!JSON.stringify(tasks).includes(replacement));
  console.log('PASS DROP USER blocks new/existing sessions and lifecycle API does not expose passwords');
 } finally {
  for(const user of created)await root.query("DROP USER '"+user+"'@'%'");
  if(copyMade)await root.query('DROP TABLE '+q(copy));if(tableMade)await root.query('DROP TABLE '+q(table));
  for(const c of opened)try{await c.end();}catch{}
  console.log('Temporary users and synthetic test tables cleaned');
 }
})().catch(e=>{console.error(e.message);process.exitCode=1;});
