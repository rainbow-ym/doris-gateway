const $=id=>document.getElementById(id);
const operationName={ENCRYPT_QUERY:'加密查询',DECRYPT_FILE:'文件解密'};
let page=1,revision=0;
function node(tag,text){const element=document.createElement(tag);element.textContent=text;return element;}
function params(){return new URLSearchParams({cluster:$('cluster').value,operation:$('operation').value});}
async function get(path,query){const response=await fetch(path+'?'+query);if(!response.ok)throw new Error('模拟接口请求失败：'+response.status);return response.json();}
async function details(id){
  const current=revision;
  try{
    const record=await get('/api/observability/requests/'+encodeURIComponent(id),params());
    if(current!==revision)return;
    const body=$('detailBody');body.replaceChildren();
    body.append(node('p','模拟请求编号：'+record.requestId));
    const grid=node('div','');grid.className='detail-grid';
    for(const [label,value] of Object.entries({'操作':operationName[record.operation],'结果':record.label,'总耗时':record.durationMs+' ms','处理行数':record.rows,'敏感单元格':record.protectedCells,'原始敏感数据':record.rawBytes+' bytes','密文字节':record.ciphertextBytes+' bytes','密文 / 原始敏感数据':(record.ciphertextBytes/record.rawBytes).toFixed(2)+' 倍（含元数据开销）'}))grid.append(node('p',label+'：'+value));
    body.append(grid,node('h3','分阶段耗时（模拟）'));
    const table=node('table','');const head=node('tr','');for(const text of ['阶段','组件','调用次数','累计耗时'])head.append(node('th',text));table.append(head);
    for(const stage of record.stages){const row=node('tr','');for(const text of [stage.name,stage.component,stage.calls,stage.totalMs+' ms'])row.append(node('td',text));table.append(row);}
    const wrap=node('div','');wrap.className='table-wrap';wrap.append(table);body.append(wrap);
    body.append(node('p','各阶段可能包含或重叠，不应直接求和。这里没有测量纯网络耗时。'),node('p','处理建议：'+record.suggestion));
    $('detail').showModal();
  }catch(e){$('error').textContent=e.message;}
}
async function load(){
  const current=++revision;$('error').textContent='';
  for(const id of ['prev','next','refresh'])$(id).disabled=true;
  try{
    const query=params(),listQuery=new URLSearchParams(query);listQuery.set('page',page);listQuery.set('size',10);listQuery.set('outcome',$('outcome').value);
    const [summary,list]=await Promise.all([get('/api/observability/summary',query),get('/api/observability/requests',listQuery)]);
    if(current!==revision)return;
    $('cards').replaceChildren();
    for(const [label,value] of [['请求样本',summary.total],['成功',summary.success],['安全拒绝',summary.denied],['程序 / 依赖故障',summary.errors],['P50',summary.p50==null?'无样本':summary.p50+' ms'],['P95',summary.p95==null?'无样本':summary.p95+' ms'],['P99',summary.p99==null?'无样本':summary.p99+' ms'],['处理中','未接入']]){const card=node('div','');card.className='card';card.append(node('span',label),node('strong',value));$('cards').append(card);}
    $('rows').replaceChildren();
    for(const item of list.items){const row=node('tr','');for(const text of [item.requestId.slice(-12),operationName[item.operation],item.label,item.durationMs+' ms',item.rows])row.append(node('td',text));const cell=node('td',''),button=node('button','查看详情');button.addEventListener('click',()=>details(item.requestId));cell.append(button);row.append(cell);$('rows').append(row);}
    if(!list.items.length){const row=node('tr',''),cell=node('td','当前筛选没有请求样本');cell.colSpan=6;row.append(cell);$('rows').append(row);}
    $('pageInfo').textContent=`共 ${list.total} 条 · 第 ${page} / ${Math.max(1,Math.ceil(list.total/10))} 页`;
    $('prev').disabled=page<=1;$('next').disabled=page*10>=list.total;
  }catch(e){if(current===revision){$('error').textContent=e.message;$('cards').replaceChildren();$('rows').replaceChildren();$('pageInfo').textContent='数据不可用';}}
  finally{if(current===revision)$('refresh').disabled=false;}
}
for(const id of ['cluster','operation','outcome'])$(id).addEventListener('change',()=>{page=1;$('detail').close();load();});
$('prev').addEventListener('click',()=>{page--;load();});$('next').addEventListener('click',()=>{page++;load();});
$('refresh').addEventListener('click',load);$('close').addEventListener('click',()=>$('detail').close());load();
