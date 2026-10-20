// Synthetic data, isolated 18086 / 3312 only. No plaintext or keys in output.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict');
const db='gateway_multi_acceptance_20261014';
function api(path,body,authorized=true){return new Promise((resolve,reject)=>{const q=http.request({hostname:'127.0.0.1',port:18086,path:'/api/'+path,method:body===undefined?'GET':'POST',auth:authorized?process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD:undefined,headers:{'Content-Type':'application/json'}},r=>{let text='';r.on('data',x=>text+=x);r.on('end',()=>{if(r.statusCode>=300){const e=Error('API status '+r.statusCode);e.status=r.statusCode;return reject(e);}resolve(JSON.parse(text));});});q.setTimeout(30000,()=>q.destroy(Error('API timeout')));q.on('error',reject);q.end(body===undefined?undefined:JSON.stringify(body));});}
function csv(rows){return rows.map(r=>r.map(v=>'"'+(v==null?'':String(v)).replace(/"/g,'""')+'"').join(',')).join('\r\n')+'\r\n';}
(async()=>{
 const c=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:process.env.DORIS_TEST_PASSWORD,database:db,rowsAsArray:true});
 const config={username:'root',databaseName:db,enabled:false,allowDecrypt:false,maxRows:20,ttlMinutes:10};let limited,tempUser;
 try{
  await api('sm4-export/profiles',config);
  const [baseline]=await c.execute('SELECT id,id_card AS doc,id_card AS related_doc FROM AA WHERE id=?',[1]);
  await api('sm4-export/profiles',{...config,enabled:true,allowDecrypt:true});
  const [rows,fields]=await c.execute('SELECT id,id_card AS doc,id_card AS related_doc FROM AA WHERE id=?',[1]);
  assert.equal(rows.length,baseline.length);assert.equal(fields.length,4);assert.equal(fields[3].name,'__gateway_sm4');
  for(const r of rows){assert(r[1].includes('*'));assert(r[2].includes('*'));assert(r[3].startsWith('__GW_SM4_V1__:'));}
  const file=csv([fields.map(f=>f.name),...rows]);const clear=await api('sm4-export/decrypt',{csv:file});assert.equal((clear.csv.match(/110101199001011234/g)||[]).length,rows.length*2);assert(!clear.csv.includes('__gateway_sm4'));
  console.log('PASS prepared protocol: unchanged row count, one new last column, multiple protected fields in one row ciphertext, decrypt restores both');
  const [duplicate,df]=await c.query('SELECT id_card AS same,id_card AS same FROM AA UNION ALL SELECT id_card AS same,id_card AS same FROM AA');
  assert.equal(df.length,3);assert(duplicate.length>=2);assert.notEqual(duplicate[0][2],duplicate[1][2]);
  await api('sm4-export/decrypt',{csv:csv([df.map(f=>f.name),...duplicate.reverse().slice(0,1)])});
  console.log('PASS text protocol, repeated column aliases, duplicate masked rows, whole-row sorting and partial export');
  await assert.rejects(()=>api('sm4-export/decrypt',{csv:file.replace('110101********1234','changed')}));
  await assert.rejects(()=>api('sm4-export/decrypt',{csv:csv([fields.slice(0,-1).map(f=>f.name),...rows.map(r=>r.slice(0,-1))])}));
  await assert.rejects(()=>api('sm4-export/decrypt',{csv:file},false));
  console.log('PASS tampering, missing encrypted column and unauthenticated decrypt rejected');
  const [empty,ef]=await c.query('SELECT id_card FROM AA WHERE 1=2');assert.equal(empty.length,0);assert.equal(ef.at(-1).name,'__gateway_sm4');await api('sm4-export/decrypt',{csv:csv([ef.map(f=>f.name)])});
  const [plain,pf]=await c.query('SELECT id FROM AA');assert.equal(pf.length,1);
  await assert.rejects(()=>c.query('SELECT id_card AS __gateway_sm4 FROM AA'));
  console.log('PASS empty result has no extra row; non-sensitive result unchanged; reserved column conflict rejected');
  await api('sm4-export/profiles',{...config,enabled:true,allowDecrypt:false});await assert.rejects(()=>api('sm4-export/decrypt',{csv:file}),e=>e.status===403);
  await api('sm4-export/profiles',{...config,enabled:true,allowDecrypt:true,maxRows:1});await assert.rejects(()=>c.query('SELECT id_card FROM AA UNION ALL SELECT id_card FROM AA'),/行数超限/);
  await api('sm4-export/profiles',{...config,enabled:true,allowDecrypt:true});
  const receipt=(await api('sm4-export/receipts')).find(r=>r.username==='root');assert(receipt);await api('sm4-export/receipts/'+receipt.id+'/revoke',{});
  tempUser='sm4row_'+Date.now();const password=require('crypto').randomBytes(16).toString('hex');
  await c.query('CREATE USER `'+tempUser+'` IDENTIFIED BY ?',[password]);await c.query('GRANT SELECT_PRIV ON `'+db+'`.AA TO `'+tempUser+'`');
  await api('sm4-export/profiles',{...config,username:tempUser,enabled:true,allowDecrypt:true});
  limited=await mysql.createConnection({host:'127.0.0.1',port:3312,user:tempUser,password,database:db,rowsAsArray:true});
  const [lr,lf]=await limited.query('SELECT id_card FROM AA');const limitedFile=csv([lf.map(f=>f.name),...lr]);await api('sm4-export/decrypt',{csv:limitedFile});
  await c.query('REVOKE SELECT_PRIV ON `'+db+'`.AA FROM `'+tempUser+'`');await assert.rejects(()=>api('sm4-export/decrypt',{csv:limitedFile}));
  console.log('PASS decrypt switch, row bound and real Doris permission revocation');
 }finally{if(limited)await limited.end();if(tempUser)await c.query('DROP USER `'+tempUser+'`');await api('sm4-export/profiles',config);await c.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
