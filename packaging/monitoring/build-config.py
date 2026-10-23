"""Generate bounded-label dashboards and Prometheus rules. No deployed settings or secrets."""
import json,pathlib
root=pathlib.Path(__file__).resolve().parent
selector='component="$component",operation="$operation",outcome="SUCCESS"'
base='gateway_export_duration_seconds'
count='sum(rate('+base+'_count{'+selector+'}[$__rate_interval]))'
seconds='sum(rate('+base+'_sum{'+selector+'}[$__rate_interval]))'
def measure(name):return 'sum(rate(gateway_export_measure_total{'+selector+',measure="'+name+'"}[$__rate_interval]))'
queries=[
 ('容器内存/限额','gateway_cgroup_memory_used_bytes/gateway_cgroup_memory_limit_bytes','percentunit'),
 ('容器 CPU 限流秒/秒','rate(gateway_cgroup_cpu_throttled_seconds_total[$__rate_interval])','s'),
 ('容器内存采集覆盖','gateway_cgroup_memory_available','short'),
 ('所选时间成功请求总数','sum(increase('+base+'_count{'+selector+'}[$__range]))','short'),
 ('所选时间成功行数','sum(increase(gateway_export_measure_total{'+selector+',measure="ROWS"}[$__range]))','short'),
 ('所选时间平均请求耗时','sum(increase('+base+'_sum{'+selector+'}[$__range]))/sum(increase('+base+'_count{'+selector+'}[$__range]))','s'),
 ('成功请求/秒',count,'reqps'),('成功行/秒',measure('ROWS'),'ops'),('成功保护单元格/秒',measure('PROTECTED_CELLS'),'ops'),
 ('平均请求耗时',seconds+'/'+count,'s'),('每行摊销耗时',seconds+'/'+measure('ROWS'),'s'),
 ('请求 P50','histogram_quantile(0.50,sum by(le)(rate('+base+'_bucket{'+selector+'}[$__rate_interval])))','s'),
 ('请求 P95','histogram_quantile(0.95,sum by(le)(rate('+base+'_bucket{'+selector+'}[$__rate_interval])))','s'),
 ('请求 P99','histogram_quantile(0.99,sum by(le)(rate('+base+'_bucket{'+selector+'}[$__rate_interval])))','s'),
 ('观察到的最长耗时（完整分钟）','max(max_over_time(gateway_export_last_minute_max_seconds{'+selector+'}[$__range]))','s'),
 ('成功数据负载字节/秒',measure('PAYLOAD_BYTES'),'Bps'),('密文字节/秒',measure('CIPHERTEXT_BYTES'),'Bps'),
 ('各结果请求/秒','sum by(outcome)(rate('+base+'_count{component="$component",operation="$operation"}[$__rate_interval]))','reqps'),
 ('阶段累计秒/秒（嵌套不可相加）','sum by(stage)(rate(gateway_export_stage_seconds_total{'+selector+'}[$__rate_interval]))','s'),
 ('每行密码运算平均耗时','sum(rate(gateway_export_stage_seconds_total{'+selector+',stage=~"ROW_ENCRYPT|ROW_DECRYPT"}[$__rate_interval]))/sum(rate(gateway_export_stage_calls_total{'+selector+',stage=~"ROW_ENCRYPT|ROW_DECRYPT"}[$__rate_interval]))','s'),
 ('采集目标状态','up{job=~"gateway-.*"}','short'),
 ('Proxy 详情丢失/秒','rate(gateway_export_detail_dropped_total[$__rate_interval])','ops'),
 ('Proxy 观测拒绝累计','gateway_export_observation_dropped','short'),
 ('Worker 观测拒绝累计','gateway_observation_dropped','short'),
 ('Proxy 连接池等待线程','gateway_proxy_pool_pending','short'),
 ('Proxy 连接池活跃/上限','gateway_proxy_pool_active/gateway_proxy_pool_maximum','percentunit'),
 ('Proxy 堆内存占比','gateway_proxy_heap_used_bytes/gateway_proxy_heap_max_bytes','percentunit'),
 ('Worker 堆内存','sum(jvm_memory_used_bytes{area="heap"})','bytes'),
 ('Proxy GC 秒/秒','rate(gateway_proxy_gc_seconds_total[$__rate_interval])','s'),
 ('主机 CPU 使用率','1-avg(rate(node_cpu_seconds_total{mode="idle"}[$__rate_interval]))','percentunit'),
 ('主机可用内存','node_memory_MemAvailable_bytes','bytes'),
 ('主机磁盘可用空间','node_filesystem_avail_bytes{fstype!~"tmpfs|overlay|squashfs"}','bytes'),
 ('采集中断（过去 5 分钟）','min_over_time(up{job=~"gateway-.*"}[5m])','short')]
