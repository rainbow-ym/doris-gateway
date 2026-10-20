// Loopback-only simulation. No database, Docker, gateway or production API access.
import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

export const reasons = {
  OK: ['SUCCESS','成功','无须处理'],
  PERMISSION_DENIED: ['SECURITY_DENIED','权限不足','检查源账号当前授权，不要绕过权限校验'],
  TAMPERED: ['SECURITY_DENIED','完整性校验失败','重新导出文件，不要修改密文或业务单元格'],
  CAPACITY: ['BUSY','服务繁忙','检查并发和请求规模'],
  TIMEOUT: ['DEPENDENCY_ERROR','依赖超时','检查对应依赖的连通性和耗时'],
  CLIENT_CANCELLED: ['CANCELLED','客户端取消','确认客户端超时或主动取消'],
  UNKNOWN: ['INTERNAL_ERROR','内部异常','使用请求编号关联受控服务日志']
};
export function fixtures() {
  const codes = Object.keys(reasons);
  return Array.from({length: 56}, (_, i) => {
    const reason = codes[i % codes.length];
    return {schemaVersion:1, requestId:`00000000-0000-4000-8000-${String(i+1).padStart(12,'0')}`,
      clusterId:i%2 ? 'demo-b' : 'demo-a', instanceId:'demo-process', operation:i%3 ? 'ENCRYPT_QUERY':'DECRYPT_FILE',
      outcome:reasons[reason][0], reason, label:reasons[reason][1], suggestion:reasons[reason][2],
      durationMs:180+i*37, rows:20+i*5, protectedCells:(20+i*5)*3,
      rawBytes:(20+i*5)*48, ciphertextBytes:(20+i*5)*640,
      stages:[{name:'结果准备 / 文件解析',totalMs:30+i,calls:1,component:'入口'},
        {name:'Worker 调用（包含下列子阶段，不可累加）',totalMs:100+i*25,calls:1,component:'Proxy'},
        {name:'来源校验',totalMs:45+i*12,calls:3,component:'Worker · 子阶段'},
        {name:'权限校验',totalMs:20+i*4,calls:3,component:'Worker · 子阶段'},
        {name:'SM4 计算',totalMs:5+i,calls:20+i*5,component:'Worker · 子阶段'}],
      incomplete:false};
  });
}
const rows = fixtures();
function percentile(values, p) { return values.length ? [...values].sort((a,b)=>a-b)[Math.ceil(values.length*p)-1] : null; }
export function api(url) {
  const cluster = url.searchParams.get('cluster');
  if (!['demo-a','demo-b'].includes(cluster)) return [400,{message:'必须选择模拟集群'}];
  let selected = rows.filter(r=>r.clusterId===cluster);
  const operation = url.searchParams.get('operation');
  if (operation && !['ENCRYPT_QUERY','DECRYPT_FILE'].includes(operation)) return [400,{message:'操作类型无效'}];
  if (operation) selected=selected.filter(r=>r.operation===operation);
  if (url.pathname==='/api/observability/status') return [200,{mode:'SIMULATION',liveConnected:false,retention:'仅模拟内存数据',resourcesAvailable:false}];
  if (url.pathname==='/api/observability/summary') return [200,{mode:'SIMULATION',total:selected.length,
    success:selected.filter(r=>r.outcome==='SUCCESS').length,
    denied:selected.filter(r=>r.outcome==='SECURITY_DENIED').length,
    errors:selected.filter(r=>['INTERNAL_ERROR','DEPENDENCY_ERROR'].includes(r.outcome)).length,
    p50:percentile(selected.map(r=>r.durationMs),.5),p95:percentile(selected.map(r=>r.durationMs),.95),p99:percentile(selected.map(r=>r.durationMs),.99),
    inflight:null,cpu:null,memory:null}];
  if (url.pathname==='/api/observability/requests') {
    const outcome=url.searchParams.get('outcome');
    if (outcome) selected=selected.filter(r=>r.outcome===outcome);
    const page=Number(url.searchParams.get('page')||1),size=Number(url.searchParams.get('size')||10);
    if (!Number.isInteger(page)||page<1||!Number.isInteger(size)||size<1||size>100) return [400,{message:'分页参数无效'}];
    return [200,{mode:'SIMULATION',total:selected.length,page,size,items:selected.slice((page-1)*size,page*size)}];
  }
  if (url.pathname.startsWith('/api/observability/requests/')) {
    const item=selected.find(r=>r.requestId===url.pathname.split('/').pop());
    return item ? [200,item] : [404,{message:'当前集群中没有该请求'}];
  }
  return [404,{message:'接口不存在'}];
}
export function createServer() {
  return http.createServer(async (req,res)=> {
    const send=(status,body,type='application/json; charset=utf-8')=>{
      res.writeHead(status,{'Content-Type':type,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
        'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'"});
      res.end(type.startsWith('application/json')?JSON.stringify(body):body);
    };
    if(req.method!=='GET') return send(405,{message:'模拟服务只读'});
    const url=new URL(req.url,'http://127.0.0.1');
    if(url.pathname.startsWith('/api/')) { const [status,body]=api(url); return send(status,body); }
    const files={'/':'index.html','/app.js':'app.js','/style.css':'style.css'};
    if(!files[url.pathname]) return send(404,{message:'Not found'});
    try {send(200,await readFile(new URL(files[url.pathname],import.meta.url)),url.pathname.endsWith('.js')?'text/javascript; charset=utf-8':url.pathname.endsWith('.css')?'text/css; charset=utf-8':'text/html; charset=utf-8');}
    catch {send(500,{message:'预览文件读取失败'});}
  });
}
if(process.argv[1]===fileURLToPath(import.meta.url)) {
  const port=Number(process.env.OBS_PREVIEW_PORT||18190);
  const server=createServer(); server.on('error',e=>{console.error(e.message);process.exitCode=1;});
  server.listen(port,'127.0.0.1',()=>console.log(`仅模拟数据：http://127.0.0.1:${port}；未连接真实网关`));
}
