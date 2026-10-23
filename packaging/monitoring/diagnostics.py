"""Read-only, numeric/status-only diagnostics. Never dumps environments or response bodies."""
import datetime, json, pathlib, socket, subprocess, urllib.error, urllib.request

def error_kind(error):
    value=str(error).lower()
    if isinstance(error,urllib.error.HTTPError):return 'HTTP_'+str(error.code)
    if 'refused' in value:return 'CONNECTION_REFUSED'
    if 'timed out' in value:return 'TIMEOUT'
    return type(error).__name__

def fetch(url):
    # Ignore server proxy environment for local health checks.
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(url,timeout=2) as response:
        data=response.read(1024*1024+1)
        if len(data)>1024*1024:raise ValueError('Oversize health response')
        return data

def inspect(manifest):
    rows=[]
    for name in ('prometheus','node','grafana'):
        try:
            output=subprocess.check_output(['systemctl','show','gateway-'+name,'--property=ActiveState,SubState,Result,ExecMainStatus'],stderr=subprocess.DEVNULL,timeout=5).decode()
            fields=dict(line.split('=',1) for line in output.splitlines() if '=' in line)
            rows.append({'check':'service:'+name,'ok':fields.get('ActiveState')=='active','state':fields})
        except Exception as error:rows.append({'check':'service:'+name,'ok':False,'error':error_kind(error)})
    for name,port in [('worker',manifest['worker_port']),('proxy',manifest['proxy_port']),('prometheus',19090),('node',19100),('grafana',13000)]:
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=2):pass
            rows.append({'check':'port:'+name,'port':port,'ok':True})
        except Exception as error:rows.append({'check':'port:'+name,'port':port,'ok':False,'error':error_kind(error)})
    for name,url in [('prometheus-ready','http://127.0.0.1:19090/-/ready'),('grafana-database','http://127.0.0.1:13000'+manifest.get('grafana_path','')+'/api/health')]:
        try:
            body=fetch(url)
            ok=name=='prometheus-ready' or json.loads(body).get('database')=='ok'
            rows.append({'check':name,'ok':ok})
        except Exception as error:rows.append({'check':name,'ok':False,'error':error_kind(error)})
    try:
        targets=json.loads(fetch('http://127.0.0.1:19090/api/v1/targets'))['data']['activeTargets']
        for name in ('gateway-worker','gateway-proxy','gateway-host'):
            matches=[t for t in targets if t.get('labels',{}).get('job')==name]
            ok=len(matches)==1 and matches[0].get('health')=='up'
            row={'check':'scrape:'+name,'ok':ok}
            if not ok:
                message=matches[0].get('lastError','') if len(matches)==1 else ''
                row['error']='CONNECTION_REFUSED' if 'connection refused' in message.lower() else 'HTTP_401' if '401' in message else 'HTTP_403' if '403' in message else 'TIMEOUT' if 'timeout' in message.lower() or 'deadline' in message.lower() else 'TARGET_MISSING_OR_UNHEALTHY'
            rows.append(row)
    except Exception as error:rows.append({'check':'scrape-targets','ok':False,'error':error_kind(error)})
    return {'timeUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':rows,'ok':all(row['ok'] for row in rows)}

def save(root,report):
    import os
    directory=pathlib.Path(root)/'setup-logs';directory.mkdir(mode=0o700,exist_ok=True)
    path=directory/('health-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')+'.json')
    with path.open('x',encoding='utf-8') as output:json.dump(report,output,ensure_ascii=False,indent=2)
    os.chmod(str(path),0o600)
    for row in report['checks']:print(('PASS ' if row['ok'] else 'FAIL ')+row['check']+' '+str(row.get('port',''))+' '+str(row.get('error',row.get('state',''))),flush=True)
    print('Diagnostic log: '+str(path),flush=True)
    return path
