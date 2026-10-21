/* All settings and plaintext downloads require administrator authentication. */
function sm4Options(defaults=false){return `<div class="grid"><label>最大结果行数（1—1000）<input name="maxRows" type="number" min="1" max="1000" value="200" required></label><label>新文件有效期（天，1—365）<input name="ttlDays" type="number" min="1" max="365" step="1" value="1"><small class="sm4TtlHint">仅影响之后导出的文件。</small></label></div><label><input name="enabled" type="checkbox">${defaults?'自动启用加密':'启用逐行 SM4 加密'}</label><label><input name="allowDecrypt" type="checkbox">${defaults?'自动授予管理员解密授权':'允许管理员解密该账号的文件'}</label><label><input name="allowEdit" type="checkbox" checked>允许编辑普通列后解密（无需业务标识列）</label>`;}
addPage('sm4Export','SM4 加密导出',`<div class="card"><h3>逐行密文配置</h3><p class="notice">按账号＋逻辑库配置。开启后，受保护查询会新增最后一列 __gateway_sm4，不要求 SQL 包含任何固定编号。普通列编辑模式仅校验受保护字段；普通内容不代表经过认证的原始业务事实。保护值相同的行交换密文可能无法识别。</p><form id="sm4ProfileForm"><div class="grid"><label>数据库用户名<input name="username" required autocomplete="off"></label><label>逻辑数据库（可填写多个，每行一个）<textarea name="databaseNames" required></textarea></label></div>${sm4Options()}<p>只停止加密不会阻止历史文件解密；需关闭解密授权或撤销批次。已导出文件的模式及有效期不随配置修改。普通列保留原类型；受保护列统一返回脱敏文本，解密 CSV 恢复原值（二进制为 Base64）。复杂类型按驱动文本表示传递。超过行数或大小限制则整个查询失败。</p><button type="submit" class="primary">保存配置</button><button type="button" onclick="newSm4Profile()">新增配置</button></form><div id="sm4Profiles"></div></div>
<div class="card"><h3>跟随账号和接入库自动关联</h3><form id="sm4DefaultsForm"><label><input name="linked" type="checkbox">开启自动关联</label>${sm4Options(true)}<p>开启后，本 Worker 当前和后续有效接入的账号＋库组合会使用以下默认设置。人工配置优先，不被覆盖；已删除的配置不会补回。修改默认值会影响所有自动关联项；关闭关联会停止这些项的加密及解密。可点击列表中的编辑保存为人工配置。</p><button type="submit">保存自动关联设置</button></form></div>
<div class="card"><h3>授权解密 CSV</h3><p>上传带表头的 UTF-8 CSV，最后一列必须保留 __gateway_sm4。新模式允许新增、删除、改名、改值和重排普通列；受保护列只允许移动，不能删列、改名或改值。整行排序或删行时，密文必须随该行保留。禁止重复行或混合不同查询批次，表头不能重名。旧文件继续遵循生成时的规则，需要重新查询导出才能使用新模式。下载时可能给公式开头的值添加单引号。</p><input id="sm4CsvFile" type="file" accept=".csv,text/csv"><button onclick="decryptSm4File(this)" class="primary">校验并下载明文</button><p id="sm4DownloadStatus" aria-live="polite"></p></div>
<div class="card"><div class="toolbar"><h3>最近 100 条加密记录</h3><button onclick="run(this,loadSm4Export)">刷新</button></div><p>新格式按查询批次撤销。系统保存批次公共元数据，不保存完整结果或逐行密文；需要同时备份 Worker 数据和密钥。过期批次自动清理，审计默认保留。</p><div id="sm4Receipts"></div></div>`,loadSm4Export);
let sm4ProfileRows=[];
function fillSm4Options(form,row){
 for(const name of ['enabled','allowDecrypt','allowEdit'])form.elements[name].checked=!!row[name];
 form.elements.maxRows.value=row.maxRows||200;
 const minutes=Number(row.ttlMinutes||1440),days=minutes/1440;
 form.elements.ttlDays.value=Number.isInteger(days)?days:'';
 form.querySelector('.sm4TtlHint').textContent=Number.isInteger(days)?'仅影响之后导出的文件。':`历史设置为 ${minutes} 分钟；留空保留，填写整数天数则修改新文件期限。`;
 form.dataset.ttlMinutes=minutes;
}
function sm4Values(form){
 const r={maxRows:+form.elements.maxRows.value};
 for(const name of ['enabled','allowDecrypt','allowEdit'])r[name]=form.elements[name].checked;
 if(form.elements.ttlDays.value!=='')r.ttlDays=+form.elements.ttlDays.value;
 else r.ttlMinutes=Number(form.dataset.ttlMinutes||1440);
 return r;
}
async function loadSm4Export(){
 const [profiles,receipts,defaults]=await Promise.all([api('/api/sm4-export/profiles'),api('/api/sm4-export/receipts'),api('/api/sm4-export/defaults')]);
 sm4ProfileRows=profiles;
 const display=profiles.map((r,i)=>({...r,index:i,validity:Number(r.ttlMinutes)%1440===0?(r.ttlMinutes/1440)+' 天':r.ttlMinutes+' 分钟（历史配置）'}));
 $('sm4Profiles').innerHTML=table(display,[['username','账号'],['databaseName','库'],['source','配置来源'],['enabled','加密'],['allowDecrypt','解密授权'],['allowEdit','普通列可编辑'],['maxRows','行数上限'],['validity','有效期']],r=>'<button onclick="editSm4Profile('+r.index+')">编辑</button><button onclick="deleteSm4Profile(this,'+r.index+')">删除</button>');
 fillSm4Options($('sm4DefaultsForm'),defaults);$('sm4DefaultsForm').elements.linked.checked=!!defaults.linked;
 $('sm4Receipts').innerHTML=table(receipts.map(r=>({...r,expiresAt:new Date(r.expiresAt).toLocaleString()})),[['id','记录编号'],['username','原账号'],['databaseName','库'],['rowCount','行数'],['revokeScope','撤销范围'],['expiresAt','有效截止'],['revoked','已撤销']],r=>r.revoked?'':'<button onclick="revokeSm4File(this,'+arg(r.id)+')">撤销解密</button>');
}
$('sm4ProfileForm').onsubmit=async e=>{e.preventDefault();const f=e.target,r=sm4Values(f);r.username=f.elements.username.value;r.databaseNames=f.elements.databaseNames.value.split(/\r?\n/).map(v=>v.trim()).filter(Boolean);
 if(!await confirmAction('保存加密导出设置','将更新所填账号和库的配置。关闭解密授权会阻止历史文件解密；普通列编辑仅适用于之后新导出的文件。确认保存？'))return;
 await run(e.submitter,async()=>{const result=await api('/api/sm4-export/profiles/batch',{method:'POST',body:JSON.stringify(r)});notify(result.message);await loadSm4Export();});};
