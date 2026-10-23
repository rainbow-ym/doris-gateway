#!/usr/bin/env python3
"""One command: verify, offline build, cold backup, stage, activate, health check."""
import argparse, json, os, pathlib, re, subprocess, sys, time, uuid

def run(*args):
    subprocess.run(list(args),check=True)

def inspect(name):
    result=subprocess.run(['docker','inspect',name],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True)
    return json.loads(result.stdout)[0]

def validate_install(root,prefix):
    expected={'worker':['/opt/gateway-admin/data'],'proxy':['/opt/gateway/conf','/opt/gateway/logs'],
              'platform':['/opt/gateway-platform/data']}
    for kind,targets in expected.items():
        info=inspect(prefix+'-'+kind)
        if not info['State']['Running']:raise RuntimeError('升级前容器必须正在运行：'+prefix+'-'+kind)
        for target in targets:
            mounts=[m for m in info['Mounts'] if m['Destination']==target]
            if len(mounts)!=1 or mounts[0]['Type']!='bind':
                raise RuntimeError('核心数据需要使用原安装目录的绑定挂载：'+kind)
            source=os.path.realpath(mounts[0]['Source'])
            if not os.path.isdir(source) or source==root or os.path.commonpath([root,source])!=root:
                raise RuntimeError('容器实际数据不在指定安装目录内，停止：'+kind)

def wait_healthy(prefix):
    for attempt in range(90):
        states=[inspect(prefix+'-'+k)['State'] for k in ('worker','proxy','platform')]
        if all(s.get('Running') and s.get('Health',{}).get('Status')=='healthy' for s in states):return
        time.sleep(2)
    raise RuntimeError('更新后 180 秒内未全部健康；请检查服务日志，不能认定成功')

def execute(args,base):
    root=os.path.realpath(args.install_dir);backup=os.path.realpath(args.backup_dir)
    if not os.path.isdir(root) or root.count(os.sep)<2:raise RuntimeError('安装目录不存在或不是专用目录')
    if not re.fullmatch(r'[a-z][a-z0-9-]{0,45}',args.prefix):raise RuntimeError('容器名前缀格式无效')
    if os.path.commonpath([root,backup]) in (root,backup):raise RuntimeError('备份目录必须在安装目录之外，不能互相包含')
    run('sha256sum','-c',str(base/'SHA256SUMS'))
    validate_install(root,args.prefix)
    os.makedirs(backup,mode=0o700,exist_ok=True)
    session=pathlib.Path(backup)/('one-click-'+uuid.uuid4().hex[:12]);session.mkdir(mode=0o700)
    tag='sm4-perf-'+session.name
    worker=args.prefix+'-worker';proxy=args.prefix+'-proxy';platform=args.prefix+'-platform'
    print('将自动冷备并重启三个服务，期间查询会中断。原配置、密钥和数据保留。',flush=True)
    print('安装目录：'+root+'\n本次备份目录：'+str(session),flush=True)
    helper=str(base/'safe-upgrade.py')
    statepath=None
    try:
        manifest=base/'offline-images.json'
        if manifest.exists():
            with manifest.open() as f: images=json.load(f)
            archive=images['archive']
            if not re.fullmatch(r'[a-zA-Z0-9_.-]+',archive):raise RuntimeError('离线镜像文件名无效')
            for kind,repository in [('worker','doris-security-gateway-admin'),('proxy','doris-security-gateway'),('platform','doris-security-gateway-platform')]:
                if not re.fullmatch(re.escape(repository)+r':[a-z0-9][a-z0-9.-]{0,90}',images[kind]):raise RuntimeError('镜像名称无效')
            run('docker','load','-i',str(base/archive))
            for kind in ('worker','proxy','platform'):
                if inspect(images[kind])['Id']!=images[kind+'Id']:raise RuntimeError('镜像 ID 与清单不一致：'+kind)
            image_args=['--worker-image',images['worker'],'--proxy-image',images['proxy'],'--platform-image',images['platform']]
        else:
            run('bash',str(base/'build-sm4-performance-images.sh'),worker,proxy,tag,platform)
            image_args=['--worker-image','doris-security-gateway-admin:'+tag,'--proxy-image','doris-security-gateway:'+tag]
            if (base/'gateway-platform-0.1.0.jar').is_file():
                image_args+=['--platform-image','doris-security-gateway-platform:'+tag]
        run(sys.executable,helper,'stage','--worker',worker,'--proxy',proxy,'--platform',platform,
            *image_args,*(['--metrics-env',os.path.abspath(args.metrics_env)] if getattr(args,'metrics_env',None) else []),'--backup-root',str(session),'--maintenance-confirmed')
        states=list(session.glob('upgrade-*/state.json'))
        if len(states)!=1:raise RuntimeError('无法唯一确认本次备份状态；停止，不猜测恢复路径')
        statepath=states[0]
        run(sys.executable,helper,'accept','--state',str(statepath),'--automatic-health-check')
        wait_healthy(args.prefix)
        print('更新完成，三个服务健康。请进行业务查询和 SM4 导出测试。')
        print('备份和恢复状态：'+str(statepath))
    except Exception:
        states=list(session.glob('upgrade-*/state.json'))
        if len(states)==1:
            statepath=states[0]
            with statepath.open() as f:state=json.load(f)
            if state['phase']=='STAGED':
                run(sys.executable,helper,'rollback','--state',str(statepath))
            elif state['phase'] in ('ACCEPTING','ACCEPTED'):
                print('已经进入开放业务阶段，不自动覆盖旧数据，避免丢失新写入。',file=sys.stderr)
            print('恢复信息：'+str(statepath),file=sys.stderr)
        print('更新未完成，备份保留：'+str(session),file=sys.stderr)
        raise

def main():
    parser=argparse.ArgumentParser(description='一键离线更新：会中断查询、冷备、重启并自动恢复业务入口。')
    parser.add_argument('--install-dir',required=True,help='已有安装目录，例如 /data/doris-gateway/install_dir')
    parser.add_argument('--backup-dir',required=True,help='安装目录外的备份目录，可自动创建')
    parser.add_argument('--metrics-env',help='监控安装工具生成的独立采集凭据文件')
    parser.add_argument('--prefix',default='doris-gateway',help='容器名前缀，默认 doris-gateway')
    args=parser.parse_args();os.umask(0o077)
    if not os.path.isabs(args.install_dir) or not os.path.isabs(args.backup_dir):
        raise RuntimeError('安装目录和备份目录请使用完整绝对路径')
    base=pathlib.Path(__file__).resolve().parent;os.chdir(str(base))
    if not os.path.isdir(args.install_dir):raise RuntimeError('安装目录不存在')
    # Serialize updates to this deployment; a crashed process automatically releases the lock.
    import fcntl
    with open(os.path.join(args.install_dir,'.sm4-update.lock'),'a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        execute(args,base)

if __name__=='__main__':
    try:main()
    except Exception as error:
        print('停止：'+(str(error) if isinstance(error,RuntimeError) else type(error).__name__+'；请检查上述步骤及备份记录'),file=sys.stderr)
        sys.exit(1)
