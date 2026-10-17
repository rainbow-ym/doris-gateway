const {chromium}=require(require('path').join(require('path').dirname(process.execPath),'../node_modules/playwright'));
const assert=require('assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://192.168.232.132:18080/login.html');
  await page.locator('input[name=username]').fill(process.env.TEST_ADMIN_USER);
  await page.locator('input[name=password]').fill(process.env.TEST_ADMIN_PASSWORD);
  await page.locator('#submit').click();await page.waitForURL('http://192.168.232.132:18080/');
  await page.getByRole('link',{name:'进入集群管理 →'}).first().click();
  await page.waitForSelector('#version:has-text("2026.10.15-accounts.1")');
  await page.getByRole('button',{name:'字段识别规则',exact:true}).click();
  await page.waitForSelector('#builtinRules tbody tr');assert.equal(await page.locator('#builtinRules tbody tr').count(),4);
  await page.getByRole('button',{name:'新增映射规则',exact:true}).click();
  assert.equal(await page.locator('#mappingForm fieldset').count(),3);
  assert.match(await page.locator('#mappingForm select[name=sensitiveType]').innerText(),/证件号码脱敏/);
  await page.locator('#mappingForm').getByRole('button',{name:'取消',exact:true}).click();
  await page.getByRole('button',{name:'账号同步任务',exact:true}).click();
  await page.waitForSelector('#accountTasks tbody tr');
  assert.match(await page.locator('#accountTasks').innerText(),/删除用户/);
  await page.screenshot({path:'docs/account-tasks-production.png',fullPage:false});
  assert.deepEqual(errors,[]);console.log('PASS 正式浏览器登录、新版标识、四类默认规则、中文策略、布局与账号同步任务；未修改业务配置');
 }finally{await browser.close()}
})().catch(e=>{console.error(e.message);process.exitCode=1});
