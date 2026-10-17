const http=require('http'),assert=require('assert').strict,mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
let cookie='',csrf,header;
function request(path,method='GET',body){return new Promise((resolve,reject)=>{const data=typeof body==='string'?body:body?JSON.stringify(body):'';const headers={'Cookie':cookie,'Content-Type':typeof body==='string'?'application/x-www-form-urlencoded':'application/json','Content-Length':Buffer.byteLength(data)};if(csrf)headers[header]=csrf;const req=http.request({hostname:'192.168.232.132',port:18080,path,method,headers},res=>{if(res.headers['set-cookie'])cookie=res.headers['set-cookie'].map(s=>s.split(';')[0]).join('; ');let text='';res.on('data',x=>text+=x);res.on('end',()=>{let j={};try{j=JSON.parse(text)}catch(e){}res.statusCode<300?resolve(j):reject(Error(path+' HTTP '+res.statusCode+' '+(j.message||'')))})});req.on('error',reject);req.end(data)})}
(async()=>{
 const health=await request('/api/health');assert.equal(health.version,'2026.10.15-accounts.1');
 let session=await request('/api/session');csrf=session.csrf;header=session.csrfHeader;
 await request('/login','POST',new URLSearchParams({username:process.env.ADMIN_USERNAME,password:process.env.ADMIN_PASSWORD}).toString());
 session=await request('/api/session');csrf=session.csrf;header=session.csrfHeader;assert(session.authenticated);
 let clusters=await request('/api/clusters');
 if(clusters.length===0){await request('/api/clusters','POST',{name:'默认 Doris 集群（132）',workerUrl:'http://127.0.0.1:18082',workerUsername:process.env.ADMIN_USERNAME,workerPassword:process.env.ADMIN_PASSWORD,gatewayAddress:'192.168.232.132:3308'});clusters=await request('/api/clusters')}
 const cluster=clusters.find(c=>c.workerUrl==='http://127.0.0.1:18082');assert(cluster);
 const bindings=await request('/api/clusters/'+cluster.id+'/relay/api/account-bindings');assert(bindings.length>0);
 console.log('PASS 正式管理员登录、默认集群登记及旧账号关联，关联数='+bindings.length);
 const c=await mysql.createConnection({host:'192.168.232.132',port:3308,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:'DWD_人口'});
 try{const [rows]=await c.query('SELECT `姓名`,`证件号码` FROM `人口关系网` LIMIT 1');assert(rows.length>0);assert(String(rows[0]['证件号码']).includes('*'));console.log('PASS 正式 3308 root 查询及人口表返回脱敏，不输出真实敏感数据')}finally{await c.end()}
 await request('/logout','POST');assert.equal((await request('/api/session')).authenticated,false);console.log('PASS 正式退出登录');
})().catch(e=>{console.error(e.message);process.exitCode=1});
