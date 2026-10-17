const {chromium}=require(require('path').join(require('path').dirname(process.execPath),'../node_modules/playwright'));
const assert=require('assert').strict;
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://192.168.232.132:18084/login.html');await page.locator('input[name=username]').fill('admin');await page.locator('input[name=password]').fill(require('./test-secrets.cjs')('PLATFORM_TEST_PASSWORD'));
  await page.locator('#submit').click();await page.waitForURL('http://192.168.232.132:18084/');
  await page.locator('.card').filter({hasText:'隔离验收 B'}).getByRole('link',{name:'进入集群管理 →'}).click();
  await page.waitForSelector('h1:has-text("隔离验收 B")');
  await page.getByRole('button',{name:'初始化与增量接入',exact:true}).click();await page.waitForSelector('#importTasks table');
  await page.getByRole('button',{name:'查看进度与确认'}).first().click();await page.waitForSelector('#detailDialog[open]');
  assert((await page.locator('#detailTitle').textContent()).includes('初始化'));
  await page.screenshot({path:'docs/cluster-ui-initialization.png',fullPage:false});
  await page.locator('#detailDialog').getByRole('button',{name:'关闭',exact:true}).click();
  await page.getByRole('button',{name:'字段识别规则',exact:true}).click();await page.waitForSelector('#mappingRules table');
  await page.getByRole('button',{name:'新增映射规则',exact:true}).click();await page.locator('#mappingForm input[name=expression]').fill('关系人证件号码');
  await page.getByRole('button',{name:'试运行（不保存）'}).click();await page.waitForSelector('#mappingPreview p');
  await page.screenshot({path:'docs/cluster-ui-mapping.png',fullPage:false});
  await page.locator('#detailDialog').getByRole('button',{name:'关闭',exact:true}).click();
  await page.getByRole('button',{name:'集群字段保护',exact:true}).click();await page.waitForSelector('#columnTable .pager');
  assert.deepEqual(errors,[]);console.log('PASS 浏览器登录、集群选择、初始化详情抽屉、字段映射试运行和分页，无页面脚本异常');
 }finally{await browser.close()}
})().catch(e=>{console.error(e.message);process.exitCode=1});
