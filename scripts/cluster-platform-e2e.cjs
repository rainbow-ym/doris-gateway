const http=require('http'),assert=require('assert').strict,mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
let cookie='',csrf,header;
function request(path,method='GET',body,token=true){return new Promise((resolve,reject)=>{const data=typeof body==='string'?body:body?JSON.stringify(body):'';const h={'Cookie':cookie,'Content-Type':typeof body==='string'?'application/x-www-form-urlencoded':'application/json','Content-Length':Buffer.byteLength(data)};if(csrf&&token)h[header]=csrf;const r=http.request({hostname:'192.168.232.132',port:18084,path,method,headers:h},res=>{if(res.headers['set-cookie'])cookie=res.headers['set-cookie'].map(s=>s.split(';')[0]).join('; ');let text='';res.on('data',x=>text+=x);res.on('end',()=>{let json;try{json=JSON.parse(text)}catch(e){json={}}resolve({status:res.statusCode,json,text})})});r.on('error',reject);r.end(data)})}
async function api(path,method='GET',body){const r=await request(path,method,body);assert(r.status<300,`${path}: HTTP ${r.status} ${r.json.message||''}`);return r.json}
async function login(){const s=await api('/api/session');csrf=s.csrf;header=s.csrfHeader;assert.notEqual((await request('/login','POST','username=admin&password=x',false)).status,200);await api('/login','POST',new URLSearchParams({username:'admin',password:require('./test-secrets.cjs')('PLATFORM_TEST_PASSWORD')}).toString());const after=await api('/api/session');csrf=after.csrf;header=after.csrfHeader;assert.equal(after.authenticated,true)}
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const db='gateway_multi_acceptance_20261014';
async function main(){
 assert.equal((await request('/api/clusters')).status,401);await login();console.log('PASS 未登录拒绝、登录 CSRF、会话登录');
 let clusters=await api('/api/clusters');
 async function register(name,port,user,password,proxy){let c=clusters.find(c=>c.name===name);if(!c){const r=await api('/api/clusters','POST',{name,workerUrl:'http://127.0.0.1:'+port,workerUsername:user,workerPassword:password,gatewayAddress:'127.0.0.1:'+proxy});c={id:r.id,name};clusters.push(c)}return c.id}
 const a=await register('隔离验收 A',18081,process.env.ADMIN_USERNAME,process.env.ADMIN_PASSWORD,3310);
 const b=await register('隔离验收 B',18083,'admin',require('./test-secrets.cjs')('CLUSTER_B_ADMIN_PASSWORD'),3311);
 const A=(path,method,body)=>api('/api/clusters/'+a+'/relay/api'+path,method,body),B=(path,method,body)=>api('/api/clusters/'+b+'/relay/api'+path,method,body);
 assert.notEqual((await A('/instance')).id,(await B('/instance')).id);
 assert.equal((await request('/api/clusters','POST',{name:'bad'},false)).status,403);
 const listing=(await request('/api/clusters')).text;assert(!listing.includes('password'));console.log('PASS 两集群独立身份、管理凭据不回显、修改接口 CSRF');
 let mappings=await B('/field-mappings');if(!mappings.some(x=>x.expression==='关系人证件号码'))await B('/field-mappings','POST',{matchKind:'EXACT',expression:'关系人证件号码',sensitiveType:'ID_CARD'});
 assert(!(await A('/field-mappings')).some(x=>x.expression==='关系人证件号码'));console.log('PASS 字段识别规则按集群隔离');
 const csv='username,password,databasePattern\nroot,'+require('./test-secrets.cjs')('DORIS_TEST_PASSWORD')+','+db+'\ngw_multi_b_20261014,'+require('./test-secrets.cjs')('MULTI_USER_TEST_PASSWORD')+','+db+'\n';
 const task=(await B('/initialization/preview','POST',{host:'127.0.0.1',port:9030,csv})).id;
 let items;
 for(let i=0;i<120;i++){items=await B('/initialization/tasks/'+task);if(items.every(r=>r.status!=='VALIDATING'))break;await wait(250)}
 assert(items.every(r=>r.status==='READY'),JSON.stringify(items));assert(!JSON.stringify(items).includes(require('./test-secrets.cjs')('MULTI_USER_TEST_PASSWORD')));console.log('PASS 用户密码表预览、验证及凭据不回显');
 await B('/initialization/tasks/'+task+'/confirm','POST');
 for(let i=0;i<240;i++){items=await B('/initialization/tasks/'+task);if(items.every(r=>['SUCCEEDED','FAILED'].includes(r.status)))break;await wait(250)}
 assert(items.every(r=>r.status==='SUCCEEDED'),JSON.stringify(items));console.log('PASS 多用户批量初始化及同库复用');
 const sources=await B('/connections'),rootSource=sources.find(x=>x.username==='root'),businessSource=sources.find(x=>x.username==='gw_multi_b_20261014');
 assert.equal(sources.filter(x=>x.username==='root').length,1);
 await B('/user-output-policies','POST',{username:'root',logicalName:db,sensitiveType:'ID_CARD',action:'CLEAR'});
 const conns=[];const connect=async o=>{const c=await mysql.createConnection(o);conns.push(c);return c};
 try{
  const ca=await connect({host:'127.0.0.1',port:3310,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
  const cb=await connect({host:'127.0.0.1',port:3311,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
  const cu=await connect({host:'127.0.0.1',port:3311,user:'gw_multi_b_20261014',password:require('./test-secrets.cjs')('MULTI_USER_TEST_PASSWORD'),database:db});
  const [ra]=await ca.query('SELECT id_card FROM AA LIMIT 1'),[rb]=await cb.query('SELECT id_card FROM AA LIMIT 1');
  assert(String(ra[0].id_card).includes('*'));assert(!String(rb[0].id_card).includes('*'));console.log('PASS 同名用户同名库在不同网关使用独立返回策略');
  const direct=await connect({host:'127.0.0.1',port:9030,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
  const table='external_alias_'+Date.now();
  await direct.query('CREATE TABLE `'+table+'` (id INT,`关系人证件号码` VARCHAR(64)) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES("replication_num"="1")');
  await direct.query('INSERT INTO `'+table+'` VALUES(1,"110101199001011234")');
  try{await cu.query('SELECT * FROM `'+table+'`');throw Error('未纳管表未拒绝')}catch(e){assert.match(e.message,/not exist|不存在/i)}
  await B('/connections/'+rootSource.id+'/scan','POST');
  const objects=await B('/pages/objects?'+new URLSearchParams({database:db,size:100}));const object=objects.rows.find(x=>x.tableName===table);assert(object);
  const approved=await B('/managed-objects/approve-batch','POST',[object.id]);assert.equal(approved[0].status,'SUCCEEDED');
  const [result]=await cu.query('SELECT `关系人证件号码` FROM `'+table+'`');assert(String(result[0]['关系人证件号码']).includes('*'));
  const projection=await B('/connections/'+businessSource.id+'/access');assert(projection.some(x=>x.tableName===table&&x.state==='ALLOWED'));
  await cb.query('DROP TABLE `'+table+'`');console.log('PASS 外部源表先拒绝、批量纳管、字段别名映射及库级账号权限投影');
  const page=await B('/pages/columns?'+new URLSearchParams({connectionId:rootSource.id,size:1,page:1}));assert.equal(page.rows.length,1);assert(page.total>1);console.log('PASS 服务端分页');
 }finally{for(const c of conns)await c.end()}
 await api('/logout','POST');assert.equal((await request('/api/clusters')).status,401);console.log('PASS 退出后原会话失效');
}
main().catch(e=>{console.error(e.message);process.exitCode=1});
