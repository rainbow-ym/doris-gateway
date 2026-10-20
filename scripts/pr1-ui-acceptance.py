"""Tunnel the isolated platform for the real browser acceptance test; no secrets printed."""
import os, socketserver, select, threading, subprocess, paramiko, json, sys

ssh = paramiko.SSHClient()
ssh.load_system_host_keys()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.232.132', username='root', password=os.environ['GATEWAY_TEST_SSH_PASSWORD'], timeout=15)
def read_env(path):
    with ssh.open_sftp() as sftp:
        with sftp.open(path) as f:
            return dict(line.split('=',1) for line in f.read().decode().splitlines() if '=' in line and not line.startswith('#'))
production = '--production' in sys.argv
if production:
    def container_env(name):
        _,output,_=ssh.exec_command('docker inspect '+name)
        return dict(x.split('=',1) for x in json.loads(output.read())[0]['Config']['Env'] if '=' in x)
    platform=container_env('doris-security-gateway-platform')
    worker=container_env('doris-security-gateway-admin')
else:
    platform = read_env('/data/gateway-sm4-pr1-clean-test/platform.env')
    worker = read_env('/data/gateway-recovery-test-20261016/admin.env')
class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        channel = ssh.get_transport().open_channel('direct-tcpip', ('192.168.232.132',18080) if production else ('127.0.0.1',18090), self.request.getpeername())
        try:
            while True:
                readable,_,_ = select.select([channel,self.request],[],[],30)
                for source in readable:
                    data=source.recv(65536)
                    if not data:return
                    (self.request if source is channel else channel).sendall(data)
        finally:channel.close()
class Server(socketserver.ThreadingTCPServer):
    daemon_threads=True
with Server(('127.0.0.1',0),Handler) as server:
    threading.Thread(target=server.serve_forever,daemon=True).start()
    env=dict(os.environ, UI_TEST_URL='http://127.0.0.1:'+str(server.server_address[1]), UI_ADMIN_USER=platform['PLATFORM_ADMIN_USER'], UI_ADMIN_PASSWORD=platform['PLATFORM_ADMIN_PASSWORD'], UI_WORKER_USER=worker['ADMIN_USERNAME'], UI_WORKER_PASSWORD=worker['ADMIN_PASSWORD'])
    env['UI_PRODUCTION']='yes' if production else 'no'
    try:
        result=subprocess.run([os.environ['UI_NODE'], 'scripts/pr1-ui-acceptance.cjs'],env=env)
    finally:
        server.shutdown();ssh.close()
    raise SystemExit(result.returncode)
