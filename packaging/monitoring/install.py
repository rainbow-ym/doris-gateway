#!/usr/bin/env python3
"""Dedicated Linux/amd64 monitoring services, outside gateway data directories."""
import argparse, hashlib, ipaddress, json, os, pathlib, secrets, shutil, socket, subprocess, sys, tarfile, time, urllib.request
BASE = pathlib.Path(__file__).resolve().parent

def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)

def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')
    os.chmod(str(path), 0o600)

def extract(archive, destination):
    destination.mkdir()
    with tarfile.open(str(archive), 'r:gz') as tar:
        for member in tar.getmembers():
            target = os.path.realpath(str(destination / member.name))
            if os.path.commonpath([str(destination.resolve()), target]) != str(destination.resolve()) or member.isdev():
                raise RuntimeError('Unsafe archive member')
            if member.issym() or member.islnk():
                linked = os.path.realpath(str((destination / member.name).parent / member.linkname) if member.issym() else str(destination / member.linkname))
                if os.path.commonpath([str(destination.resolve()), linked]) != str(destination.resolve()):
                    raise RuntimeError('Unsafe archive link')
        tar.extractall(str(destination))

def config(root, worker_port, proxy_port, grafana_bind):
    password, admin = secrets.token_urlsafe(36), secrets.token_urlsafe(24)
    write(root / 'metrics.env', 'METRICS_PASSWORD=' + password + '\nPROXY_METRICS_PORT=' + str(proxy_port) + '\n')
    write(root / 'metrics.password', password)
    write(root / 'login.txt', 'Grafana username: admin\nGrafana password: ' + admin + '\n')
    auth = {'username': 'gateway_metrics', 'password_file': str(root / 'metrics.password')}
    jobs = [
        {'job_name': 'gateway-worker', 'metrics_path': '/api/observability/metrics', 'basic_auth': auth, 'static_configs': [{'targets': ['127.0.0.1:' + str(worker_port)]}]},
        {'job_name': 'gateway-proxy', 'basic_auth': auth, 'static_configs': [{'targets': ['127.0.0.1:' + str(proxy_port)]}]},
        {'job_name': 'gateway-host', 'static_configs': [{'targets': ['127.0.0.1:19100']}]}]
    write(root / 'prometheus.json', json.dumps({'global': {'scrape_interval': '15s', 'evaluation_interval': '15s'}, 'rule_files': [str(root / 'rules.json')], 'scrape_configs': jobs}))
    for name in ('rules.json', 'dashboard.json'):
        shutil.copyfile(str(BASE / name), str(root / name))
    write(root / 'provisioning/datasources/gateway.yaml', json.dumps({'apiVersion': 1, 'datasources': [{'name': 'Gateway Prometheus', 'uid': 'gateway-prometheus', 'type': 'prometheus', 'access': 'proxy', 'url': 'http://127.0.0.1:19090', 'isDefault': True, 'editable': False}]}))
    write(root / 'provisioning/dashboards/gateway.yaml', json.dumps({'apiVersion': 1, 'providers': [{'name': 'gateway', 'type': 'file', 'options': {'path': str(root / 'dashboard.json')}}]}))
    write(root / 'grafana.ini', '[server]\nhttp_addr = ' + grafana_bind + '\nhttp_port = 13000\n[paths]\ndata = ' + str(root / 'data/grafana') + '\nlogs = ' + str(root / 'logs') + '\nprovisioning = ' + str(root / 'provisioning') + '\n[security]\nadmin_password = ' + admin + '\n[analytics]\nreporting_enabled = false\ncheck_for_updates = false\ncheck_for_plugin_updates = false\n[users]\nallow_sign_up = false\n[plugins]\npreinstall_disabled = true\n[auth.anonymous]\nenabled = false\n')

