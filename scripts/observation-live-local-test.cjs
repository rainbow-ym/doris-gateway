// Real isolated Worker HTTP + browser, synthetic numeric events only. No Docker or remote access.
const fs=require('fs'),path=require('path'),os=require('os'),net=require('net'),cp=require('child_process'),crypto=require('crypto'),assert=require('assert/strict');
const {chromium}=require(path.join(path.dirname(process.execPath),'../node_modules/playwright'));
const java=process.env.OBS_JAVA,jar=path.resolve(__dirname,'../admin/target/doris-security-gateway-admin-0.1.0.jar');
if(!java||!fs.existsSync(jar))throw Error('Set OBS_JAVA and build the Worker jar first');
(async()=>{
 const socket=net.createServer();await new Promise(r=>socket.listen(0,'127.0.0.1',r));const port=socket.address().port;await new Promise(r=>socket.close(r));
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'gateway-observation-live-')),password=crypto.randomBytes(18).toString('hex'),token=crypto.randomBytes(24).toString('hex');
 const child=cp.spawn(java,['-jar',jar,'--spring.datasource.url=jdbc:h2:mem:observation_live;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE'],{cwd:dir,windowsHide:true,env:{...process.env,ADMIN_PORT:String(port),ADMIN_BIND_ADDRESS:'127.0.0.1',ADMIN_USERNAME:'observer',ADMIN_PASSWORD:password,ADMIN_MASTER_KEY_B64:crypto.randomBytes(32).toString('base64'),LINEAGE_INGRESS_TOKEN:token,PROXY_HOST:'127.0.0.1',PROXY_PORT:'65530',PROXY_ADMIN_USER:'unused',PROXY_ADMIN_PASSWORD:password,OBSERVABILITY_JOURNAL_DIR:path.join(dir,'telemetry')},stdio:['ignore','pipe','pipe']});
 const log=fs.createWriteStream(path.join(dir,'worker.log'));child.stdout.pipe(log);child.stderr.pipe(log);
 let browser;
 const base=`http://127.0.0.1:${port}`,headers={Authorization:'Basic '+Buffer.from('observer:'+password).toString('base64')};
 try{
  let up=false;for(let i=0;i<100;i++){if(child.exitCode!==null)throw Error('Isolated Worker exited; log: '+path.join(dir,'worker.log'));try{if((await fetch(base+'/api/health')).ok){up=true;break;}}catch{}await new Promise(r=>setTimeout(r,200));}assert(up,'Worker startup timeout');
  assert.equal((await fetch(base+'/api/observability/status')).status,401);
  const status=await(await fetch(base+'/api/observability/status',{headers})).json();assert.equal(status.mode,'LIVE');assert.equal(status.journalEnabled,true);
  const id=crypto.randomUUID();
  const event={schemaVersion:1,requestId:id,spanId:crypto.randomUUID(),operation:'ENCRYPT_QUERY',reason:'PERMISSION_DENIED',durationNanos:30000000,startedAtMillis:Date.now(),stages:{WORKER_CALL:{calls:1,totalNanos:20000000,maxNanos:20000000}},measures:{ROWS:2}};
  assert.equal((await fetch(base+'/api/internal/sql/observation',{method:'POST',headers:{'Content-Type':'application/json','X-Gateway-Lineage-Token':'wrong'},body:JSON.stringify(event)})).status,403);
  for(let i=0;i<23;i++){event.requestId=i?crypto.randomUUID():id;event.spanId=crypto.randomUUID();assert.equal((await fetch(base+'/api/internal/sql/observation',{method:'POST',headers:{'Content-Type':'application/json','X-Gateway-Lineage-Token':token},body:JSON.stringify(event)})).status,200);}
  const metrics=await(await fetch(base+'/api/observability/metrics',{headers})).text();assert.match(metrics,/gateway_crypto_duration_seconds_bucket/);assert(!metrics.includes(id));
  const slow=await(await fetch(base+'/api/observability/slowest?component=proxy&operation=ENCRYPT_QUERY',{headers})).json();assert.equal(slow.length,20);
  browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const page=await browser.newPage({httpCredentials:{username:'observer',password},viewport:{width:1400,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base+'/');await page.getByRole('button',{name:'加解密运行监控',exact:true}).click();
  await page.selectOption('#obsComponent','proxy');await page.waitForFunction(()=>document.querySelector('#obsPage').textContent.includes('23'));
  assert.equal(await page.locator('#obsSlow button').count(),20);
  await page.getByRole('button',{name:'查看详情',exact:true}).first().click();await page.waitForSelector('#obsDetail[open]');assert.match(await page.locator('#obsDetailBody').innerText(),/权限不足/);
  await page.locator('#obsClose').click();await page.locator('#obsNext').click();await page.waitForFunction(()=>document.querySelector('#obsPage').textContent.includes('第 2'));
  assert.equal(await page.locator('#obsRequests button').count(),3);
  const target=path.resolve(__dirname,'../observability/target');fs.mkdirSync(target,{recursive:true});await page.screenshot({path:path.join(target,'live-monitor.png'),fullPage:true});
  assert.deepEqual(errors,[]);console.log('PASS real isolated Worker auth, bounded telemetry ingress, Prometheus, live UI paging and details; no production contact');
 }finally{if(browser)await browser.close();child.kill();await Promise.race([new Promise(r=>child.once('exit',r)),new Promise(r=>setTimeout(r,3000))]);log.end();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
