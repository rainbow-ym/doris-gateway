const http=require('http');
http.get({hostname:'127.0.0.1',port:18082,path:'/api/connections',auth:process.env.ADMIN_USERNAME+':'+process.env.ADMIN_PASSWORD},r=>{
 let b='';r.on('data',s=>b+=s);r.on('end',()=>{if(r.statusCode!==200)throw Error('preflight HTTP '+r.statusCode);const roots=JSON.parse(b).filter(s=>s.username==='root');console.log(JSON.stringify(roots.map(s=>({id:s.id,host:s.host,port:s.port}))))});
}).on('error',e=>{console.error(e.message);process.exitCode=1});
