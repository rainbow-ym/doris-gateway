#!/usr/bin/env bash
# Dedicated existing deployment rebuild. No init, no volume/image/data deletion.
set -euo pipefail
set +x
command -v python3 >/dev/null || { echo '需要 Python 3（无需 pip）'; exit 2; }
exec python3 - "${1:-/data/doris-gateway/install_dir}" "${2:-doris-gateway}" <<'PY'
import os,sys,re,json,subprocess,shutil,socket,time,base64,datetime
os.umask(0o077)
root=os.path.realpath(sys.argv[1]); prefix=sys.argv[2]
if not re.fullmatch(r'[a-z][a-z0-9-]{0,45}',prefix):sys.exit('实例名称只允许小写字母、数字和短横线')
if not os.path.isdir(root) or root.count('/')<2:sys.exit('必须指定已有专用安装目录，拒绝创建空安装目录')
def docker(*args):
    p=subprocess.run(['docker']+list(args),stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True)
    if p.returncode:raise RuntimeError('Docker '+args[0]+' 失败；配置和备份保留，请检查 Docker 状态')
    return p.stdout.strip()
def inspect(name):
    p=subprocess.run(['docker','inspect',name],stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True)
    if p.returncode:return None
    return json.loads(p.stdout)[0]
def environment(info):return dict(x.split('=',1) for x in info['Config'].get('Env',[]) if '=' in x)
def readenv(path):
    result={}
    with open(path,encoding='utf-8-sig') as f:
        for line in f.read().splitlines():
            if not line.strip() or line.lstrip().startswith('#'):continue
            if '=' not in line:raise RuntimeError('ENV 行格式无效：'+os.path.basename(path))
            key,value=line.split('=',1)
            if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*',key) or key in result:raise RuntimeError('ENV 键无效或重复：'+os.path.basename(path))
            result[key]=value
    return result
def writeenv(path,values):
    temporary=path+'.sm4-new'
    with open(temporary,'w',encoding='utf-8',newline='\n') as f:
        for key,value in values.items():
            if '\n' in value or '\r' in value:raise RuntimeError('ENV 值包含换行，停止')
            f.write(key+'='+value+'\n')
    os.chmod(temporary,0o600);os.replace(temporary,path)
