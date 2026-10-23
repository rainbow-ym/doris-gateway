// Real Platform + Grafana; Prometheus query data is synthetic. Never connects to a deployed server.
const fs=require('fs'),path=require('path'),os=require('os'),net=require('net'),http=require('http'),cp=require('child_process'),crypto=require('crypto'),assert=require('assert/strict');
const {chromium}=require(path.join(path.dirname(process.execPath),'../node_modules/playwright'));
async function freePort(){const s=net.createServer();await new Promise(r=>s.listen(0,'127.0.0.1',r));const p=s.address().port;await new Promise(r=>s.close(r));return p;}
(async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'gw-history-')),platformPort=await freePort(),grafanaPort=await freePort(),password=crypto.randomBytes(18).toString('hex');let queries=0;
 const prom=http.createServer((req,res)=>{req.resume();if(req.url.includes('/query'))queries++;res.setHeader('Content-Type','application/json');res.end(JSON.stringify({status:'success',data:req.url.includes('/query')?{resultType:'matrix',result:[]}:req.url.includes('buildinfo')?{version:'3.15.0'}:[]}));});await new Promise(r=>prom.listen(0,'127.0.0.1',r));
 const config=cp.spawnSync(process.env.OBS_PYTHON,[path.join(__dirname,'history-test-config.py'),dir,String(platformPort),String(grafanaPort),String(prom.address().port)],{encoding:'utf8',windowsHide:true});assert.equal(config.status,0,config.stderr);
 const children=[],logs=[];function start(binary,args,env,name){const child=cp.spawn(binary,args,{env:{...process.env,...env},cwd:dir,windowsHide:true,stdio:['ignore','pipe','pipe']});const log=fs.createWriteStream(path.join(dir,name+'.log'));child.stdout.pipe(log);child.stderr.pipe(log);logs.push(log);children.push(child);return child;}
 let browser;
 try{
  const home=process.env.GRAFANA_TEST_HOME;start(path.join(home,'bin','grafana.exe'),['server','--homepath',home,'--config',path.join(dir,'grafana.ini')],{},'grafana');
  start(process.env.OBS_JAVA,['-jar',path.resolve(__dirname,'../platform/target/gateway-platform-0.1.0.jar'),'--spring.datasource.url=jdbc:h2:mem:history_live;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE'],{PLATFORM_PORT:String(platformPort),PLATFORM_BIND_ADDRESS:'127.0.0.1',PLATFORM_ADMIN_PASSWORD:password,PLATFORM_MASTER_KEY_B64:crypto.randomBytes(32).toString('base64'),GRAFANA_LOCAL_PORT:String(grafanaPort)},'platform');
  const base='http://127.0.0.1:'+platformPort;
  let ready=false;for(let i=0;i<150;i++){try{ready=(await fetch(base+'/api/health')).ok&&(await fetch('http://127.0.0.1:'+grafanaPort+'/history/api/health')).ok;if(ready)break;}catch{}await new Promise(r=>setTimeout(r,400));}assert(ready,'Startup failed; private logs: '+dir);
  browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});const page=await browser.newPage({viewport:{width:1600,height:1100}}),bad=[];
  page.on('response',r=>{if(r.url().startsWith(base+'/history/')&&r.status()>=400)bad.push(r.status()+' '+new URL(r.url()).pathname);});
  await page.goto(base+'/login.html');await page.locator('[name=username]').fill('admin');await page.locator('[name=password]').fill(password);await page.locator('#submit').click();await page.waitForURL(base+'/');
  await page.getByRole('link',{name:'历史监控',exact:true}).click();await page.waitForURL(base+'/history.html');
  const frame=page.frameLocator('#dashboard');await frame.getByText('成功请求/秒',{exact:true}).first().waitFor({timeout:60000});
  for(let i=0;i<40&&queries===0;i++)await new Promise(r=>setTimeout(r,500));assert(queries>0,'Dashboard did not query Prometheus');
  assert.deepEqual(bad,[],'Unexpected Grafana request failures');
  await page.screenshot({path:path.resolve(__dirname,'../observability/target/history-integrated.png'),fullPage:true});
  const observer=await page.evaluate(()=>fetch('/history/api/user').then(r=>r.json()));assert.equal(observer.login,'gateway-platform-observer');assert.equal(observer.isGrafanaAdmin,false);
  await page.evaluate(async()=>{const s=await(await fetch('/api/session')).json();await fetch('/logout',{method:'POST',headers:{[s.csrfHeader]:s.csrf}});});
  const after=await page.request.get(base+'/history/api/user',{maxRedirects:0});assert.equal(after.status(),302);
  console.log('PASS: real Grafana embedded with platform login, query requests, Viewer identity, logout access revoked');console.log('Non-query upstream errors:',JSON.stringify(bad));console.log('Private local logs:',dir);
 }finally{if(browser)await browser.close();for(const child of children)child.kill();await Promise.all(children.map(c=>c.exitCode!==null?null:new Promise(r=>{c.once('exit',r);setTimeout(r,3000);})));logs.forEach(l=>l.end());prom.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
