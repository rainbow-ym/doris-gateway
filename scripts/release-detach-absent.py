"""Detach only the two explicitly authorized, independently verified absent logical databases."""
import json,subprocess,base64,urllib.request,urllib.parse
o=json.loads(subprocess.check_output(['docker','inspect','doris-security-gateway-admin']))[0]
e=dict(v.split('=',1) for v in o['Config']['Env'] if '=' in v)
auth=base64.b64encode((e['ADMIN_USERNAME']+':'+e['ADMIN_PASSWORD']).encode()).decode()
def api(path,method='GET'):
 r=urllib.request.Request('http://127.0.0.1:'+e['ADMIN_PORT']+'/api/'+path,method=method,headers={'Authorization':'Basic '+auth})
 return json.load(urllib.request.urlopen(r,timeout=60))
targets={'DWH_人口','DWH_入口'};before=api('logical-databases')
assert all(r['status']=='FAILED' for r in before if r['logicalName'] in targets)
remaining=[r for r in before if r['logicalName'] not in targets]
for name in sorted(targets):
 result=api('logical-databases/'+urllib.parse.quote(name,safe=''),'DELETE')
 assert result['status']=='DETACHED';print('Detached gateway mapping:',name)
assert api('logical-databases')==remaining
print('PASS all other logical database mappings unchanged')