names={k:prefix+'-'+k for k in ('worker','proxy','platform')}
images={'worker':'doris-security-gateway-admin:sm4-row-v2-20261019','proxy':'doris-security-gateway:sm4-row-v2-20261019','platform':'doris-security-gateway-platform:sm4-row-v2-20261019'}
destinations={'worker':{'/opt/gateway-admin/data':'worker-data'},'proxy':{'/opt/gateway/conf':'conf','/opt/gateway/logs':'logs'},'platform':{'/opt/gateway-platform/data':'platform-data'}}
backup=None;stopped=[];removed=False
try:
    host=socket.gethostname()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,252}',host):raise RuntimeError('hostname 格式无效')
    for image in images.values():docker('inspect','--type=image',image)
    infos={k:inspect(n) for k,n in names.items()};paths={};envs={};data={}
    for kind in names:
        basename='gateway' if kind=='proxy' else kind
        candidates=[os.path.join(root,basename+s) for s in ('.env','.ENV') if os.path.isfile(os.path.join(root,basename+s))]
        if len(candidates)!=1:raise RuntimeError('需要且只能有一份 '+basename+'.env 或 '+basename+'.ENV')
        paths[kind]=candidates[0];envs[kind]=readenv(candidates[0])
        info=infos[kind]
        if info:
            merged=environment(info);merged.update(envs[kind]);envs[kind]=merged
            if any(m['Type'] not in ('bind','volume') for m in info.get('Mounts',[])):raise RuntimeError('存在特殊挂载，停止以免遗漏数据')
        if info and (info['HostConfig']['NetworkMode']!='host' or info['HostConfig'].get('Privileged')):raise RuntimeError('不支持非 host 网络或特权容器，未删除任何容器')
        if info and (info['HostConfig'].get('PortBindings') or info['HostConfig'].get('Devices')):raise RuntimeError('存在定制端口或设备配置，停止以免丢失参数')
        for destination,folder in destinations[kind].items():
            expected=os.path.realpath(os.path.join(root,folder))
            if not os.path.isdir(expected) or os.path.commonpath([root,expected])!=root:raise RuntimeError('缺少原数据目录或目录位于安装目录之外：'+folder)
            if info:
                matches=[m for m in info.get('Mounts',[]) if m['Destination']==destination]
                if len(matches)!=1 or os.path.realpath(matches[0]['Source'])!=expected:raise RuntimeError('旧容器实际数据不是指定安装目录：'+names[kind]+'；拒绝切换到空数据')
            data[folder]=expected
    required={'worker':['ADMIN_USERNAME','ADMIN_PASSWORD','ADMIN_MASTER_KEY_B64','PROXY_ADMIN_USER','PROXY_ADMIN_PASSWORD','LINEAGE_INGRESS_TOKEN'], 'proxy':['GATEWAY_LINEAGE_TOKEN'], 'platform':['PLATFORM_ADMIN_USER','PLATFORM_ADMIN_PASSWORD','PLATFORM_MASTER_KEY_B64']}
    for kind,keys in required.items():
        original=environment(infos[kind]) if infos[kind] else {}
        for key in keys:
            if not envs[kind].get(key):
                if not original.get(key):raise RuntimeError('缺少原凭据 '+key+'；不生成新密码覆盖原系统')
                envs[kind][key]=original[key]
    w,g,p=envs['worker'],envs['proxy'],envs['platform']
    wp=w.get('ADMIN_PORT','18082');gp=g.get('GATEWAY_PORT','3308');pp=p.get('PLATFORM_PORT','18080')
    if len({wp,gp,pp})!=3 or any(not x.isdigit() or not 1024<=int(x)<=65535 for x in (wp,gp,pp)):raise RuntimeError('三个服务端口必须有效且不同')
    if w['LINEAGE_INGRESS_TOKEN']!=g['GATEWAY_LINEAGE_TOKEN']:raise RuntimeError('Worker 与网关令牌不一致，未删除容器')
    bind=g.get('GATEWAY_BIND_ADDRESS','0.0.0.0')
    w.update(ADMIN_PORT=wp,ADMIN_BIND_ADDRESS='127.0.0.1',PROXY_PORT=gp,PROXY_HOST='127.0.0.1' if bind in ('0.0.0.0','::') else bind,GATEWAY_MULTI_USER_ENABLED='true')
    g.update(GATEWAY_PORT=gp,GATEWAY_BIND_ADDRESS=bind,GATEWAY_MULTI_USER_ENABLED='true',GATEWAY_CONTROL_ENDPOINT='http://127.0.0.1:'+wp+'/api/internal/sql',GATEWAY_LINEAGE_ENDPOINT='http://127.0.0.1:'+wp+'/api/internal/lineage/events')
    p.setdefault('PLATFORM_PORT',pp);p.setdefault('PLATFORM_BIND_ADDRESS','0.0.0.0')
    # Preserve the exact H2 file path and settings; disable only AUTO_SERVER.
    for values in (w,p):
        if 'SPRING_DATASOURCE_URL' in values:
            values['SPRING_DATASOURCE_URL']=re.sub(r'(?i)AUTO_SERVER=TRUE','AUTO_SERVER=FALSE',values['SPRING_DATASOURCE_URL'])
    keypath=os.path.join(data['worker-data'],'sm4-export-keys.properties')
    if w.get('SM4_EXPORT_KEY_FILE','/opt/gateway-admin/data/sm4-export-keys.properties')!='/opt/gateway-admin/data/sm4-export-keys.properties':raise RuntimeError('存在自定义 SM4 密钥路径，停止以免使用错误密钥')
    for otherid in docker('ps','-q').splitlines():
        other=inspect(otherid)
        if other['Name'].lstrip('/') in names.values():continue
        if any(os.path.realpath(m['Source']) in data.values() for m in other.get('Mounts',[])):raise RuntimeError('其他运行容器共享本实例数据：'+other['Name']+'；先停止共享实例')
    needed=sum(os.path.getsize(os.path.join(d,f)) for path in data.values() for d,_,files in os.walk(path) for f in files if not os.path.islink(os.path.join(d,f)))
    if shutil.disk_usage(root).free<needed+256*1024*1024:raise RuntimeError('原目录可用空间不足以备份，未停止或删除容器')
    backup=os.path.join(root,'backup-before-sm4-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
    os.mkdir(backup,0o700)
    with open(os.path.join(backup,'containers.json'),'w') as f:json.dump(infos,f,indent=2)
    for path in paths.values():shutil.copy2(path,os.path.join(backup,os.path.basename(path)))
    if os.path.isfile(os.path.join(root,'install.conf')):shutil.copy2(os.path.join(root,'install.conf'),backup)
    print('主机名：'+host+'；安装目录：'+root+'；备份：'+backup,flush=True)
    for kind in ('platform','proxy','worker'):
        if infos[kind] and infos[kind]['State']['Running']:docker('stop',names[kind]);stopped.append(kind)
    for folder,path in data.items():shutil.copytree(path,os.path.join(backup,folder),symlinks=True)
    # Do not overwrite old keys, passwords, H2 files, conf or business rules.
    if not os.path.exists(keypath):
        with open(keypath,'x') as f:f.write('v1='+base64.b64encode(os.urandom(16)).decode()+'\n')
        os.chmod(keypath,0o600)
    for kind in names:writeenv(paths[kind],envs[kind])
    print('冷备完成，开始删除三个指定容器（不加 -v，不删除数据或镜像）。',flush=True)
    for kind in ('platform','proxy','worker'):
        if infos[kind]:docker('rm',names[kind]);removed=True
    for kind in ('worker','proxy','platform'):
        args=['run','-d','--name',names[kind],'--net','host','--restart','unless-stopped','--env-file',paths[kind]]
        old=infos[kind];hosts=(old['HostConfig'].get('ExtraHosts') or []) if old else []
        if old:
            for key,option in (('Memory','--memory'),('MemorySwap','--memory-swap'),('CpuShares','--cpu-shares')):
                if old['HostConfig'].get(key):args += [option,str(old['HostConfig'][key])]
        for h in hosts:
            if not h.startswith(host+':'):args+=['--add-host',h]
        args+=['--add-host',host+':127.0.0.1']
        if old:
            for m in old['Mounts']:args+=['-v',(m.get('Name') if m['Type']=='volume' else m['Source'])+':'+m['Destination']+(':'+m['Mode'] if m.get('Mode') else '')]
        else:
            for target,folder in destinations[kind].items():args+=['-v',data[folder]+':'+target+':Z']
        args.append(images[kind]);docker(*args)
    for attempt in range(90):
        states=[inspect(name) for name in names.values()]
        if all(x and x['State']['Running'] and x['State'].get('Health',{}).get('Status')=='healthy' for x in states):
            print('三个容器均已健康。门户端口 '+pp+'，MySQL 端口 '+gp+'。请登录平台验证查询和脱敏。')
            print('配置仍在原目录；备份含凭据，请勿公开。不要重新 init，也不要同时启动旧备份容器。')
            print('备份位置：'+backup);break
        time.sleep(2)
    else:raise RuntimeError('容器未在 180 秒内全部健康，请检查 docker logs；不能认定更新成功')
except Exception as e:
    print('停止：'+str(e),file=sys.stderr)
    if backup:print('备份保留：'+backup,file=sys.stderr)
    if not removed:
        for kind in reversed(stopped):
            try:docker('start',names[kind])
            except Exception:pass
    else:print('旧容器已删除，但数据、镜像、ENV 和容器配置备份均保留。不要 init 或覆盖冷备；先检查故障。',file=sys.stderr)
    sys.exit(1)
PY
