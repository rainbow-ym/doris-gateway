// Run only against the isolated 3312 acceptance gateway and synthetic test database.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise'),http=require('http'),assert=require('assert/strict');
const db='gateway_multi_acceptance_20261014',user=process.env.LIFECYCLE_TEST_USER||'gw_life_'+Date.now(),password=require('./test-secrets.cjs')('LIFECYCLE_TEST_PASSWORD'),nextPassword=require('./test-secrets.cjs')('LIFECYCLE_NEXT_PASSWORD');
const base={host:'127.0.0.1',port:3312,database:db},opened=[];
async function connect(username='root',pass=require('./test-secrets.cjs')('DORIS_TEST_PASSWORD')){const c=await mysql.createConnection({...base,user:username,password:pass});opened.push(c);return c;}
function api(path,method='GET',body){return new Promise((resolve,reject)=>{
 const data=body?JSON.stringify(body):'',r=http.request({hostname:'127.0.0.1',port:18086,path,method,auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json'}},res=>{
 let b='';res.on('data',s=>b+=s);res.on('end',()=>{try{if(res.statusCode!==200)throw Error('API '+res.statusCode);resolve(JSON.parse(b))}catch(e){reject(e)}})});r.on('error',reject);r.end(data);
});}
async function denied(fn){let refused=false;try{await fn()}catch(e){refused=true;}assert(refused,'expected refusal');}
(async()=>{
 const root=await connect();
 await root.query("/* client DBeaver */ CREATE /* middle */ USER '"+user+"'@'%' IDENTIFIED BY '"+password+"'");
 assert((await api('/api/connections')).some(r=>r.username===user));
 await denied(()=>connect(user,password));
 console.log('PASS CREATE USER 自动登记，同步前不授予业务库');
 await root.query('GRANT SELECT_PRIV ON '+db+'.AA TO '+user);
 const first=await connect(user,password),[rows]=await first.query('SELECT id_card FROM AA LIMIT 1');
 assert(rows.length&&String(rows[0].id_card).includes('*'));
 await denied(()=>first.query('SELECT * FROM BB LIMIT 1'));
 await denied(()=>first.query("CREATE USER forbidden_lifecycle IDENTIFIED BY 'no'"));
 console.log('PASS 表级授权自动关联、继承脱敏、非 root 不代执行管理命令');
 await root.query('REVOKE SELECT_PRIV ON '+db+'.AA FROM '+user);
 await denied(()=>first.query('SELECT id_card FROM AA LIMIT 1'));
 console.log('PASS 撤权阻止已有业务连接继续查询');
 await root.query('GRANT SELECT_PRIV ON '+db+'.* TO '+user);
 const old=await connect(user,password);await old.query('SELECT id_card FROM AA LIMIT 1');
 await root.query("SET PASSWORD FOR "+user+" = PASSWORD('"+nextPassword+"')");
 await denied(()=>old.query('SELECT id_card FROM AA LIMIT 1'));
 await denied(()=>connect(user,password));
 const current=await connect(user,nextPassword);await current.query('SELECT id_card FROM AA LIMIT 1');
 console.log('PASS 改密同步新凭据，旧密码与旧会话均不可继续访问');
 await denied(()=>root.query("CREATE USER "+user+" IDENTIFIED BY '"+nextPassword+"'"));
 const failed=(await api('/api/account-lifecycle/tasks')).filter(t=>t.username===user&&t.status==='FAILED');assert(failed.length);
 await root.query('DROP USER '+user);
 await denied(()=>current.query('SELECT id_card FROM AA LIMIT 1'));await denied(()=>connect(user,nextPassword));
 console.log('PASS 删除用户禁止新旧连接，Doris 失败操作不显示成功');
 const tasks=(await api('/api/account-lifecycle/tasks')).filter(t=>t.username===user);
 assert(!JSON.stringify(tasks).includes(password));assert(!JSON.stringify(tasks).includes(nextPassword));assert(!JSON.stringify(tasks).includes('IDENTIFIED'));
 assert(tasks.some(t=>t.operation==='DROP'&&t.status==='SUCCEEDED'));
 console.log('PASS 审计任务无 SQL/密码明文；user='+user);
})().catch(e=>{console.error('FAIL',e.message,e.code,e.sqlState);process.exitCode=1}).finally(async()=>{for(const c of opened)try{await c.end()}catch{}});
