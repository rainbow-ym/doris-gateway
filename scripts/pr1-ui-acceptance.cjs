// Real platform login, relay, SM4 form and downloaded CSV; isolated 18090/18086/3312 only.
const fs=require('fs'),path=require('path'),assert=require('assert/strict'),cp=require('child_process');
const {chromium}=require(path.join(path.dirname(process.execPath),'../node_modules/playwright'));
const db='gateway_multi_acceptance_20261014';
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const page=await browser.newPage(),errors=[];page.setDefaultTimeout(20000);page.on('pageerror',e=>errors.push(e.message));let cluster;
 async function api(p,body){return page.evaluate(async({p,body})=>{const s=await(await fetch('/api/session')).json();const r=await fetch(p,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',[s.csrfHeader]:s.csrf},body:body===undefined?undefined:JSON.stringify(body)});if(!r.ok)throw Error('HTTP '+r.status);return r.json();},{p,body});}
 try{
  await page.goto(process.env.UI_TEST_URL+'/login.html');await page.locator('[name=username]').fill(process.env.UI_ADMIN_USER);await page.locator('[name=password]').fill(process.env.UI_ADMIN_PASSWORD);await page.locator('#submit').click();await page.waitForURL(process.env.UI_TEST_URL+'/');
  const clusters=await api('/api/clusters');cluster=clusters.find(c=>c.name==='PR1 isolated acceptance');
  if(!cluster)cluster=await api('/api/clusters',{name:'PR1 isolated acceptance',workerUrl:'http://127.0.0.1:18086',workerUsername:process.env.UI_WORKER_USER,workerPassword:process.env.UI_WORKER_PASSWORD,gatewayAddress:'127.0.0.1:3312'});
  console.log('PASS authenticated platform and registered isolated cluster');
  await page.goto(process.env.UI_TEST_URL+'/console/index.html?cluster='+cluster.id);
  await page.getByRole('button',{name:'SM4 加密导出',exact:true}).click();
  const form=page.locator('#sm4ProfileForm');await form.locator('[name=username]').fill('root');await form.locator('[name=databaseName]').fill(db);await form.locator('[name=enabled]').check();await form.locator('[name=allowDecrypt]').check();await form.locator('[name=maxRows]').fill('20');
  await form.locator('button').click();await page.locator('#confirmYes').click();
  await page.waitForFunction(()=>document.querySelector('#sm4Profiles').textContent.includes('root'));
  const profiles=await api('/api/clusters/'+cluster.id+'/relay/api/sm4-export/profiles');assert(profiles.some(p=>p.username==='root'&&p.enabled&&p.allowDecrypt));
  const command=`cd /data/gateway-pr1-f4d910d; /opt/node/bin/node -e 'const m=require("/opt/scip-runtime/node_modules/mysql2/promise");(async()=>{const c=await m.createConnection({host:"127.0.0.1",port:3312,user:"root",password:process.env.DORIS_TEST_PASSWORD,database:"${db}",rowsAsArray:true});const [r,f]=await c.execute("SELECT id, id_card AS alias_card FROM AA WHERE id=?",[1]);console.log(JSON.stringify([f.map(x=>x.name),...r]));await c.end();})().catch(()=>process.exit(1));'`;
  const raw=cp.execFileSync('python',['scripts/remote-work.py','--command','export DORIS_TEST_PASSWORD=1; '+command],{encoding:'utf8'});
  const rows=JSON.parse(raw);assert.equal(rows[0].at(-1),'__gateway_sm4');assert(rows[1].at(-1).startsWith('__GW_SM4_V1__:'));assert(rows[1][1].includes('*'));
  const csv=rows.map(r=>r.map(v=>'"'+(v==null?'':String(v)).replace(/"/g,'""')+'"').join(',')).join('\r\n')+'\r\n';
  await page.locator('#sm4CsvFile').setInputFiles({name:'isolated-export.csv',mimeType:'text/csv',buffer:Buffer.from(csv)});
  const waiting=page.waitForEvent('download');await page.getByRole('button',{name:'校验并下载明文',exact:true}).click();const download=await waiting;
  const content=fs.readFileSync(await download.path(),'utf8');assert(content.includes('110101199001011234'));assert(!content.includes('__GW_SM4_V1__:'));assert.match(download.suggestedFilename(),/^decrypted-/);
  assert.deepEqual(errors,[]);console.log('PASS real platform login, cluster relay, UI enables SM4, MySQL final ciphertext column, CSV upload and plaintext browser download');
 }finally{
  if(cluster)await api('/api/clusters/'+cluster.id+'/relay/api/sm4-export/profiles',{username:'root',databaseName:db,enabled:false,allowDecrypt:false,maxRows:20,ttlMinutes:10});
  await browser.close();
 }
})().catch(e=>{console.error(e.message);process.exitCode=1;});
