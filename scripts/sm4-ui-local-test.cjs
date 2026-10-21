// Mocked browser regression; never calls the deployment server.
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const {chromium}=require(path.join(path.dirname(process.execPath),'../node_modules/playwright'));
const base=path.resolve(__dirname,'../admin/src/main/resources/static');
(async()=>{const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{const page=await browser.newPage(),writes=[],errors=[];let profiles=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',route=>{const req=route.request(),u=new URL(req.url());assert.equal(u.hostname,'gateway-ui.test');
   if(u.pathname==='/')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(base,'index.html'),'utf8')});
   if(['/cluster-ui.js','/sm4-export-ui.js','/observability-ui.js'].includes(u.pathname))return route.fulfill({contentType:'text/javascript',body:fs.readFileSync(path.join(base,u.pathname.slice(1)),'utf8')});
   let data=[];if(req.method()==='DELETE'){writes.push({path:u.pathname,body:req.postDataJSON()});profiles=[];}
   if(req.method()==='POST'){writes.push({path:u.pathname,body:req.postDataJSON()});if(u.pathname.endsWith('/profiles/batch')){const b=req.postDataJSON();profiles=b.databaseNames.map(databaseName=>({...b,databaseName,ttlMinutes:b.ttlDays?b.ttlDays*1440:b.ttlMinutes,source:'人工配置'}));}}
   if(u.pathname==='/api/sm4-export/defaults')data={linked:false,enabled:false,allowDecrypt:false,allowEdit:true,maxRows:200,ttlMinutes:1440};
   if(u.pathname==='/api/health')data={version:'mock'};
   if(u.pathname==='/api/sm4-export/profiles')data=req.method()==='GET'?profiles:{message:'已保存'};
   if(u.pathname==='/api/sm4-export/decrypt')data={filename:'decrypted-test.csv',csv:'姓名\r\n张三\r\n'};
   return route.fulfill({json:data});});
  await page.goto('http://gateway-ui.test/');await page.getByRole('button',{name:'SM4 加密导出',exact:true}).click();
  assert.equal(await page.locator('#sm4ProfileForm [name=enabled]').isChecked(),false);
  assert.equal(await page.locator('#sm4ProfileForm [name=allowDecrypt]').isChecked(),false);
  await page.locator('#sm4ProfileForm [name=username]').fill('测试账号');await page.locator('#sm4ProfileForm [name=databaseNames]').fill('测试库\n第二库');
  assert.equal(await page.locator('[name=rowKeyColumn]').count(),0);
  await page.locator('#sm4ProfileForm [name=ttlDays]').fill('365');
  await page.evaluate(()=>{confirmAction=async()=>true;});await page.locator('#sm4ProfileForm button[type=submit]').click();await page.waitForFunction(()=>document.getElementById('sm4Profiles').textContent.includes('测试账号'));
  assert.equal(writes[0].body.allowEdit,true);assert.equal(writes[0].body.ttlDays,365);assert.deepEqual(writes[0].body.databaseNames,['测试库','第二库']);
  await page.locator('#sm4ProfileForm [name=ttlDays]').fill('2');await page.getByRole('button',{name:'编辑',exact:true}).first().click();assert.equal(await page.locator('#sm4ProfileForm [name=ttlDays]').inputValue(),'365');assert.equal(await page.locator('#sm4ProfileForm [name=databaseNames]').inputValue(),'测试库');
  assert.equal(writes[0].body.enabled,false);assert.equal(writes[0].body.allowDecrypt,false);
  await page.locator('#sm4CsvFile').setInputFiles({name:'export.csv',mimeType:'text/csv',buffer:Buffer.from('姓名\r\n张*\r\n__GW_SM4_V1__:example\r\n')});
  const downloaded=page.waitForEvent('download');await page.getByRole('button',{name:'校验并下载明文',exact:true}).click();assert.equal((await downloaded).suggestedFilename(),'decrypted-test.csv');
  assert.equal(writes[1].path,'/api/sm4-export/decrypt');assert(writes[1].body.csv.includes('__GW_SM4_V1__:'));
  await page.getByRole('button',{name:'删除',exact:true}).first().click();await page.waitForFunction(()=>!document.getElementById('sm4Profiles').textContent.includes('测试账号'));
  await page.locator('#sm4DefaultsForm [name=linked]').check();await page.locator('#sm4DefaultsForm [name=allowDecrypt]').check();await page.locator('#sm4DefaultsForm button').click();await page.waitForTimeout(100);assert.equal(writes.at(-1).path,'/api/sm4-export/defaults');assert.equal(writes.at(-1).body.allowDecrypt,true);assert.equal(writes.at(-1).body.linked,true);
  assert.deepEqual(errors,[]);console.log('PASS SM4 UI no anchor, batch save, days, edit, delete, explicit auto-link grants and CSV download');
 }finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
