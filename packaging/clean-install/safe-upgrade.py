#!/usr/bin/env python3
"""Offline staged upgrade for one worker/Proxy pair. Python 3 standard library, no Compose.
Never deletes a data directory/container. A staged instance listens on loopback only.
"""
import argparse, base64, json, os, re, shutil, socket, subprocess, sys, time, urllib.request, uuid

def docker(*args):
    p=subprocess.run(['docker']+list(args),stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True)
    if p.returncode: raise RuntimeError('Docker 操作失败：'+args[0]+'；请检查容器状态，不输出可能含凭据的参数。')
    return p.stdout.strip()

def inspect(name):
    return json.loads(docker('inspect',name))[0]

def exists(name):
    return subprocess.call(['docker','inspect',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)==0

def save(state,path):
    with open(path+'.tmp','w') as f: json.dump(state,f,ensure_ascii=False,indent=2)
    os.chmod(path+'.tmp',0o600);os.replace(path+'.tmp',path)

def env(info):
    return dict(x.split('=',1) for x in info['Config']['Env'] if '=' in x)

def request(info,path,body=None):
    e=env(info);port=int(e.get('ADMIN_PORT','18080'))
    auth=base64.b64encode((e.get('ADMIN_USERNAME','admin')+':'+e['ADMIN_PASSWORD']).encode()).decode()
    req=urllib.request.Request('http://127.0.0.1:'+str(port)+path,data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Basic '+auth,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=60) as r:return json.load(r)
    except Exception:
        raise RuntimeError('管理接口校验失败：'+path+'；不输出凭据或响应数据。请检查版本历史及服务日志。')

def ready(info):
    for _ in range(60):
        try:request(info,'/api/health');return
        except RuntimeError:time.sleep(1)
    raise RuntimeError('worker 60 秒内未就绪。')

def check_policies(state,bootstrap):
    worker=state['worker'];ready(worker)
    e=env(state['proxy']);host='127.0.0.1' if state['phase']!='ACCEPTING' else e.get('GATEWAY_BIND_ADDRESS','127.0.0.1')
    if host in ('0.0.0.0','::'):host='127.0.0.1'
    for attempt in range(60):
        try:
            with socket.create_connection((host,int(e.get('GATEWAY_PORT','3307'))),timeout=1):break
        except OSError:
            if attempt==59:raise RuntimeError('Proxy 60 秒内未监听。')
            time.sleep(1)
    rows=request(worker,'/api/logical-databases')
    if any(r['status']!='ACTIVE' for r in rows):raise RuntimeError('存在未完成接入的逻辑库，先核实处理，禁止自动跳过。')
    # Verify every database before bootstrapping any. Never publish an unverified draft.
    for r in rows:request(worker,'/api/upgrade/policies/verify',{'database':r['logicalName']})
    if bootstrap:
        for r in rows:request(worker,'/api/upgrade/policies/bootstrap',{'database':r['logicalName']})
    print('策略一致性校验通过，逻辑库数量：'+str(len(rows)))

def validate(info,expected):
    if info['HostConfig']['NetworkMode']!='host':raise RuntimeError('当前升级工具只支持 host 网络部署，拒绝猜测端口映射。')
    if info['HostConfig'].get('Privileged'):raise RuntimeError('不支持自动迁移特权容器。')
    mounts=info.get('Mounts',[])
    destinations={m['Destination'] for m in mounts}
    inherited={'/opt/gateway/conf','/opt/gateway/logs'} if set(expected)=={'/opt/gateway-admin/data'} else set()
    if set(expected)=={'/opt/gateway-platform/data'}:inherited={'/opt/gateway/conf','/opt/gateway/logs','/opt/gateway-admin/data'}
    if not set(expected).issubset(destinations) or destinations-set(expected)-inherited:raise RuntimeError('容器挂载布局不符合已验证版本，拒绝自动重建。')
    for m in mounts:
        path=m['Source']
        local_volume=m['Destination'] in inherited and m['Type']=='volume' and m.get('Driver')=='local' and bool(m.get('Name'))
        if (m['Type']!='bind' and not local_volume) or not os.path.isdir(path) or os.path.realpath(path)!=path or path.count('/')<3:
            raise RuntimeError('数据目录必须是明确的专用绝对目录，且不能是符号链接或卷。')
    if not info['State']['Running']:raise RuntimeError('升级前 worker 和 Proxy 必须运行，请先核实原服务。')

def run_container(state,kind,staged):
    old=state[kind];name=old['Name'].lstrip('/');e=env(old)
    if staged:
        if kind=='worker':e.update(ADMIN_BIND_ADDRESS='127.0.0.1',PROXY_HOST='127.0.0.1')
        else:e['GATEWAY_BIND_ADDRESS']='127.0.0.1'
    path=os.path.join(state['backup'],kind+('-staged' if staged else '-accepted')+'.env')
    if any('\n' in k+v or '\r' in k+v for k,v in e.items()):raise RuntimeError('环境变量包含换行，不支持自动迁移。')
    with open(path,'w') as f:f.write('\n'.join(k+'='+v for k,v in e.items())+'\n')
    os.chmod(path,0o600)
    args=['run','-d','--name',name,'--net','host','--label','gateway.upgrade-id='+state['id'],'--env-file',path]
    restart=old['HostConfig'].get('RestartPolicy',{}).get('Name','no')
    if restart not in ('no','always','unless-stopped'):raise RuntimeError('该重启策略尚未验证。')
    args+=['--restart','no' if staged else restart]
    # Preserve the resource budget when replacing the runtime, including performance upgrades.
    for key,flag in (('Memory','--memory'),('MemorySwap','--memory-swap'),('MemoryReservation','--memory-reservation'),
                     ('CpuShares','--cpu-shares'),('CpuPeriod','--cpu-period'),('CpuQuota','--cpu-quota'),
                     ('CpusetCpus','--cpuset-cpus'),('CpusetMems','--cpuset-mems'),('PidsLimit','--pids-limit')):
        if old['HostConfig'].get(key):args += [flag,str(old['HostConfig'][key])]
    if old['HostConfig'].get('NanoCpus'):args += ['--cpus',str(old['HostConfig']['NanoCpus']/1000000000)]
    for limit in old['HostConfig'].get('Ulimits') or []:
        args += ['--ulimit',limit['Name']+'='+str(limit['Soft'])+':'+str(limit['Hard'])]
    hostname=socket.gethostname()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,252}',hostname):raise RuntimeError('hostname 格式异常，停止升级。')
    hosts=old['HostConfig'].get('ExtraHosts') or []
    for host in hosts:args+=['--add-host',host]
    if not any(x.startswith(hostname+':') for x in hosts):args+=['--add-host',hostname+':127.0.0.1']
    for m in old['Mounts']:args+=['-v',(m['Name'] if m['Type']=='volume' else m['Source'])+':'+m['Destination']+(':'+m['Mode'] if m.get('Mode') else '')]
    args.append(state[kind+'_image']);docker(*args)

def restore(state,path):
    if state['phase'] in ('ACCEPTING','ACCEPTED'):raise RuntimeError('已经进入开放业务阶段，禁止自动回退冷备，避免丢失后续配置；须另行核实。')
    if state['phase']=='ROLLED_BACK':print('已回退，无需重复操作。');return
    for kind in ('proxy','worker'):
        old=state[kind];name=old['Name'].lstrip('/');previous=name+'-pre-'+state['id']
        if exists(name) and (inspect(name).get('Config',{}).get('Labels') or {}).get('gateway.upgrade-id')==state['id']:
            docker('stop',name);docker('rename',name,name+'-failed-'+state['id'])
        if exists(previous):
            if exists(name):raise RuntimeError('容器名被其他实例占用，拒绝覆盖。')
            docker('rename',previous,name)
    if state.get('cold_backup_complete'):
        for item in state['directories']:
            source=item['source'];failed=source+'.upgrade-failed-'+state['id']
            if not os.path.exists(failed):os.rename(source,failed)
            if not os.path.exists(source):
                # Never expose a half-copied database as the restored directory. A failed
                # copy is retained under a unique name and a retry starts a fresh copy.
                temporary=source+'.restore-copy-'+uuid.uuid4().hex[:12]
                shutil.copytree(item['backup'],temporary,symlinks=True)
                os.rename(temporary,source)
    for kind in ('worker','proxy'):
        name=state[kind]['Name'].lstrip('/')
        if state[kind]['State']['Running']:docker('start',name)
    if state.get('platform') and state['platform']['State']['Running']:docker('start',state['platform']['Name'].lstrip('/'))
    state['phase']='ROLLED_BACK';save(state,path);print('已恢复原容器和冷备。失败现场及备份均保留，未删除。')

def stage(args):
    worker=inspect(args.worker);proxy=inspect(args.proxy)
    validate(worker,{'/opt/gateway-admin/data'});validate(proxy,{'/opt/gateway/conf','/opt/gateway/logs'})
    if args.worker==args.proxy or args.platform in (args.worker,args.proxy):raise RuntimeError('容器名称必须不同。')
    if env(proxy).get('GATEWAY_MULTI_USER_ENABLED')!='true':raise RuntimeError('此升级工具要求现有多用户部署。')
    if env(worker).get('ADMIN_BIND_ADDRESS','127.0.0.1')!='127.0.0.1':raise RuntimeError('worker 必须只监听 127.0.0.1，防止维护期间外部写入管理库。')
    if not args.platform and not args.no_platform:raise RuntimeError('必须指定平台容器，或明确此实例没有平台。')
    root=os.path.realpath(args.backup_root)
    if not os.path.isdir(root) or root.count('/')<2:raise RuntimeError('备份根目录必须预先创建为专用目录。')
    platform=inspect(args.platform) if args.platform else None
    if args.platform_image:
        if not platform:raise RuntimeError('升级平台镜像必须指定 --platform。')
        validate(platform,{'/opt/gateway-platform/data'})
        inspect(args.platform_image)
    paths=[m['Source'] for i in (worker,proxy) for m in i['Mounts']]
    if args.platform_image:paths += [m['Source'] for m in platform['Mounts']]
    for source in paths:
        if os.path.commonpath([root,source]) in (root,source):raise RuntimeError('备份目录与运行目录不能互相包含。')
    for i,source in enumerate(paths):
        if any(os.path.commonpath([source,other]) in (source,other) for other in paths[i+1:]):raise RuntimeError('挂载目录重叠，拒绝自动备份。')
    needed=sum(os.path.getsize(os.path.join(d,n)) for p in paths for d,_,files in os.walk(p) for n in files if not os.path.islink(os.path.join(d,n)))
    if shutil.disk_usage(root).free<needed*2+128*1024*1024:raise RuntimeError('备份目录空间不足。')
    token=uuid.uuid4().hex[:12];backup=os.path.join(root,'upgrade-'+token);os.mkdir(backup,0o700)
    state={'id':token,'backup':backup,'phase':'PREPARED','worker':worker,'proxy':proxy,'platform':platform,
           'worker_image':inspect(args.worker_image)['Id'],'proxy_image':inspect(args.proxy_image)['Id'],
           'directories':[{'source':p,'backup':os.path.join(backup,'data-'+str(i))} for i,p in enumerate(paths)]}
    if args.platform_image:state['platform_image']=inspect(args.platform_image)['Id']
    path=os.path.join(backup,'state.json');save(state,path);print('升级状态文件：'+path,flush=True)
    try:
        if state['platform']:docker('stop',args.platform)
        docker('stop',args.proxy);docker('stop',args.worker)
        for item in state['directories']:shutil.copytree(item['source'],item['backup'],symlinks=True)
        state['cold_backup_complete']=True;save(state,path)
        for kind in ('worker','proxy'):
            name=state[kind]['Name'].lstrip('/');docker('rename',name,name+'-pre-'+token)
        run_container(state,'worker',True);run_container(state,'proxy',True)
        check_policies(state,True);state['phase']='STAGED';save(state,path)
        print('隔离升级就绪：业务入口仅本机可达，平台保持停止。完成业务抽查后才能 accept；否则 rollback。')
    except Exception:
        restore(state,path);raise

def main():
    os.umask(0o077)
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='action')
    s=sub.add_parser('stage')
    for name in ('worker','proxy','worker-image','proxy-image','backup-root'):s.add_argument('--'+name,required=True)
    s.add_argument('--platform');s.add_argument('--no-platform',action='store_true');s.add_argument('--maintenance-confirmed',action='store_true')
    s.add_argument('--platform-image',help='同时冷备平台数据，并在 accept 时保留原挂载和凭据升级平台。')
    for name in ('accept','rollback'):
        a=sub.add_parser(name);a.add_argument('--state',required=True);a.add_argument('--business-verified',action='store_true')
        if name=='accept':a.add_argument('--automatic-health-check',action='store_true',help='自动健康检查后开放业务，不代表已执行真实业务验收。')
    args=p.parse_args()
    if args.action=='stage':
        if not args.maintenance_confirmed:raise RuntimeError('必须明确安排维护窗口并指定 --maintenance-confirmed；会停止业务连接。')
        stage(args);return
    if args.action not in ('accept','rollback'):p.error('请选择 stage / accept / rollback')
    path=os.path.realpath(args.state)
    with open(path) as f:state=json.load(f)
    if args.action=='rollback':restore(state,path);return
    if state['phase']!='STAGED' or not (args.business_verified or args.automatic_health_check):raise RuntimeError('必须指定业务验收或自动健康检查模式。')
    if args.automatic_health_check:print('自动更新：按策略和服务健康检查开放业务，未执行真实业务 SQL 验收。')
    check_policies(state,False);state['phase']='ACCEPTING';save(state,path)
    for kind in ('proxy','worker'):
        name=state[kind]['Name'].lstrip('/');docker('stop',name);docker('rename',name,name+'-verified-'+state['id'])
    run_container(state,'worker',False);ready(state['worker']);run_container(state,'proxy',False)
    check_policies(state,False)
    if state.get('platform_image'):
        name=state['platform']['Name'].lstrip('/')
        docker('rename',name,name+'-pre-'+state['id'])
        run_container(state,'platform',False)
        e=env(state['platform']);host=e.get('PLATFORM_BIND_ADDRESS','127.0.0.1')
        if host in ('0.0.0.0','::'):host='127.0.0.1'
        for attempt in range(60):
            try:
                with urllib.request.urlopen('http://'+host+':'+e.get('PLATFORM_PORT','18080')+'/login.html',timeout=2) as response:
                    if response.status==200:break
            except Exception:pass
            if attempt==59:raise RuntimeError('平台升级后未就绪；保持 ACCEPTING，禁止自动覆盖冷备，请核实平台日志。')
            time.sleep(1)
    elif state.get('platform') and state['platform']['State']['Running']:docker('start',state['platform']['Name'].lstrip('/'))
    state['phase']='ACCEPTED';save(state,path);print('已按原监听配置开放业务，旧容器及冷备保留。')

if __name__=='__main__':
    try:main()
    except Exception as e:print('停止：'+str(e),file=sys.stderr);sys.exit(1)
