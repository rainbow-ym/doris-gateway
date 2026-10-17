"""Run bounded build/test commands on the authorized test host; credentials stay in environment."""
import argparse
import os
import sys
import paramiko

parser = argparse.ArgumentParser()
parser.add_argument("--upload", nargs=2, action="append", default=[])
parser.add_argument("--command", required=True)
args = parser.parse_args()
client = paramiko.SSHClient()
client.load_system_host_keys()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(os.environ.get("GATEWAY_TEST_HOST", "192.168.232.132"),
               username=os.environ.get("GATEWAY_TEST_USER", "root"),
               password=os.environ["GATEWAY_TEST_SSH_PASSWORD"], timeout=15)
try:
    with client.open_sftp() as sftp:
        for local, remote in args.upload:
            sftp.put(local, remote)
    _, stdout, stderr = client.exec_command(args.command, timeout=900)
    sys.stdout.buffer.write(stdout.read())
    sys.stderr.buffer.write(stderr.read())
    sys.exit(stdout.channel.recv_exit_status())
finally:
    client.close()
