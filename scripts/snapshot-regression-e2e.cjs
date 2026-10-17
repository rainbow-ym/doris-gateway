// Only isolated 3312 / 18086. Synthetic data; no production service mutations.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
const http=require('http'),assert=require('assert/strict');
const db='gateway_multi_acceptance_20261014';
function api(path,method='GET',body){return new Promise((resolve,reject)=>{
 const r=http.request({hostname:'127.0.0.1',port:18086,path,method,auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json'}},s=>{
  let text='';s.on('data',x=>text+=x);s.on('end',()=>{if(s.statusCode>=300)return reject(Error(text));try{resolve(JSON.parse(text));}catch(e){reject(e);}});
 });r.on('error',reject);r.end(body?JSON.stringify(body):undefined);
});}
(async()=>{
 const c=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
 let field,original;
 const publish=()=>api('/api/publish','POST',{database:db});
 const assign=id=>api('/api/assignments/batch','POST',{ids:[field.id],policyId:id});
 const value=async()=>{const [r]=await c.execute('SELECT id_card FROM AA WHERE id=?',[1]);assert(r.length);return r[0].id_card;};
 try {
  if(process.argv.includes('--verify-restart')) {
   assert.equal(await value(),'110101********1234');
   console.log('PASS worker重启后无需重新发布，持久化生效快照继续脱敏');return;
  }
  // Explicit migration: never infer the active snapshot from an unverified draft.
  await publish();
  const mappings=await api('/api/logical-databases');
  const mapping=mappings.find(x=>x.logicalName===db);assert(mapping);
  const rows=await api('/api/columns?connectionId='+mapping.connectionId+'&database='+encodeURIComponent(db));
  field=rows.find(x=>x.tableName==='AA'&&x.columnName==='id_card');assert(field);original=field.assignedPolicy;
  await assign('ID_CARD');await publish();assert.equal(await value(),'110101********1234');
  if(process.argv.includes('--automatic')) {
   const target='auto_draft_'+Date.now(),renamed=target+'_renamed';let current='';
   const check=async()=>{const [r]=await c.query('SELECT inherited FROM `'+current+'` LIMIT 1');assert.equal(r[0].inherited,'110101********1234');assert.equal(await value(),'110101********1234');};
   try {
    await assign('PERSON_NAME');
    await c.query('CREATE TABLE `'+target+'` PROPERTIES("replication_num"="1") AS SELECT id_card AS inherited FROM AA');current=target;await check();
    console.log('PASS CTAS继承来源生效策略，不继承来源草稿，也不发布其他表草稿');
    const createdVersion=(await api('/api/versions')).find(x=>x.databaseName===db&&x.status==='PUBLISHED');assert(createdVersion);
    await assert.rejects(api('/api/versions/'+createdVersion.id+'/rollback','POST'),/结构|字段集合/);await check();
    console.log('PASS 建表后拒绝回滚到缺少新表保护的旧结构快照');
    const targets=await api('/api/columns?connectionId='+mapping.connectionId+'&database='+encodeURIComponent(db));
    const targetField=targets.find(x=>x.tableName===target&&x.columnName==='inherited');assert(targetField);
    await api('/api/assignments/batch','POST',{ids:[targetField.id],policyId:'PERSON_NAME'});
    await c.query('INSERT INTO `'+target+'` SELECT id_card FROM AA');await check();
    let draft=(await api('/api/columns?connectionId='+mapping.connectionId+'&database='+encodeURIComponent(db))).find(x=>x.id===targetField.id);
    assert.equal(draft.assignedPolicy,'PERSON_NAME');
    console.log('PASS INSERT SELECT只更新生效继承，不提前发布或覆盖目标表草稿');
    await c.query('ALTER TABLE `'+target+'` ADD COLUMN remark VARCHAR(64)');await check();
    await c.query('ALTER TABLE `'+target+'` RENAME `'+renamed+'`');current=renamed;await check();
    draft=(await api('/api/columns?connectionId='+mapping.connectionId+'&database='+encodeURIComponent(db))).find(x=>x.id===targetField.id);
    assert.equal(draft.tableName,renamed);assert.equal(draft.assignedPolicy,'PERSON_NAME');
    console.log('PASS ALTER与RENAME保留生效保护以及未发布草稿');
    await c.query('DROP TABLE `'+current+'`');current='';assert.equal(await value(),'110101********1234');
    assert.equal((await api('/api/columns?connectionId='+mapping.connectionId+'&database='+encodeURIComponent(db))).find(x=>x.id===field.id).assignedPolicy,'PERSON_NAME');
    console.log('PASS DROP仅移除目标策略，不发布来源表草稿');
    const updateField=targets.find(x=>x.tableName==='UU'&&x.columnName==='id_card');assert(updateField);
    try {
     await api('/api/assignments/batch','POST',{ids:[updateField.id],policyId:'PERSON_NAME'});
     await c.query('UPDATE UU SET copy_value=id_card WHERE id=1');
     const [u]=await c.query('SELECT id_card,copy_value FROM UU WHERE id=1');
     assert.equal(u[0].id_card,'110101********1234');assert.equal(u[0].copy_value,'110101********1234');
     assert.equal((await api('/api/columns?connectionId='+mapping.connectionId+'&database='+encodeURIComponent(db))).find(x=>x.id===updateField.id).assignedPolicy,'PERSON_NAME');
     console.log('PASS UPDATE使用生效来源策略并保留同表未发布草稿');
    } finally {await api('/api/assignments/batch','POST',{ids:[updateField.id],policyId:updateField.assignedPolicy});}
   } finally {if(current)await c.query('DROP TABLE `'+current+'`');}
   return;
  }
  await assign('PERSON_NAME');assert.equal(await value(),'110101********1234');
  console.log('PASS 草稿修改不改变正在生效的返回策略');
  const second=await publish();const next=await value();assert.notEqual(next,'110101********1234');assert(next.includes('*'));
  console.log('PASS 校验发布后预编译查询读取新快照');
  await api('/api/versions/'+second.version+'/rollback','POST');assert.equal(await value(),'110101********1234');
  const after=await api('/api/columns?connectionId='+mapping.connectionId+'&database='+encodeURIComponent(db));
  assert.equal(after.find(x=>x.id===field.id).assignedPolicy,'PERSON_NAME');
  console.log('PASS 回滚恢复旧生效快照且不覆盖当前草稿');
  const reading=(async()=>{for(let i=0;i<30;i++){
   const [rows]=await c.query('SELECT id_card FROM AA WHERE id=1');assert(rows.length);
   const values=new Set(rows.map(x=>x.id_card));assert.equal(values.size,1);
   for(const v of values)assert(['110101********1234',next].includes(v));
  }})();
  const publishing=(async()=>{for(const id of ['PERSON_NAME','ID_CARD','PERSON_NAME','ID_CARD']){await assign(id);await publish();}})();
  await Promise.all([reading,publishing]);
  console.log('PASS 并发发布与30次查询只读取完整旧版或新版，未返回明文或混合行');
 } finally {
  if(field&&original){await assign(original);await publish();}
  await c.end();
 }
})().catch(e=>{console.error(e.message);process.exitCode=1;});
