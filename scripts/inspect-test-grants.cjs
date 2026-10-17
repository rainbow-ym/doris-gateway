const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
(async()=>{const c=await mysql.createConnection({host:'127.0.0.1',port:9030,user:'gw_multi_b_20261014',password:require('./test-secrets.cjs')('MULTI_USER_TEST_PASSWORD')});try{console.log(JSON.stringify((await c.query('SHOW GRANTS'))[0]));}finally{await c.end();}})().catch(e=>{console.error(e.message);process.exitCode=1;});
