"""Read-only production release inventory; never prints credentials or SQL."""
import json, subprocess, urllib.request, base64
for name in ('doris-security-gateway','doris-security-gateway-admin','doris-security-gateway-platform'):
    obj=json.loads(subprocess.check_output(['docker','inspect',name]))[0]
    env=dict(x.split('=',1) for x in obj['Config']['Env'] if '=' in x)
    print(json.dumps({'name':name,'image':obj['Config']['Image'],'network':obj['HostConfig']['NetworkMode'],
        'mounts':[{k:m.get(k) for k in ('Type','Source','Destination')} for m in obj['Mounts']],
        'ports':{k:v for k,v in env.items() if k in ('ADMIN_PORT','ADMIN_BIND_ADDRESS','GATEWAY_PORT','GATEWAY_BIND_ADDRESS','PLATFORM_PORT','PLATFORM_BIND_ADDRESS')}},ensure_ascii=False))
    if name.endswith('-admin'):
        auth=base64.b64encode((env['ADMIN_USERNAME']+':'+env['ADMIN_PASSWORD']).encode()).decode()
        for path in ('logical-databases','managed-executions'):
            req=urllib.request.Request('http://127.0.0.1:'+env.get('ADMIN_PORT','18082')+'/api/'+path,headers={'Authorization':'Basic '+auth})
            data=json.load(urllib.request.urlopen(req,timeout=20))
            print(path,json.dumps([{k:v for k,v in r.items() if k in ('logicalName','status','executionId','statementType')} for r in data if path=='logical-databases' or r.get('status') not in ('COMMITTED','ABORTED')],ensure_ascii=False))
