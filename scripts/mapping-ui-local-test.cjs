// Fully mocked local UI regression: never connects to the deployment server.
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const {chromium}=require(path.join(path.dirname(process.execPath),'../node_modules/playwright'));
const root=path.resolve(__dirname,'..'),base=path.join(root,'admin/src/main/resources/static');
const catalog=fs.readFileSync(path.join(root,'admin/src/main/java/com/example/doris/gateway/admin/PolicyCatalog.java'),'utf8');
const policies=[...catalog.matchAll(/new Policy\("([^"]+)", "([^"]+)", "([^"]+)", props\(([^)]*)\),\s*names\(([^)]*)\)/g)].map(m=>{
 const strings=s=>[...s.matchAll(/"([^"]*)"/g)].map(x=>x[1]),pairs=strings(m[4]),props={};
 for(let i=0;i<pairs.length;i+=2)props[pairs[i]]=pairs[i+1];
 return {id:m[1],name:m[2],algorithmType:m[3],props,columnNames:strings(m[5])};
});
(async()=>{
 assert.equal(policies.length,4);
 assert(catalog.includes('public List<String> getColumnNames()'));
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],writes=[];let rows=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',async route=>{
   const req=route.request(),url=new URL(req.url());
   assert.equal(url.hostname,'gateway-ui.test');
   if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(base,'index.html'),'utf8')});
   if(url.pathname==='/cluster-ui.js')return route.fulfill({contentType:'text/javascript',body:fs.readFileSync(path.join(base,'cluster-ui.js'),'utf8')});
   let data=[];
   if(req.method()==='POST'){
    writes.push({url:url.pathname,body:req.postDataJSON()});
    if(url.pathname==='/api/field-mappings')rows=[{id:1,...req.postDataJSON()}];
   }
   if(url.pathname==='/api/policies')data=policies;
   if(url.pathname==='/api/health')data={version:'local-test'};
   if(url.pathname==='/api/field-mappings')data=rows;
   if(url.pathname==='/api/field-mappings/preview')data={total:1,sample:[{databaseName:'测试库',tableName:'测试表',columnName:'关系人证件号码',assignedPolicy:'ID_CARD'}]};
   return route.fulfill({json:data});
  });
  await page.goto('http://gateway-ui.test/');
  await page.waitForFunction(()=>policies.length===4);
  await page.getByRole('button',{name:'字段识别规则',exact:true}).click();
  await page.waitForSelector('#builtinRules tbody tr');
  assert.equal(await page.locator('#builtinRules tbody tr').count(),4);
  assert.match(await page.locator('#mappingRules').innerText(),/内置识别规则仍然有效/);
  assert.deepEqual(await page.evaluate(()=>[
   cellText('recommendedPolicy','NONE'),cellText('assignedPolicy','NONE'),cellText('assignedPolicy',null),
   cellText('inheritedPolicy','ID_CARD'),cellText('sensitiveType','PERSON_NAME'),cellText('columnName','ID_CARD')
  ]),['未匹配推荐策略','不处理','未配置','证件号码脱敏','姓名脱敏','ID_CARD']);
  await page.getByRole('button',{name:'新增映射规则',exact:true}).click();
  assert.equal(await page.locator('#mappingForm fieldset').count(),3);
  await page.getByRole('button',{name:'试运行（不保存）'}).click();
  assert.equal(writes.length,0);
  await page.locator('[name=expression]').fill('关系人证件号码');
  await page.getByRole('button',{name:'试运行（不保存）'}).click();
  await page.waitForSelector('#mappingPreview table');
  assert.match(await page.locator('#mappingPreview').innerText(),/证件号码脱敏/);
  assert.equal(writes[0].body.sensitiveType,'ID_CARD');
  await page.screenshot({path:path.join(root,'docs/mapping-ui-local-desktop.png')});
  await page.setViewportSize({width:480,height:900});
  assert.equal(await page.locator('#mappingForm .grid').first().evaluate(e=>getComputedStyle(e).gridTemplateColumns.split(' ').length),1);
  await page.screenshot({path:path.join(root,'docs/mapping-ui-local-mobile.png')});
  await page.getByRole('button',{name:'保存',exact:true}).click();
  await page.waitForSelector('#mappingRules table');
  assert.match(await page.locator('#mappingRules').innerText(),/证件号码脱敏/);
  assert.equal(writes.filter(w=>w.url==='/api/field-mappings').length,1);
  await page.locator('#mappingRules').getByRole('button',{name:'编辑',exact:true}).click();
  assert.equal(await page.locator('[name=expression]').inputValue(),'关系人证件号码');
  await page.locator('#mappingForm').getByRole('button',{name:'取消',exact:true}).click();
  assert.equal(await page.locator('#detailDialog').evaluate(e=>e.open),false);
  assert.deepEqual(errors,[]);
  console.log('PASS local mocked UI: defaults, Chinese labels, NONE semantics, unchanged IDs, validation, preview/save/edit/cancel, desktop/mobile; no server requests.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
