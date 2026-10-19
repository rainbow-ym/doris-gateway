const mysql=require('/opt/scip-runtime/node_modules/mysql2/promise');
(async()=>{
 for(const port of [9030,3308]) {
  const c=await mysql.createConnection({host:'127.0.0.1',port,user:'root',password:process.env.DORIS_TEST_PASSWORD,database:'DWD_人口'});
  const queries=[['nested text','SELECT t.alias_doc FROM (SELECT `证件号码` AS alias_doc FROM `人口关系网`) t LIMIT 5',null],['nested prepared','SELECT t.alias_doc FROM (SELECT `证件号码` AS alias_doc FROM `人口关系网`) t LIMIT ?',[5]],['simple prepared','SELECT `证件号码` FROM `人口关系网` LIMIT ?',[5]],['predicate prepared','SELECT t.alias_doc FROM (SELECT `证件号码` AS alias_doc FROM `人口关系网`) t WHERE t.alias_doc IS NOT NULL AND ? = 1 LIMIT 5',[1]]];
  try{for(const [name,sql,args] of queries){try{const [r]=args?await c.execute(sql,args):await c.query(sql);console.log(port,name,'PASS rows='+r.length);}catch(e){console.log(port,name,'FAIL code='+e.errno+' '+e.message);}}}finally{await c.end();}
 }
})().catch(e=>{console.error(e.message);process.exitCode=1;});
