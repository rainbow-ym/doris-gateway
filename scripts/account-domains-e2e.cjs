// Isolated gateway only; real MySQL sessions are kept open throughout GRANT/REVOKE.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict'),crypto=require('crypto');
const db='gateway_multi_acceptance_20261014',user='权限版本_'+Date.now(),password=crypto.randomBytes(16).toString('hex'),next=crypto.randomBytes(16).toString('hex'),opened=[];
const base={host:'127.0.0.1',port:3312,database:db};
async function connect(name,secret){const c=await mysql.createConnection({...base,user:name,password:secret});opened.push(c);return c;}
function api(path,body){return new Promise((resolve,reject)=>{const q=http.request({hostname:'127.0.0.1',port:18086,path:'/api/'+path,method:'POST',auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json'}},r=>{let b='';r.on('data',x=>b+=x);r.on('end',()=>{if(r.statusCode>=300)return reject(Error('API status '+r.statusCode));resolve(JSON.parse(b));});});q.on('error',reject);q.end(JSON.stringify(body));});}
(async()=>{
 const root=await connect('root',process.env.DORIS_TEST_PASSWORD);let created=false;
 try{
  await root.query("CREATE USER '"+user+"'@'%' IDENTIFIED BY '"+password+"'");created=true;
  await root.query('GRANT SELECT_PRIV ON '+db+'.AA TO `'+user+'`');
  const c=await connect(user,password),session=c.threadId;
  const read=async()=>{const [r]=await c.execute('SELECT id_card FROM AA WHERE id=?',[1]);return r[0].id_card;};
  assert.equal(await read(),'110101********1234');
  await root.query('GRANT SELECT_PRIV ON '+db+'.UU TO `'+user+'`');
  await c.query('SELECT id FROM UU LIMIT 1');assert.equal(await read(),'110101********1234');assert.equal(c.threadId,session);
  console.log('PASS GRANT new table usable on same authenticated session');
  await root.query('REVOKE SELECT_PRIV ON '+db+'.AA FROM `'+user+'`');
  await assert.rejects(read,/denied|权限|privilege/i);await c.query('SELECT id FROM UU LIMIT 1');
  await root.query('GRANT SELECT_PRIV ON '+db+'.AA TO `'+user+'`');assert.equal(await read(),'110101********1234');assert.equal(c.threadId,session);
  console.log('PASS REVOKE blocks next query, unrelated grant remains usable, re-GRANT restores same prepared session');
  await root.query('REVOKE SELECT_PRIV ON '+db+'.UU FROM `'+user+'`');
  await root.query('REVOKE SELECT_PRIV ON '+db+'.AA FROM `'+user+'`');await assert.rejects(read);
  await root.query('GRANT SELECT_PRIV ON '+db+'.AA TO `'+user+'`');assert.equal(await read(),'110101********1234');assert.equal(c.threadId,session);
  console.log('PASS revoke last database privilege then regrant restores original session without reconnect');
  const direct=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:process.env.DORIS_TEST_PASSWORD});opened.push(direct);
  await direct.query('REVOKE SELECT_PRIV ON '+db+'.AA FROM `'+user+'`');await assert.rejects(read,/denied|权限|privilege/i);
  await direct.query('GRANT SELECT_PRIV ON '+db+'.AA TO `'+user+'`');assert.equal(await read(),'110101********1234');
  console.log('PASS direct Doris revoke/grant enforced on next query without waiting for gateway polling');
  await api('user-output-policies',{username:user,logicalName:db,sensitiveType:'ID_CARD',action:'CLEAR'});assert.equal(await read(),'110101199001011234');
  await api('user-output-policies',{username:user,logicalName:db,sensitiveType:'ID_CARD',action:'MASK',policyId:'ID_CARD'});assert.equal(await read(),'110101********1234');assert.equal(c.threadId,session);
  console.log('PASS output policy changes do not invalidate authentication or authorization');
  await root.query("SET PASSWORD FOR '"+user+"'@'%' = PASSWORD('"+next+"')");
  await assert.rejects(read);await assert.rejects(()=>connect(user,password));
  const fresh=await connect(user,next);await fresh.query('SELECT id FROM AA LIMIT 1');
  await root.query("DROP USER '"+user+"'@'%'");created=false;
  await assert.rejects(()=>fresh.query('SELECT id FROM AA LIMIT 1'));await assert.rejects(()=>connect(user,next));
  console.log('PASS PASSWORD and DROP still revoke old/new credentials appropriately');
 }finally{if(created)await root.query("DROP USER '"+user+"'@'%'");for(const c of opened)try{await c.end();}catch{}}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
