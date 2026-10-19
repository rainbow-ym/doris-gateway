"""Replace stopped production platform after safe-upgrade stage. Preserve mounts and cold backup."""
import json,os,subprocess,shutil
name='doris-security-gateway-platform'
root='/data/gateway-release-20261019'
def docker(*args):
 p=subprocess.run(['docker']+list(args),stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True)
 if p.returncode:raise RuntimeError('Docker operation failed: '+args[0])
 return p.stdout.strip()
os.umask(0o077)
o=json.loads(docker('inspect',name))[0]
assert not o['State']['Running'],'Stage must stop platform first'
assert o['HostConfig']['NetworkMode']=='host'
backup=root+'/platform-cold-backup'
os.mkdir(backup,0o700)
with open(backup+'/container.json','w') as f:json.dump(o,f)
for i,m in enumerate(o['Mounts']):
 src=m['Source'];assert os.path.isdir(src) and os.path.realpath(src)==src and src.count('/')>=3
 shutil.copytree(src,backup+'/mount-'+str(i),symlinks=True)
envfile=backup+'/platform.env'
with open(envfile,'w') as f:f.write('\n'.join(o['Config']['Env'])+'\n')
old=name+'-pre-release-20261019'
docker('rename',name,old)
args=['create','--name',name,'--net','host','--restart',o['HostConfig']['RestartPolicy']['Name'],'--env-file',envfile]
for h in o['HostConfig'].get('ExtraHosts') or []:args+=['--add-host',h]
for m in o['Mounts']:args+=['-v',(m['Name'] if m['Type']=='volume' else m['Source'])+':'+m['Destination']+(':'+m['Mode'] if m.get('Mode') else '')]
args+=['doris-security-gateway-platform:release-20261019']
try:docker(*args)
except Exception:
 docker('rename',old,name)
 raise
print('Platform prepared (stopped); original container and cold data backup retained: '+backup)