panels=[]
for i,(title,expr,unit) in enumerate(queries):
 panels.append({'id':i+1,'type':'timeseries','title':title,'datasource':{'type':'prometheus','uid':'gateway-prometheus'},'gridPos':{'x':(i%2)*12,'y':(i//2)*8,'w':12,'h':8},'targets':[{'refId':'A','expr':expr,'legendFormat':'{{stage}}{{outcome}}{{job}}{{mountpoint}}'}],'fieldConfig':{'defaults':{'unit':unit},'overrides':[]}})
 if title.startswith('所选时间') or title.startswith('观察到的最长'):
  panels[-1]['type']='stat';panels[-1]['targets'][0]['instant']=True
  panels[-1]['options']={'reduceOptions':{'calcs':['lastNotNull'],'values':False},'graphMode':'none'}
dashboard={'uid':'gateway-capacity','title':'网关加解密容量与质量','schemaVersion':39,'version':1,'timezone':'browser','refresh':'15s','time':{'from':'now-1h','to':'now'},'tags':['gateway'],'templating':{'list':[{'name':'component','type':'custom','query':'worker,proxy','current':{'text':'worker','value':'worker'}},{'name':'operation','type':'custom','query':'ENCRYPT_QUERY,DECRYPT_FILE','current':{'text':'ENCRYPT_QUERY','value':'ENCRYPT_QUERY'}}]},'panels':panels,'description':'按完成时间统计；成功行统计不含失败请求。Proxy 不等于客户端接收完成。最长值仅完整分钟及采集覆盖内有效，不能把缺失当零。阶段嵌套不可相加。'}
(root/'dashboard.json').write_text(json.dumps(dashboard,ensure_ascii=False,indent=2),encoding='utf-8')
rules={'groups':[{'name':'gateway-capacity','rules':[
 {'alert':'GatewayMetricsUnavailable','expr':'up{job=~"gateway-.*"} == 0','for':'1m','labels':{'severity':'warning'},'annotations':{'summary':'网关监控采集失败，容量统计不完整'}},
 {'alert':'GatewayDetailLoss','expr':'increase(gateway_export_detail_dropped_total[5m]) > 0','labels':{'severity':'warning'},'annotations':{'summary':'Proxy 请求详情丢失；累计指标由本机独立采集'}},
 {'alert':'GatewayObservationLoss','expr':'increase(gateway_observation_dropped[5m]) > 0 or increase(gateway_export_observation_dropped[5m]) > 0','labels':{'severity':'critical'},'annotations':{'summary':'核心观测出现缺口，停止据此推断最大容量'}},
 {'alert':'GatewayPoolWaiting','expr':'gateway_proxy_pool_pending > 0','for':'2m','labels':{'severity':'warning'},'annotations':{'summary':'数据库连接池持续等待'}},
 {'alert':'GatewayRequestErrors','expr':'sum by(component,operation)(rate(gateway_export_duration_seconds_count{outcome=~"DEPENDENCY_ERROR|INTERNAL_ERROR|BUSY"}[5m])) / sum by(component,operation)(rate(gateway_export_duration_seconds_count[5m])) > 0.01','for':'5m','labels':{'severity':'warning'},'annotations':{'summary':'依赖错误、内部错误或繁忙占比超过初始阈值 1%'}},
 {'alert':'GatewayLatency','expr':'histogram_quantile(0.95,sum by(component,operation,le)(rate(gateway_export_duration_seconds_bucket{outcome="SUCCESS"}[5m]))) > 5','for':'5m','labels':{'severity':'warning'},'annotations':{'summary':'成功请求 P95 超过初始阈值 5 秒，按业务 SLO 调整'}}]}]}
(root/'rules.json').write_text(json.dumps(rules,ensure_ascii=False,indent=2),encoding='utf-8')
