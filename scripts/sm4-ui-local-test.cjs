// Mocked browser regression; never calls the deployment server.
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const {chromium}=require(path.join(path.dirname(process.execPath),'../node_modules/playwright'));
const base=path.resolve(__dirname,'../admin/src/main/resources/static');
(async()=>{const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{const page=await browser.newPage(),writes=[],errors=[];let profiles=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',route=>{const req=route.request(),u=new URL(req.url());assert.equal(u.hostname,'gateway-ui.test');
   if(u.pathname==='/')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(base,'index.html'),'utf8')});
   if(['/cluster-ui.js','/sm4-export-ui.js'].includes(u.pathname))return route.fulfill({contentType:'text/javascript',body:fs.readFileSync(path.join(base,u.pathname.slice(1)),'utf8')});
   let data=[];
   if(req.method()==='POST'){writes.push({path:u.pathname,body:req.postDataJSON()});if(u.pathname.endsWith('/profiles'))profiles=[req.postDataJSON()];}
   if(u.pathname==='/api/health')data={version:'mock'};
   if(u.pathname==='/api/sm4-export/profiles')data=req.method()==='GET'?profiles:{message:'已保存'};
   if(u.pathname==='/api/sm4-export/decrypt')data={filename:'decrypted-test.csv',csv:'姓名\r\n张三\r\n'};
   return route.fulfill({json:data});});
  await page.goto('http://gateway-ui.test/');await page.getByRole('button',{name:'SM4 加密导出',exact:true}).click();
  assert.equal(await page.locator('#sm4ProfileForm [name=enabled]').isChecked(),false);
  assert.equal(await page.locator('#sm4ProfileForm [name=allowDecrypt]').isChecked(),false);
  await page.locator('#sm4ProfileForm [name=username]').fill('测试账号');await page.locator('#sm4ProfileForm [name=databaseName]').fill('测试库');
  await page.locator('#sm4ProfileForm [name=rowKeyColumn]').fill('record_id');
  await page.evaluate(()=>{confirmAction=async()=>true;});await page.locator('#sm4ProfileForm button').click();await page.waitForFunction(()=>document.getElementById('sm4Profiles').textContent.includes('测试账号'));
  assert.equal(writes[0].body.rowKeyColumn,'record_id');
  await page.locator('#sm4ProfileForm [name=rowKeyColumn]').fill('changed');await page.getByRole('button',{name:'编辑',exact:true}).click();assert.equal(await page.locator('#sm4ProfileForm [name=rowKeyColumn]').inputValue(),'record_id');
  assert.equal(writes[0].body.enabled,false);assert.equal(writes[0].body.allowDecrypt,false);
  await page.locator('#sm4CsvFile').setInputFiles({name:'export.csv',mimeType:'text/csv',buffer:Buffer.from('姓名\r\n张*\r\n__GW_SM4_V1__:example\r\n')});
  const downloaded=page.waitForEvent('download');await page.getByRole('button',{name:'校验并下载明文',exact:true}).click();assert.equal((await downloaded).suggestedFilename(),'decrypted-test.csv');
  assert.equal(writes[1].path,'/api/sm4-export/decrypt');assert(writes[1].body.csv.includes('__GW_SM4_V1__:'));
  assert.deepEqual(errors,[]);console.log('PASS SM4 UI defaults disabled, explicit profile save, CSV upload and plaintext download wiring');
 }finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
