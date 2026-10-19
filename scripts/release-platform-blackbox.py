"""HTTP black-box platform authentication / CSRF / relay tests, no secret output."""
import json,subprocess,urllib.request,urllib.parse,urllib.error,http.cookiejar
o=json.loads(subprocess.check_output(['docker','inspect','doris-security-gateway-platform']))[0]
e=dict(v.split('=',1) for v in o['Config']['Env'] if '=' in v)
base='http://192.168.232.132:18080'
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
op=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),NoRedirect())
def call(path,data=None,headers=None):
 r=urllib.request.Request(base+path,data=data,headers=headers or {})
 try:
  with op.open(r,timeout=20) as s:return s.status,s.read()
 except urllib.error.HTTPError as err:return err.code,err.read()
assert call('/api/clusters')[0]==401
status,body=call('/api/clusters',b'{}',{'Content-Type':'application/json'})
assert status in (401,403,302), 'unauthenticated POST was not rejected: '+str(status)
status,body=call('/api/session');assert status==200;s=json.loads(body)
payload=urllib.parse.urlencode({'username':e.get('PLATFORM_ADMIN_USER','admin'),'password':e['PLATFORM_ADMIN_PASSWORD'],'_csrf':s['csrf']}).encode()
status,body=call('/login',payload,{'Content-Type':'application/x-www-form-urlencoded'});assert status==200,'Configured admin credential did not authenticate; no password reset attempted'
status,body=call('/api/session');session=json.loads(body);assert session['authenticated']
status,body=call('/api/clusters');assert status==200;clusters=json.loads(body)
target=[c for c in clusters if ':18082' in c['workerUrl']];assert len(target)==1
status,body=call('/api/clusters/'+target[0]['id']+'/relay/api/logical-databases');assert status==200
rows=json.loads(body);assert len(rows)==3 and all(r['status']=='ACTIVE' for r in rows)
assert call('/api/clusters',b'{}',{'Content-Type':'application/json'})[0]==403
status,body=call('/console/cluster-ui.js');assert status==200 and b'STALE_EXECUTION' in body
if '--identity' in __import__('sys').argv:
 assert b'reviewNewSource' in body and b'reviewIdentityBaseline' in body
 status,body=call('/api/clusters/'+target[0]['id']+'/relay/api/object-identities/'+urllib.parse.quote('DWD_人口')+'/preview')
 assert status==200 and isinstance(json.loads(body),list)
 print('PASS new identity UI delivered and authenticated read-only preview relayed; no baseline enabled')
print('PASS platform unauthenticated access rejected, admin login, CSRF protection, cluster relay, latest UI resources')
status,_=call('/logout',b'',{session['csrfHeader']:session['csrf']});assert status==204
assert call('/api/clusters')[0]==401
print('PASS logout invalidates management session')
