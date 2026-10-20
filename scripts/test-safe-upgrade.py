"""Offline safety tests; no Docker or production data access."""
import importlib.util, pathlib, tempfile, unittest
from unittest.mock import patch

path=pathlib.Path(__file__).resolve().parents[1]/'packaging'/'clean-install'/'safe-upgrade.py'
spec=importlib.util.spec_from_file_location('upgrade',path)
upgrade=importlib.util.module_from_spec(spec);spec.loader.exec_module(upgrade)

class RestoreTest(unittest.TestCase):
    def test_replacement_preserves_cpu_memory_and_file_limits(self):
        with tempfile.TemporaryDirectory() as root:
            old={'Name':'/worker','Config':{'Env':['ADMIN_PORT=18082']},'Mounts':[],
                 'HostConfig':{'RestartPolicy':{'Name':'unless-stopped'},'Memory':4294967296,
                               'MemorySwap':4294967296,'NanoCpus':8000000000,'CpusetCpus':'0-15',
                               'PidsLimit':1024,'Ulimits':[{'Name':'nofile','Soft':65536,'Hard':65536}]}}
            state={'worker':old,'worker_image':'sha256:test','backup':root,'id':'test'}
            with patch.object(upgrade,'docker') as docker,patch.object(upgrade.socket,'gethostname',return_value='host'):
                upgrade.run_container(state,'worker',True)
            args=docker.call_args[0]
            for flag,value in (('--memory','4294967296'),('--memory-swap','4294967296'),
                               ('--cpus','8.0'),('--cpuset-cpus','0-15'),('--pids-limit','1024'),
                               ('--ulimit','nofile=65536:65536')):
                self.assertEqual(value,args[args.index(flag)+1])
            self.assertEqual(['ADMIN_PORT=18082'],old['Config']['Env'])
    def test_failed_copy_is_not_mistaken_for_restored_database(self):
        with tempfile.TemporaryDirectory() as root:
            root=pathlib.Path(root);source=root/'live';backup=root/'backup'
            source.mkdir();backup.mkdir();(source/'db').write_text('new');(backup/'db').write_text('old')
            state={'id':'test','phase':'STAGED','cold_backup_complete':True,
                   'directories':[{'source':str(source),'backup':str(backup)}],
                   'worker':{'Name':'/worker','State':{'Running':True}},
                   'proxy':{'Name':'/proxy','State':{'Running':True}}}
            realcopy=upgrade.shutil.copytree
            def broken(src,dst,**kw):
                pathlib.Path(dst).mkdir();(pathlib.Path(dst)/'partial').write_text('partial')
                raise OSError('simulated full disk')
            with patch.object(upgrade,'exists',return_value=False),patch.object(upgrade,'docker') as docker:
                with patch.object(upgrade.shutil,'copytree',side_effect=broken):
                    with self.assertRaises(OSError):upgrade.restore(state,str(root/'state.json'))
                docker.assert_not_called();self.assertFalse(source.exists())
                with patch.object(upgrade.shutil,'copytree',side_effect=realcopy):
                    upgrade.restore(state,str(root/'state.json'))
                self.assertEqual('old',(source/'db').read_text());self.assertFalse((source/'partial').exists())
                self.assertEqual('new',(root/'live.upgrade-failed-test'/'db').read_text())
                self.assertEqual('ROLLED_BACK',state['phase'])
                calls=docker.call_count;upgrade.restore(state,str(root/'state.json'));self.assertEqual(calls,docker.call_count)
    def test_after_accept_cannot_restore_old_data(self):
        with patch.object(upgrade,'docker') as docker:
            for phase in ('ACCEPTING','ACCEPTED'):
                with self.assertRaises(RuntimeError):upgrade.restore({'phase':phase},'unused')
            docker.assert_not_called()

if __name__=='__main__':unittest.main()
