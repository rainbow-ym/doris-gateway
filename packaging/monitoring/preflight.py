"""Validate existing monitor ownership/ports before any gateway upgrade."""
import argparse,json,pathlib,subprocess
import install
parser=argparse.ArgumentParser()
parser.add_argument('--monitor-dir',required=True);parser.add_argument('--platform-url',required=True);parser.add_argument('--prefix',required=True)
args=parser.parse_args();install.platform_url(args.platform_url)
root=pathlib.Path(args.monitor_dir).resolve()
if root.exists():
    manifest=json.loads((root/'manifest.json').read_text())
    if manifest['root']!=str(root):raise RuntimeError('Monitoring path does not match manifest')
    worker=json.loads(subprocess.check_output(['docker','inspect',args.prefix+'-worker']).decode())[0]
    env=dict(item.split('=',1) for item in worker['Config']['Env'] if '=' in item)
    if int(env.get('ADMIN_PORT','18080'))!=manifest['worker_port']:raise RuntimeError('Monitoring belongs to another Worker port')
    if not (root/'metrics.env').is_file():raise RuntimeError('Existing metrics credentials missing; do not regenerate')
    print('Existing monitoring configuration will be reused; credentials and history retained.')
