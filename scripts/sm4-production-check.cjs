// Explicit target: authorized 132 deployment; synthetic gateway_mask_test only.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),cp=require('child_process'),assert=require('assert/strict');
const env=Object.fromEntries(JSON.parse(cp.execFileSync('docker',['inspect','doris-security-gateway-admin']))[0].Config.Env.map(x=>{const i=x.indexOf('=');return [x.slice(0,i),x.slice(i+1)];}));
function api(path,body){return new Promise((resolve,reject)=>{const r=http.request({host:'127.0.0.1',port:18082,path:'/api/'+path,auth:env.ADMIN_USERNAME+':'+env.ADMIN_PASSWORD,method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json'}},s=>{let t='';s.on('data',x=>t+=x);s.on('end',()=>{if(s.statusCode>=300)return reject(Error('HTTP '+s.statusCode));resolve(JSON.parse(t));});});r.setTimeout(20000,()=>r.destroy(Error('timeout')));r.on('error',reject);r.end(body===undefined?undefined:JSON.stringify(body));});}
function csv(rows){return rows.map(r=>r.map(v=>'"'+(v==null?'':String(v)).replace(/"/g,'""')+'"').join(',')).join('\r\n')+'\r\n';}
(async()=>{const staged=process.argv.includes('--staged');const c=await mysql.createConnection({host:staged?'127.0.0.1':'192.168.232.132',port:3308,user:'root',password:process.env.DORIS_TEST_PASSWORD,database:'gateway_mask_test',rowsAsArray:true});let restore;
 try{
  const sql='SELECT id,id_card AS document,name AS person FROM customer ORDER BY id LIMIT 3';
  const [baseline,bf]=await c.execute(sql);assert(baseline.length);assert(baseline.some(r=>String(r[1]).includes('*')));assert.equal(bf.length,3);
  assert((await api('logical-databases')).every(r=>r.status==='ACTIVE'));console.log('PASS 3308 existing masked query, prepared protocol and active logical databases');
  if(staged)return;
  const existing=(await api('sm4-export/profiles')).find(r=>r.username==='root'&&r.databaseName==='gateway_mask_test');restore=existing||{username:'root',databaseName:'gateway_mask_test',enabled:false,allowDecrypt:false,maxRows:20,ttlMinutes:10};
  await api('sm4-export/profiles',{...restore,enabled:true,allowDecrypt:true,maxRows:20});
  const [rows,fields]=await c.execute(sql);assert.equal(rows.length,baseline.length);assert.equal(fields.length,4);assert.equal(fields[3].name,'__gateway_sm4');for(const row of rows)assert(row[3].startsWith('__GW_SM4_V1__:'));
  const file=csv([fields.map(f=>f.name),...rows]);const result=await api('sm4-export/decrypt',{csv:file});
  const direct=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:process.env.DORIS_TEST_PASSWORD,database:'gateway_mask_test',rowsAsArray:true});
  try{const [plain]=await direct.execute(sql);assert.equal(result.csv.replace(/^\uFEFF/,''),csv([fields.slice(0,3).map(f=>f.name),...plain]));}finally{direct.destroy();}
  await assert.rejects(()=>api('sm4-export/decrypt',{csv:file.replace('__GW_SM4_V1__:','invalid:')}));
  console.log('PASS 3308 one extra encrypted column, row count unchanged, multi-field plaintext matches Doris, damaged ciphertext rejected');
 }finally{if(restore)await api('sm4-export/profiles',restore);c.destroy();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