$('sm4DefaultsForm').onsubmit=async e=>{e.preventDefault();const r=sm4Values(e.target);r.linked=e.target.elements.linked.checked;
 if(!await confirmAction('更新自动关联设置','将应用到当前和后续未人工配置、未删除的有效账号＋接入库组合。自动授予解密授权：'+(r.allowDecrypt?'是':'否')+'；开启关联：'+(r.linked?'是':'否')+'。确认保存？'))return;
 await run(e.submitter,async()=>{const result=await api('/api/sm4-export/defaults',{method:'POST',body:JSON.stringify(r)});notify(result.message);await loadSm4Export();});};
function newSm4Profile(){const f=$('sm4ProfileForm');f.reset();f.elements.username.readOnly=false;f.elements.databaseNames.readOnly=false;delete f.dataset.ttlMinutes;f.querySelector('.sm4TtlHint').textContent='仅影响之后导出的文件。';}
function editSm4Profile(index){const r=sm4ProfileRows[index],f=$('sm4ProfileForm');f.elements.username.value=r.username;f.elements.databaseNames.value=r.databaseName;f.elements.username.readOnly=true;f.elements.databaseNames.readOnly=true;fillSm4Options(f,r);f.scrollIntoView({block:'start'});}
async function deleteSm4Profile(b,index){const r=sm4ProfileRows[index];if(!await confirmAction('删除加密导出配置','将停止该账号在此库的加密和历史文件解密，自动关联不会补回；可手动重新添加。确认删除？'))return;await run(b,async()=>{await api('/api/sm4-export/profiles',{method:'DELETE',body:JSON.stringify({username:r.username,databaseName:r.databaseName})});await loadSm4Export();});}
async function decryptSm4File(b){await run(b,async()=>{
 const f=$('sm4CsvFile').files[0];if(!f)throw Error('请选择 CSV 文件');if(f.size>1024*1024)throw Error('文件超过 1 MiB，请缩小原查询范围');
 const csv=new TextDecoder('utf-8',{fatal:true}).decode(await f.arrayBuffer());
 const result=await api('/api/sm4-export/decrypt',{method:'POST',body:JSON.stringify({csv})});
 const url=URL.createObjectURL(new Blob([result.csv],{type:'text/csv;charset=utf-8'})),a=document.createElement('a');a.href=url;a.download=result.filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),10000);
 $('sm4CsvFile').value='';$('sm4DownloadStatus').textContent='校验通过，已生成明文下载。请按敏感数据要求保管文件。';
});}
async function revokeSm4File(b,id){if(!await confirmAction('撤销文件解密','新格式撤销整次查询及所有副本；旧格式撤销该行。是否继续？'))return;await run(b,async()=>{await api('/api/sm4-export/receipts/'+encodeURIComponent(id)+'/revoke',{method:'POST'});await loadSm4Export();});}
