// Isolated 18086/3312 only. Synthetic test database; never prints plaintext or keys.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict');
const db='gateway_multi_acceptance_20261014';
function api(path,body){return new Promise((resolve,reject)=>{const q=http.request({hostname:'127.0.0.1',port:18086,path:'/api/'+path,method:body===undefined?'GET':'POST',auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json'}},r=>{let text='';r.on('data',x=>text+=x);r.on('end',()=>{if(r.statusCode>=300){const e=Error('API status '+r.statusCode);e.status=r.statusCode;return reject(e);}resolve(JSON.parse(text));});});q.on('error',reject);q.end(body===undefined?undefined:JSON.stringify(body));});}
function csv(rows){return rows.map(r=>r.map(v=>'"'+(v==null?'':String(v)).replace(/"/g,'""')+'"').join(',')).join('\r\n')+'\r\n';}
(async()=>{
 const c=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:process.env.DORIS_TEST_PASSWORD,database:db,rowsAsArray:true});
 const config={username:'root',databaseName:db,enabled:false,allowDecrypt:false,maxRows:20,ttlMinutes:10};
 const query=()=>c.execute('SELECT id, id_card AS `证件别名` FROM AA WHERE id=?',[1]);
 let tempUser,limited;
 try{
  await api('sm4-export/profiles',config);const [normal]=await query();assert(normal.length&&normal[0][1].includes('*'));
  await api('sm4-export/profiles',{...config,enabled:true,allowDecrypt:true});
  const [rows,fields]=await query();assert.equal(rows.length,normal.length+1);assert.equal(rows.at(-1)[0],null);assert(rows.at(-1)[1].startsWith('__GW_SM4_V1__:'));
  const file=csv([fields.map(x=>x.name),...rows]);const restored=await api('sm4-export/decrypt',{csv:file});assert(restored.csv.includes('110101199001011234'));assert(!restored.csv.includes('__GW_SM4_V1__:'));
  console.log('PASS prepared query aliases, unchanged columns, extra final row, authenticated CSV plaintext restoration');
  const [duplicates,duplicateFields]=await c.query('SELECT id_card AS repeated, id_card AS repeated FROM AA');
  const duplicateResult=await api('sm4-export/decrypt',{csv:csv([duplicateFields.map(x=>x.name),...duplicates])});
  assert.equal((duplicateResult.csv.match(/110101199001011234/g)||[]).length,normal.length*2);
  console.log('PASS duplicate column labels restore by position instead of name');
  await assert.rejects(()=>api('sm4-export/decrypt',{csv:file.replace('110101********1234','changed')}));
  await assert.rejects(()=>api('sm4-export/decrypt',{csv:csv([fields.map(x=>x.name),...rows.slice(0,-1)])}));
  await api('sm4-export/receipts/'+restored.id+'/revoke',{});await assert.rejects(()=>api('sm4-export/decrypt',{csv:file}),e=>e.status===403);
  console.log('PASS edited result, missing trailer and revoked file rejected');
  await api('sm4-export/profiles',{...config,enabled:true,allowDecrypt:false});
  const [denied,deniedFields]=await query();await assert.rejects(()=>api('sm4-export/decrypt',{csv:csv([deniedFields.map(x=>x.name),...denied])}),e=>e.status===403);
  console.log('PASS ciphertext possession does not grant decrypt permission');
  const [none]=await c.query('SELECT id FROM AA');assert.equal(none.length,normal.length);
  const [empty,emptyFields]=await c.query('SELECT id_card AS alias_doc FROM AA WHERE 1=2');assert.equal(empty.length,1);assert(empty[0][0].startsWith('__GW_SM4_V1__:'));
  await api('sm4-export/profiles',{...config,enabled:true,allowDecrypt:true});await api('sm4-export/decrypt',{csv:csv([emptyFields.map(x=>x.name),...empty])});
  console.log('PASS non-sensitive result unchanged; empty protected result has a decryptable header-only export');
  await api('sm4-export/profiles',{...config,enabled:true,maxRows:1});
  await assert.rejects(()=>c.query('SELECT id_card FROM AA UNION ALL SELECT id_card FROM AA'),/行数超限/);
  await assert.rejects(()=>c.query('SELECT id_card, CURRENT_TIMESTAMP() FROM AA'));
  console.log('PASS row overflow and unsupported result types fail before a complete result is delivered');
  tempUser='sm4_user_'+Date.now();const password=require('crypto').randomBytes(16).toString('hex');
  await c.query('CREATE USER `'+tempUser+'` IDENTIFIED BY ?', [password]);
  await c.query('GRANT SELECT_PRIV ON `'+db+'`.AA TO `'+tempUser+'`');
  await api('sm4-export/profiles',{...config,username:tempUser,enabled:true,allowDecrypt:true});
  limited=await mysql.createConnection({host:'127.0.0.1',port:3312,user:tempUser,password,database:db,rowsAsArray:true});
  const [restricted,restrictedFields]=await limited.query('SELECT id_card FROM AA');const restrictedCsv=csv([restrictedFields.map(x=>x.name),...restricted]);
  await api('sm4-export/decrypt',{csv:restrictedCsv});
  await c.query('REVOKE SELECT_PRIV ON `'+db+'`.AA FROM `'+tempUser+'`');
  await assert.rejects(()=>api('sm4-export/decrypt',{csv:restrictedCsv}));
  console.log('PASS revoking original Doris account permission prevents decrypting already-exported files');
 }finally{if(limited)await limited.end();if(tempUser)await c.query('DROP USER `'+tempUser+'`');await api('sm4-export/profiles',config);await c.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
