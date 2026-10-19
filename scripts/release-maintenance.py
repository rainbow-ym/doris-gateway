"""20261018 production release backup and platform replacement. No data deletion."""
import os,json,subprocess,shutil,time,sys
ROOT='/data/gateway-release-20261018'
NAMES=['doris-security-gateway-platform','doris-security-gateway','doris-security-gateway-admin']
def cmd(*a):return subprocess.check_output(a).decode().strip()
def inspect(n):return json.loads(cmd('docker','inspect',n))[0]
def save(p,v):
 with open(p,'w') as f:json.dump(v,f,indent=2)
 os.chmod(p,0o600)
os.umask(0o077)
action=sys.argv[1]
if action=='backup':
 path=ROOT+'/production-backup-'+time.strftime('%Y%m%d-%H%M%S');os.mkdir(path,0o700)
 rows={n:inspect(n) for n in NAMES};save(path+'/containers.json',rows)
 for n in NAMES:cmd('docker','stop',n)
 try:
  for n,o in rows.items():
   for i,m in enumerate(o['Mounts']):
    src=m['Source'];assert os.path.isdir(src) and os.path.realpath(src)==src and src.count('/')>=3
    shutil.copytree(src,path+'/'+n+'-'+str(i),symlinks=True)
  save(ROOT+'/backup-location.json',{'path':path})
 finally:
  for n in reversed(NAMES):cmd('docker','start',n)
 print('Cold backup completed:',path)
elif action=='inspect-absent':
 path=json.load(open(ROOT+'/backup-location.json'))['path'];rows=json.load(open(path+'/containers.json'))
 o=rows['doris-security-gateway-admin'];env=os.environ.copy();env.update(dict(v.split('=',1) for v in o['Config']['Env'] if '=' in v))
 i=next(i for i,m in enumerate(o['Mounts']) if m['Destination']=='/opt/gateway-admin/data')
 cp=ROOT+'/scripts:'+ROOT+'/admin/target/classes:'+open(ROOT+'/admin/target/test-classpath.txt').read().strip()
 subprocess.run(['/opt/jdk17/bin/java','-cp',cp,'VerifyAbsentDatabases',path+'/doris-security-gateway-admin-'+str(i)+'/gateway-admin'],env=env,check=True)
elif action=='platform':
 path=json.load(open(ROOT+'/backup-location.json'))['path'];old=json.load(open(path+'/containers.json'))[NAMES[0]]
 name=NAMES[0];assert old['HostConfig']['NetworkMode']=='host'
 envfile=path+'/platform.env'
 with open(envfile,'w') as f:f.write('\n'.join(old['Config']['Env'])+'\n')
 cmd('docker','stop',name);cmd('docker','rename',name,name+'-pre-release-20261018')
 args=['docker','run','-d','--name',name,'--net','host','--restart',old['HostConfig']['RestartPolicy']['Name'],'--env-file',envfile]
 for h in old['HostConfig'].get('ExtraHosts') or []:args+=['--add-host',h]
 for m in old['Mounts']:args+=['-v',(m['Name'] if m['Type']=='volume' else m['Source'])+':'+m['Destination']+(':'+m['Mode'] if m.get('Mode') else '')]
 args+=['doris-security-gateway-platform:release-20261018']
 try:cmd(*args)
 except Exception:
  cmd('docker','rename',name+'-pre-release-20261018',name);cmd('docker','start',name);raise
 print('Platform replaced; original container and cold backup retained')
else:raise ValueError('unsupported action')
