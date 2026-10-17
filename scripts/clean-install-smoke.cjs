const http=require('http'),assert=require('assert/strict');
let cookie='',csrf,header;
function request(port,path,method='GET',data,worker=false){return new Promise((resolve,reject)=>{
 const body=typeof data==='string'?data:data?JSON.stringify(data):'';
 const headers={'Content-Type':typeof data==='string'?'application/x-www-form-urlencoded':'application/json','Cookie':cookie};if(csrf)headers[header]=csrf;
 const r=http.request({host:'127.0.0.1',port,path,method,headers,auth:worker?process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD:undefined},res=>{
  if(res.headers['set-cookie'])cookie=res.headers['set-cookie'].map(s=>s.split(';')[0]).join('; ');
  let b='';res.on('data',s=>b+=s);res.on('end',()=>{try{if(res.statusCode>=300)throw Error('HTTP '+res.statusCode);resolve(b?JSON.parse(b):{})}catch(e){reject(e)}});
 });r.on('error',reject);r.end(body);
});}
(async()=>{
 for(let n=0;;n++){try{await request(18180,'/api/health');break}catch(e){if(n>=30)throw e;await new Promise(r=>setTimeout(r,1000));}}
 const worker=path=>request(18182,path,'GET',null,true);
 assert.equal((await worker('/api/connections')).length,0);
 assert.equal((await worker('/api/field-mappings')).length,0);
 assert.equal((await worker('/api/account-lifecycle/tasks')).length,0);
 const policies=await worker('/api/policies');assert.equal(policies.filter(p=>p.columnNames&&p.columnNames.length).length,4);
 let s=await request(18180,'/api/session');assert(!s.authenticated);csrf=s.csrf;header=s.csrfHeader;
 await request(18180,'/login','POST',new URLSearchParams({username:process.env.PLATFORM_ADMIN_USER,password:process.env.PLATFORM_ADMIN_PASSWORD}).toString());
 s=await request(18180,'/api/session');assert(s.authenticated);csrf=s.csrf;header=s.csrfHeader;
 const before=await request(18180,'/api/clusters');assert(before.length===0||before.length===1&&before[0].name==='全新安装验收');
 if(!before.length)await request(18180,'/api/clusters','POST',{name:'全新安装验收',workerUrl:'http://127.0.0.1:18182',workerUsername:process.env.ADMIN_USERNAME,workerPassword:process.env.ADMIN_PASSWORD,gatewayAddress:'127.0.0.1:3316'});
 const clusters=await request(18180,'/api/clusters');assert.equal(clusters.length,1);
 assert.equal((await request(18180,'/api/clusters/'+clusters[0].id+'/relay/api/connections')).length,0);
 await request(18180,'/logout','POST');
 console.log('PASS Docker 1.13.1 全新安装：随机管理员登录、空集群登记、空数据源/映射/任务、四类内置规则；未导入生产数据');
})().catch(e=>{console.error(e.message);process.exitCode=1});
