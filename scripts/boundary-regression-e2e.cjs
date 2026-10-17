// Synthetic data only. Refuses to use production service ports.
const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
const http=require('http'),assert=require('assert/strict');
const db='gateway_multi_acceptance_20261014',suffix=Date.now(),unknown='boundary_unmanaged_'+suffix,target='boundary_target_'+suffix,view='boundary_view_'+suffix;
function api(path,body){return new Promise((resolve,reject)=>{const r=http.request({hostname:'127.0.0.1',port:18086,path,method:'POST',auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD,headers:{'Content-Type':'application/json'}},s=>{let b='';s.on('data',x=>b+=x);s.on('end',()=>s.statusCode<300?resolve(b):reject(Error(b)));});r.on('error',reject);r.end(JSON.stringify(body));});}
async function deny(c,sql,expected){try{await c.query(sql);}catch(e){assert.match(e.message,expected);return;}throw Error('Expected rejection');}
(async()=>{
  const direct=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
  const gateway=await mysql.createConnection({host:'127.0.0.1',port:3312,user:'root',password:require('./test-secrets.cjs')('DORIS_TEST_PASSWORD'),database:db});
  const control=await mysql.createConnection({host:'127.0.0.1',port:3312,user:process.env.PROXY_ADMIN_USER,password:process.env.PROXY_ADMIN_PASSWORD,database:db});
  try{
    await direct.query('CREATE TABLE `'+unknown+'` (secret VARCHAR(64)) DUPLICATE KEY(secret) DISTRIBUTED BY HASH(secret) BUCKETS 1 PROPERTIES("replication_num"="1")');
    await deny(gateway,"SELECT 'information_schema.' FROM `"+unknown+'`',/exist|纳管|not found/i);
    console.log('PASS metadata literal cannot bypass unmanaged-object enforcement');
    await deny(gateway,'CREATE TABLE `'+target+'` AS SELECT (SELECT id_card FROM AA LIMIT 1) AS innocent',/标量子查询|Unsupported|not support/i);
    const [missing]=await direct.query('SHOW TABLES LIKE ?', [target]);assert.equal(missing.length,0);
    console.log('PASS scalar CTAS rejected before creating the physical target');
    await deny(gateway,'CREATE TABLE `'+target+'` AS SELECT 1 AS innocent FROM `'+unknown+'`',/exist|纳管|not found/i);
    console.log('PASS constant-output CTAS still validates its source object');
    await gateway.query('CREATE TABLE `'+target+'` PROPERTIES("replication_num"="1") AS SELECT id_card AS inherited FROM AA');
    const [masked]=await gateway.query('SELECT inherited FROM `'+target+'` LIMIT 1');assert(masked.length&&masked[0].inherited.includes('*'));
    await deny(gateway,'INSERT INTO `'+target+'` (inherited) SELECT name FROM AA',/策略.*冲突/);
    console.log('PASS existing target policy cannot be overwritten by incompatible source policy');
    await control.query('DROP MASK RULE `'+target+'`');
    try{const [stable]=await gateway.query('SELECT inherited FROM `'+target+'` LIMIT 1');assert(stable.length&&stable[0].inherited.includes('*'));}
    finally{await api('/api/publish',{database:db});}
    const [restored]=await gateway.execute('SELECT inherited FROM `'+target+'` LIMIT 1');assert(restored.length&&restored[0].inherited.includes('*'));
    console.log('PASS immutable published snapshot still masks while Proxy rule is absent; prepared query also works');
    await gateway.query('CREATE VIEW `'+view+'` AS SELECT id_card AS innocent FROM AA');
    await deny(gateway,'CREATE OR REPLACE VIEW `'+view+'` AS SELECT name AS innocent FROM AA',/CREATE OR REPLACE|替换/);
    console.log('PASS view replacement is rejected rather than acknowledged as NOOP');
    await gateway.query('DROP VIEW `'+view+'`');
    await gateway.query('DROP TABLE `'+target+'`');
  } finally {
    await direct.query('DROP TABLE IF EXISTS `'+unknown+'`');
    await Promise.all([direct.end(),gateway.end(),control.end()]);
  }
})().catch(e=>{console.error(e.message);process.exitCode=1;});
