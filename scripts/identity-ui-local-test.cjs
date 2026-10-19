// Mocked UI test: no deployment or database access.
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const {chromium}=require(path.join(path.dirname(process.execPath),'../node_modules/playwright'));
const base=path.resolve(__dirname,'../admin/src/main/resources/static');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{
  const page=await browser.newPage(),writes=[],errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  const preview={id:7,generation_id:'generation-test',database:'测试库',table:'外部新表',physicalIdentity:'0:1:2'};
  await page.route('**/*',route=>{
   const req=route.request(),url=new URL(req.url());assert.equal(url.hostname,'gateway-ui.test');
   if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(base,'index.html'),'utf8')});
   if(url.pathname==='/cluster-ui.js')return route.fulfill({contentType:'text/javascript',body:fs.readFileSync(path.join(base,'cluster-ui.js'),'utf8')});
   if(req.method()==='POST')writes.push({path:url.pathname,body:req.postDataJSON()});
   let data=[];
   if(url.pathname.endsWith('/new-sources/7/preview'))data=preview;
   if(url.pathname.endsWith('/new-sources/7/confirm'))data={message:'已确认来源身份，尚未开放查询'};
   if(url.pathname==='/api/health')data={version:'mock'};
   return route.fulfill({json:data});
  });
  await page.goto('http://gateway-ui.test/');await page.waitForFunction(()=>typeof reviewNewSource==='function');
  await page.evaluate(()=>reviewNewSource(document.createElement('button'),7));
  await page.waitForSelector('#confirmNewSource');assert.equal(writes.length,0);
  assert.match(await page.locator('#detailContent').innerText(),/确认后仍不能查询/);
  await page.locator('#confirmNewSource').click();await page.waitForFunction(()=>!document.getElementById('detailDialog').open);
  assert.deepEqual(writes,[{path:'/api/object-identities/new-sources/7/confirm',body:preview}]);
  assert.deepEqual(errors,[]);
  console.log('PASS source preview is read-only, explicit confirmation sends captured identity, no implicit approval');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
