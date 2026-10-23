"""Configuration/upgrade tests only. Never starts system services or connects to deployments."""
import importlib.util, io, json, pathlib, tarfile, tempfile, unittest, configparser, types
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
def load(name, relative):
    spec=importlib.util.spec_from_file_location(name, ROOT/relative)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
monitor=load('monitor', 'packaging/monitoring/install.py')
upgrade=load('upgrade', 'packaging/clean-install/safe-upgrade.py')

class MonitoringTest(unittest.TestCase):
    def test_integration_preserves_credentials_and_data_and_closes_public_listener(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary).resolve();monitor.config(root,19002,18092,'0.0.0.0')
            manifest={'root':str(root),'worker_port':19002,'proxy_port':18092,'grafana_bind':'0.0.0.0'}
            (root/'manifest.json').write_text(json.dumps(manifest));(root/'history-data').write_text('retain')
            secret=(root/'metrics.env').read_bytes();before=(root/'grafana.ini').read_bytes()
            monitor.integrate(types.SimpleNamespace(monitor_dir=str(root),platform_url='http://server:19000'))
            config=configparser.ConfigParser(interpolation=None);config.read(str(root/'grafana.ini'))
            self.assertEqual('127.0.0.1',config['server']['http_addr']);self.assertEqual('http://server:19000/history/',config['server']['root_url'])
            self.assertEqual('Viewer',config['users']['auto_assign_org_role']);self.assertEqual('false',config['auth.proxy']['enable_login_token'])
            self.assertEqual(secret,(root/'metrics.env').read_bytes());self.assertEqual('retain',(root/'history-data').read_text())
            self.assertEqual(before,next(root.glob('grafana.ini.before-integration-*')).read_bytes())
            self.assertEqual('/history',json.loads((root/'manifest.json').read_text())['grafana_path'])
    def test_diagnostics_identify_refused_proxy_without_logging_raw_errors(self):
        def fake(url):
            if url.endswith('/targets'):
                return json.dumps({'data':{'activeTargets':[{'labels':{'job':name},'health':'down' if name=='gateway-proxy' else 'up','lastError':'connection refused password=SECRET'} for name in ['gateway-worker','gateway-proxy','gateway-host']]}}).encode()
            return b'{"database":"ok"}'
        with patch.object(monitor.diagnostics,'fetch',side_effect=fake),patch.object(monitor.diagnostics.subprocess,'check_output',return_value=b'ActiveState=active\n'),patch.object(monitor.diagnostics.socket,'create_connection'):
            result=monitor.diagnostics.inspect({'worker_port':19002,'proxy_port':18092,'grafana_path':'/history'})
        self.assertFalse(result['ok']);self.assertNotIn('SECRET',json.dumps(result))
        self.assertEqual('CONNECTION_REFUSED',next(r for r in result['checks'] if r['check']=='scrape:gateway-proxy')['error'])
    def test_platform_origin_rejects_credentials_and_paths(self):
        for url in ['http://user:password@host','http://host/path','http://host?x=1','file:///etc/passwd','http://host/%0a']:
            with self.assertRaises(RuntimeError):monitor.platform_url(url)
    def test_independent_credentials_config_and_offline_dashboard(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            monitor.config(root,18082,18092,'127.0.0.1')
            values=upgrade.metrics_env(str(root/'metrics.env'))
            self.assertEqual('18092',values['PROXY_METRICS_PORT'])
            self.assertGreaterEqual(len(values['METRICS_PASSWORD']),32)
            self.assertNotIn(values['METRICS_PASSWORD'],(root/'login.txt').read_text())
            config=json.loads((root/'prometheus.json').read_text())
            self.assertEqual('127.0.0.1:18082',config['scrape_configs'][0]['static_configs'][0]['targets'][0])
            for job in config['scrape_configs'][:2]:
                self.assertEqual('gateway_metrics',job['basic_auth']['username'])
                self.assertIn('password_file',job['basic_auth'])
            self.assertIn('preinstall_disabled = true',(root/'grafana.ini').read_text())
            dashboard=json.loads((root/'dashboard.json').read_text(encoding='utf-8'))
            self.assertGreaterEqual(len(dashboard['panels']),25)
            self.assertTrue(all(p['datasource']['uid']=='gateway-prometheus' for p in dashboard['panels']))
    def test_metrics_override_rejects_business_config_and_weak_secret(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=pathlib.Path(temporary)/'metrics.env'
            for content in ['ADMIN_PASSWORD=bad\n','METRICS_PASSWORD=short\nPROXY_METRICS_PORT=18092\n','METRICS_PASSWORD='+'a'*32+'\nPROXY_METRICS_PORT=0\n']:
                path.write_text(content)
                with self.assertRaises(RuntimeError):upgrade.metrics_env(str(path))
    def test_archive_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary);archive=root/'bad.tar.gz'
            with tarfile.open(archive,'w:gz') as tar:
                item=tarfile.TarInfo('../escape');item.size=1;tar.addfile(item,io.BytesIO(b'x'))
            with self.assertRaises(RuntimeError):monitor.extract(archive,root/'out')
            self.assertFalse((root/'escape').exists())
    def test_dashboard_and_rules_do_not_mix_worker_and_proxy_success(self):
        dashboard=json.loads((ROOT/'packaging/monitoring/dashboard.json').read_text(encoding='utf-8'))
        for panel in dashboard['panels']:
            expression=panel['targets'][0]['expr']
            if 'gateway_export_duration_seconds_sum' in expression:
                self.assertIn('component="$component"',expression)
                self.assertIn('outcome="SUCCESS"',expression)
        rules=json.loads((ROOT/'packaging/monitoring/rules.json').read_text(encoding='utf-8'))
        self.assertTrue(any(r['alert']=='GatewayObservationLoss' for r in rules['groups'][0]['rules']))

if __name__=='__main__':unittest.main()
