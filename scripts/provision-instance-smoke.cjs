// Read-only checks against a newly provisioned, empty acceptance instance.
const http=require('http'),assert=require('assert').strict;
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
function api(path,method='GET',body){return new Promise((resolve,reject)=>{
 const data=body?JSON.stringify(body):'';
 const req=http.request({hostname:'127.0.0.1',port:Number(process.env.ADMIN_PORT),path,method,
  auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(data)}},res=>{
  let body='';res.on('data',x=>body+=x);res.on('end',()=>{try{assert.equal(res.statusCode,200);resolve(JSON.parse(body))}catch(e){reject(e)}});
 });req.on('error',reject);req.end(data);
});}
(async()=>{
 const identity=await api('/api/instance');assert(identity.multiUserEnabled);assert.match(identity.id,/^[a-f0-9-]{36}$/);
 const sources=await api('/api/connections');assert(sources.every(s=>s.name==='部署脚本验收数据源'));
 console.log('PASS 新实例具备独立身份，未继承其他集群的连接配置');
 const c=await mysql.createConnection({host:process.env.PROXY_HOST,port:Number(process.env.PROXY_PORT),user:process.env.PROXY_ADMIN_USER,password:process.env.PROXY_ADMIN_PASSWORD,database:'information_schema'});
 try{const [rows]=await c.query('EXPORT METADATA');assert(rows.length>0);console.log('PASS 随机生成的控制账号可连接新网关并读取初始化元数据')}finally{await c.end()}
 const db='gateway_multi_acceptance_20261014';
 const source=sources[0]||await api('/api/connections','POST',{name:'部署脚本验收数据源',host:'127.0.0.1',port:9030,username:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),databasePattern:db,tablePattern:'%'});
 const submitted=await api('/api/connections/'+source.id+'/onboard','POST',{databases:[db]});
 for(let i=0;i<120;i++){
  const jobs=(await api('/api/onboarding/jobs')).filter(j=>submitted.jobs.includes(j.id));
  assert(!jobs.some(j=>j.status==='FAILED'),JSON.stringify(jobs));
  if(jobs.every(j=>j.status==='SUCCEEDED'))break;
  if(i===119)throw Error('首次初始化超时');await new Promise(r=>setTimeout(r,500));
 }
 const business=await mysql.createConnection({host:process.env.PROXY_HOST,port:Number(process.env.PROXY_PORT),user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
 try{const [rows]=await business.query('SELECT id_card FROM AA LIMIT 1');assert(String(rows[0].id_card).includes('*'));console.log('PASS 从空实例首次接入真实 Doris 测试库，并完成业务账号脱敏查询')}finally{await business.end()}
})().catch(e=>{console.error(e.message,e.code,e.sqlState,e.sql||'connection phase');process.exitCode=1});
