"""Create disposable local integration fixture using the real monitoring config generator."""
import sys,pathlib,json,types,configparser
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'packaging/monitoring'))
import install
root=pathlib.Path(sys.argv[1]).resolve();platform_port,grafana_port,prom_port=map(int,sys.argv[2:])
install.config(root,19002,18092,'127.0.0.1')
for directory in ('data/grafana','logs'):(root/directory).mkdir(parents=True,exist_ok=True)
install.write(root/'manifest.json',json.dumps({'root':str(root),'worker_port':19002,'proxy_port':18092,'grafana_bind':'127.0.0.1'}))
install.integrate(types.SimpleNamespace(monitor_dir=str(root),platform_url='http://127.0.0.1:'+str(platform_port)))
config=configparser.ConfigParser(interpolation=None);config.read(str(root/'grafana.ini'));config['server']['http_port']=str(grafana_port)
with (root/'grafana.ini').open('w') as output:config.write(output)
source=root/'provisioning/datasources/gateway.yaml';value=json.loads(source.read_text());value['datasources'][0]['url']='http://127.0.0.1:'+str(prom_port);source.write_text(json.dumps(value))
