"""Mocked orchestration tests; never calls Docker or changes a deployment."""
import argparse, importlib.util, json, pathlib, tempfile, unittest
from unittest.mock import patch

path=pathlib.Path(__file__).resolve().parents[1]/'packaging'/'clean-install'/'one-click-update.py'
spec=importlib.util.spec_from_file_location('oneclick',path)
oneclick=importlib.util.module_from_spec(spec);spec.loader.exec_module(oneclick)

class OneClickTest(unittest.TestCase):
    def scenario(self,failure=None,offline=False,mismatch=False):
        with tempfile.TemporaryDirectory() as temp:
            base=pathlib.Path(temp);install=base/'install';install.mkdir()
            args=argparse.Namespace(install_dir=str(install),backup_dir=str(base/'backups'),prefix='doris-gateway')
            if offline:
                manifest={'archive':'gateway-images.tar.gz'}
                for kind,repo in [('worker','doris-security-gateway-admin'),('proxy','doris-security-gateway'),('platform','doris-security-gateway-platform')]:
                    manifest[kind]=repo+':obs-test';manifest[kind+'Id']='sha256:test'
                (base/'offline-images.json').write_text(json.dumps(manifest))
            calls=[];statefile=[]
            def run(*command):
                calls.append(command)
                if 'stage' in command:
                    folder=pathlib.Path(command[command.index('--backup-root')+1])/'upgrade-test'
                    folder.mkdir();statefile.append(folder/'state.json')
                    statefile[0].write_text(json.dumps({'phase':'STAGED'}))
                if 'accept' in command:
                    statefile[0].write_text(json.dumps({'phase':failure or 'ACCEPTED'}))
                    if failure:raise RuntimeError('simulated activation failure')
            with patch.object(oneclick,'run',side_effect=run),patch.object(oneclick,'validate_install'),patch.object(oneclick,'inspect',return_value={'Id':'wrong' if mismatch else 'sha256:test'}),patch.object(oneclick,'wait_healthy') as health:
                if failure or mismatch:
                    with self.assertRaises(RuntimeError):oneclick.execute(args,base)
                    health.assert_not_called()
                else:
                    oneclick.execute(args,base);health.assert_called_once_with('doris-gateway')
            return calls
    def test_prebuilt_package_updates_all_three_images_without_build(self):
        calls=self.scenario(offline=True)
        self.assertEqual(('docker','load','-i'),calls[1][:3])
        stage=next(c for c in calls if 'stage' in c)
        self.assertIn('--platform-image',stage)
        self.assertFalse(any('bash'==c[0] for c in calls))
    def test_prebuilt_image_mismatch_stops_before_stopping_services(self):
        calls=self.scenario(offline=True,mismatch=True)
        self.assertFalse(any('stage' in c for c in calls))
    def test_success_automates_build_backup_and_activation_without_claiming_business_test(self):
        calls=self.scenario()
        self.assertEqual('sha256sum',calls[0][0]);self.assertEqual('bash',calls[1][0])
        self.assertIn('stage',calls[2]);self.assertIn('--maintenance-confirmed',calls[2])
        self.assertIn('--automatic-health-check',calls[3])
        self.assertFalse(any('--business-verified' in call for call in calls))
    def test_failure_before_opening_rolls_back(self):
        calls=self.scenario('STAGED');self.assertIn('rollback',calls[-1])
    def test_failure_after_opening_never_restores_stale_data(self):
        calls=self.scenario('ACCEPTING');self.assertFalse(any('rollback' in c for c in calls))
    def test_wrong_install_directory_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)/'install';root.mkdir()
            outside=pathlib.Path(temp)/'other';outside.mkdir()
            info={'State':{'Running':True},'Mounts':[{'Type':'bind','Destination':'/opt/gateway-admin/data','Source':str(outside)}]}
            with patch.object(oneclick,'inspect',return_value=info):
                with self.assertRaises(RuntimeError):oneclick.validate_install(str(root),'doris-gateway')
    def test_nested_backup_is_rejected_before_any_command(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            args=argparse.Namespace(install_dir=str(root),backup_dir=str(root/'backup'),prefix='doris-gateway')
            with patch.object(oneclick,'run') as run:
                with self.assertRaises(RuntimeError):oneclick.execute(args,root)
                run.assert_not_called()

if __name__=='__main__':unittest.main()
