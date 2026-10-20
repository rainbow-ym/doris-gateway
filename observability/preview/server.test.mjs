import test from 'node:test';
import assert from 'node:assert/strict';
import {api,fixtures,createServer} from './server.mjs';
const call=path=>api(new URL(path,'http://127.0.0.1'));
test('cluster is required and cross-cluster details are denied',()=>{
  assert.equal(call('/api/observability/requests')[0],400);
  const row=fixtures().find(r=>r.clusterId==='demo-a');
  assert.equal(call(`/api/observability/requests/${row.requestId}?cluster=demo-b`)[0],404);
});
test('pagination, filters and empty results',()=>{
  const [,page]=call('/api/observability/requests?cluster=demo-a&page=2&size=10');
  assert.equal(page.total,28);assert.equal(page.items.length,10);
  assert.equal(call('/api/observability/requests?cluster=demo-a&size=999')[0],400);
  assert.equal(call('/api/observability/requests?cluster=demo-a&page=999')[1].items.length,0);
  assert.ok(call('/api/observability/requests?cluster=demo-a&operation=DECRYPT_FILE')[1].items.every(r=>r.operation==='DECRYPT_FILE'));
});
test('security rejection is not a system error; missing resources are null',()=>{
  const [,summary]=call('/api/observability/summary?cluster=demo-a');
  assert.equal(summary.mode,'SIMULATION');assert.ok(summary.denied>0);assert.ok(summary.errors>0);
  assert.equal(summary.cpu,null);assert.equal(summary.inflight,null);assert.ok(summary.p99>=summary.p95);
});
test('HTTP is read-only, serves assets and denies arbitrary paths',async()=>{
  const server=createServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));
  try {
    const base=`http://127.0.0.1:${server.address().port}`;
    assert.equal((await fetch(base+'/')).status,200);
    assert.match(await (await fetch(base+'/app.js')).text(),/textContent/);
    assert.equal((await fetch(base+'/api/observability/requests',{method:'POST'})).status,405);
    assert.equal((await fetch(base+'/pom.xml')).status,404);
    assert.match((await fetch(base+'/')).headers.get('content-security-policy'),/frame-ancestors 'none'/);
  } finally {await new Promise(r=>server.close(r));}
});