def prepare(args):
    root, install = pathlib.Path(args.monitor_dir).resolve(), pathlib.Path(args.install_dir).resolve()
    if root.exists():
        raise RuntimeError('Monitoring directory exists; refusing to overwrite credentials/data')
    if any(c.isspace() or c == '%' for c in str(root)) or len(root.parts) < 3 or os.path.commonpath([str(root), str(install)]) in (str(root), str(install)):
        raise RuntimeError('Use a dedicated monitoring directory outside gateway installation')
    ipaddress.IPv4Address(args.grafana_bind)
    info = json.loads(subprocess.check_output(['docker', 'inspect', args.prefix + '-worker']).decode())[0]
    env = dict(value.split('=', 1) for value in info['Config']['Env'])
    worker_port = int(env.get('ADMIN_PORT', '18080'))
    for port in (args.proxy_port, 19090, 19100, 13000):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', port))
    if len({worker_port, args.proxy_port, 19090, 19100, 13000}) != 5:
        raise RuntimeError('Monitoring ports overlap')
    sources = json.loads((BASE / 'vendor/sources.json').read_text())
    for name, url, digest in sources:
        if hashlib.sha256((BASE / 'vendor' / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError('Vendor checksum mismatch: ' + name)
    root.mkdir(mode=0o700, parents=True)
    for name, url, digest in sources:
        extract(BASE / 'vendor' / name, root / name.split('-')[0].split('_')[0])
    config(root, worker_port, args.proxy_port, args.grafana_bind)
    prometheus = next(path for path in (root / 'prometheus').rglob('prometheus') if path.is_file())
    promtool = next(path for path in (root / 'prometheus').rglob('promtool') if path.is_file())
    node = next(path for path in (root / 'node').rglob('node_exporter') if path.is_file())
    grafana = next(path for path in (root / 'grafana').rglob('grafana') if path.is_file() and path.parent.name == 'bin')
    run(str(promtool), 'check', 'config', str(root / 'prometheus.json'))
    commands = {
        'prometheus': str(prometheus) + ' --config.file=' + str(root / 'prometheus.json') + ' --storage.tsdb.path=' + str(root / 'data/prometheus') + ' --storage.tsdb.retention.time=30d --storage.tsdb.retention.size=10GB --web.listen-address=127.0.0.1:19090',
        'node': str(node) + ' --web.listen-address=127.0.0.1:19100',
        'grafana': str(grafana) + ' server --homepath=' + str(grafana.parent.parent) + ' --config=' + str(root / 'grafana.ini')}
    for name, command in commands.items():
        write(root / (name + '.service'), '[Unit]\nDescription=Gateway ' + name + ' monitoring\nAfter=network.target\n[Service]\nUser=gateway-monitor\nGroup=gateway-monitor\nExecStart=' + command + '\nRestart=on-failure\nNoNewPrivileges=true\nProtectSystem=strict\nReadWritePaths=' + str(root) + '\n[Install]\nWantedBy=multi-user.target\n')
    for directory in ('data/prometheus', 'data/grafana', 'logs'):
        (root / directory).mkdir(parents=True, exist_ok=True)
    write(root / 'manifest.json', json.dumps({'schema': 1, 'root': str(root), 'worker_port': worker_port, 'proxy_port': args.proxy_port, 'grafana_bind': args.grafana_bind}))
    print('Monitoring prepared. Credentials remain in ' + str(root / 'login.txt'))

def start(args):
    import pwd
    root = pathlib.Path(args.monitor_dir).resolve()
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest['root'] != str(root):
        raise RuntimeError('Monitoring directory differs from prepared path')
    for name in ('prometheus', 'node', 'grafana'):
        target = pathlib.Path('/etc/systemd/system/gateway-' + name + '.service')
        if target.exists() and target.read_bytes() != (root / (name + '.service')).read_bytes():
            raise RuntimeError('Different existing monitoring service; refusing overwrite')
    try:
        account = pwd.getpwnam('gateway-monitor')
    except KeyError:
        run('useradd', '--system', '--no-create-home', '--shell', '/sbin/nologin', 'gateway-monitor')
        account = pwd.getpwnam('gateway-monitor')
    for path in [root] + list(root.rglob('*')):
        if not path.is_symlink():
            os.chown(str(path), account.pw_uid, account.pw_gid)
    for name in ('prometheus', 'node', 'grafana'):
        target = pathlib.Path('/etc/systemd/system/gateway-' + name + '.service')
        if target.exists() and target.read_bytes() != (root / (name + '.service')).read_bytes():
            raise RuntimeError('Different existing monitoring service; refusing overwrite')
        shutil.copyfile(str(root / (name + '.service')), str(target))
        os.chmod(str(target), 0o644)
    run('systemctl', 'daemon-reload')
    for name in ('prometheus', 'node', 'grafana'):
        run('systemctl', 'enable', '--now', 'gateway-' + name + '.service')
    for attempt in range(30):
        try:
            with urllib.request.urlopen('http://127.0.0.1:19090/-/ready', timeout=2):
                pass
            with urllib.request.urlopen('http://' + ('127.0.0.1' if manifest['grafana_bind']=='0.0.0.0' else manifest['grafana_bind']) + ':13000/api/health', timeout=2) as response:
                if json.load(response).get('database') != 'ok':
                    raise RuntimeError('Grafana database not ready')
            with urllib.request.urlopen('http://127.0.0.1:19090/api/v1/targets', timeout=2) as response:
                targets = json.load(response)['data']['activeTargets']
                if len(targets) != 3 or any(target['health'] != 'up' for target in targets):
                    raise RuntimeError('Scrape targets not ready; run a gateway query to initialize Proxy metrics')
            print('Monitoring ready: Worker, Proxy and host targets are up.')
            return
        except Exception:
            time.sleep(2)
    raise RuntimeError('Monitoring health checks failed; gateway data was not modified')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['prepare', 'start'])
    parser.add_argument('--monitor-dir', required=True)
    parser.add_argument('--install-dir')
    parser.add_argument('--prefix', default='doris-gateway')
    parser.add_argument('--proxy-port', type=int, default=18092)
    parser.add_argument('--grafana-bind', default='127.0.0.1')
    args = parser.parse_args()
    if sys.platform != 'linux' or os.geteuid() != 0 or os.uname().machine != 'x86_64':
        raise RuntimeError('Requires root on Linux x86_64 with systemd')
    if not pathlib.Path('/run/systemd/system').exists():
        raise RuntimeError('Requires systemd')
    os.umask(0o077)
    if args.action == 'prepare':
        prepare(args)
    else:
        start(args)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Monitoring setup stopped: ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
