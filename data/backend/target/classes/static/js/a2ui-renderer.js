document.addEventListener('DOMContentLoaded', () => {
  const $ = s => document.querySelector(s);
  const form=$('#chat-form'), input=$('#chat-input'), list=$('#message-list'), welcome=$('#welcome'), picker=$('#user-picker');
  const esc=A2UIRegistry.escape;
  function renderAnswerMarkdown(source){
    const lines=String(source||'').replace(/\\\|/g,'|').replace(/\r/g,'').split('\n');
    const inline=s=>esc(s).replace(/\*\*(.+?)\*\*/g,'<strong>$1</strong>').replace(/`([^`]+)`/g,'<code>$1</code>');
    const cells=line=>line.trim().replace(/^\|/,'').replace(/\|$/,'').split('|').map(x=>x.trim());
    const blocks=[];let i=0;
    while(i<lines.length){
      if(lines[i].trim().startsWith('|')&&i+1<lines.length&&/^\s*\|?\s*:?-{3,}/.test(lines[i+1])){
        const head=cells(lines[i]);i+=2;const rows=[];
        while(i<lines.length&&lines[i].trim().startsWith('|')){rows.push(cells(lines[i]));i++}
        blocks.push(`<div class="answer-table-wrap"><table class="answer-table"><thead><tr>${head.map(x=>`<th>${inline(x)}</th>`).join('')}</tr></thead><tbody>${rows.map(row=>`<tr>${head.map((_,j)=>`<td>${inline(row[j]||'')}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`);continue;
      }
      const line=lines[i++];if(!line.trim()){continue}
      if(/^###?\s/.test(line)){blocks.push(`<h3>${inline(line.replace(/^###?\s+/,''))}</h3>`);continue}
      if(/^\s*[-*]\s+/.test(line)){blocks.push(`<div class="answer-list-item">• ${inline(line.replace(/^\s*[-*]\s+/,''))}</div>`);continue}
      blocks.push(`<p>${inline(line)}</p>`);
    }
    return blocks.join('');
  }
  const STORE='fieldnotes-conversations-v1';
  const USER_STORE='fieldnotes-active-user-v1';
  let users=[],activeUser='',activeId=null,busy=false,toastTimer,databases=[];

  function allChats(){try{return JSON.parse(localStorage.getItem(STORE)||'{}')}catch{return {}}}
  function saveChats(chats){try{localStorage.setItem(STORE,JSON.stringify(chats))}catch{showToast('This browser could not save conversation history.')}}
  function currentDatabaseId(){return databases.find(d=>d.selected)?.id||''}
  function userChats(){const chats=allChats()[activeUser]||[],databaseId=currentDatabaseId();return chats.filter(c=>!databaseId||!c.databaseId||c.databaseId===databaseId).sort((a,b)=>String(b.updatedAt||'').localeCompare(String(a.updatedAt||'')))}
  function saveChat(chat){chat.databaseId=chat.databaseId||currentDatabaseId();const all=allChats(),chats=(all[activeUser]||[]).filter(x=>x.id!==chat.id);chats.unshift(chat);all[activeUser]=chats.slice(0,100);saveChats(all);renderHistory()}
  function showToast(message){const t=$('#toast');t.textContent=message;t.classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>t.classList.remove('visible'),3200)}
  function setConnection(ok,label){$('#connection-dot').style.background=ok?'#83ca91':'#e49a83';$('#connection-label').textContent=label;$('.live-indicator').innerHTML=`<span style="background:${ok?'#83ca91':'#e49a83'}"></span> ${ok?'FIELD DATA CONNECTED':'FIELD DATA OFFLINE'}`}
  function initials(name){return String(name||'?').split(/\s+/).slice(0,2).map(p=>p[0]||'').join('').toUpperCase()}

  async function loadUsers(){
    try{
      const response=await fetch('/api/users',{headers:{'Accept':'application/json'}});
      if(!response.ok)throw new Error(`HTTP ${response.status}`);
      users=await response.json();
      if(!Array.isArray(users)||!users.length)throw new Error('No users were returned');
      picker.innerHTML=users.map(u=>`<option value="${esc(u.user_id)}">${esc(u.name)} · ${esc(String(u.role||'').replaceAll('_',' '))}</option>`).join('');
      const savedUser=localStorage.getItem(USER_STORE);activeUser=users.some(x=>x.user_id===savedUser)?savedUser:users[0].user_id;picker.value=activeUser;updateUserBadge();setConnection(true,'Connected to field data');await loadConversationHistory();
    }catch(error){picker.innerHTML='<option value="">Unavailable</option>';setConnection(false,'Could not reach backend');showToast('Could not load field users. Check that the backend is running.');console.error(error)}
  }
  async function loadDatabases(){
    try{const r=await fetch('/api/databases');if(!r.ok)throw new Error('Could not load databases');const data=await r.json();databases=data.databases||[];const select=$('#database-picker');select.innerHTML=databases.map(d=>`<option value="${esc(d.id)}">${esc(d.name)} · ${Number(d.records).toLocaleString()} records</option>`).join('');const selected=databases.find(d=>d.selected);if(selected){select.value=selected.id;const latest=selected.latestChange||'No changes yet';$('#database-latest').textContent=`Latest · ${latest}`;$('#database-latest').title=`Latest change · ${latest}`;$('#database-history')?.setAttribute('title',`Latest change · ${latest} · Open change history`)} }
    catch(e){$('#database-picker').innerHTML='<option value="">Databases unavailable</option>';console.error(e)}
  }
  async function loadConversationHistory(){
    if(!activeUser)return;const databaseId=currentDatabaseId();const all=allChats(),local=(all[activeUser]||[]).filter(c=>!c.databaseId||!databaseId||c.databaseId===databaseId);
    local.forEach(c=>{if(databaseId)c.databaseId=databaseId});
    try{
      const response=await fetch('/api/conversations/sync',{method:'POST',headers:{'Content-Type':'application/json','Accept':'application/json'},body:JSON.stringify({userId:activeUser,conversations:local})});
      const data=await response.json();if(!response.ok)throw new Error(data.message||'Could not restore saved chats');
      const current=data.conversations||[],other=(all[activeUser]||[]).filter(c=>c.databaseId&&databaseId&&c.databaseId!==databaseId);all[activeUser]=[...current,...other].sort((a,b)=>String(b.updatedAt||'').localeCompare(String(a.updatedAt||''))).slice(0,100);saveChats(all);
      renderHistory();const latest=userChats()[0];if(latest)showChat(latest);else showChat(null);
    }catch(error){console.warn('Could not sync conversations with the backend',error);renderHistory();const latest=userChats()[0];if(latest)showChat(latest);else showChat(null)}
  }
  async function deleteChat(chat){
    if(busy){showToast('Wait for the current answer to finish before deleting a chat.');return}
    try{const response=await fetch(`/api/conversations/${encodeURIComponent(chat.id)}?userId=${encodeURIComponent(activeUser)}`,{method:'DELETE'});const data=await response.json();if(!response.ok)throw new Error(data.message||'Could not delete chat');const all=allChats();all[activeUser]=(all[activeUser]||[]).filter(c=>c.id!==chat.id);saveChats(all);if(activeId===chat.id)showChat(null);renderHistory();showToast('Chat deleted')}catch(e){showToast(e.message||'Could not delete chat')}
  }
  async function switchDatabase(id){if(busy){showToast('Wait for the current answer to finish before switching databases.');$('#database-picker').value=databases.find(d=>d.selected)?.id||'';return}try{const r=await fetch(`/api/databases/${encodeURIComponent(id)}/select`,{method:'POST'});const data=await r.json();if(!r.ok)throw new Error(data.message||'Could not switch database');activeId=null;showChat(null);renderHistory();await loadDatabases();const u=users.find(x=>x.user_id===activeUser);showToast(`Now using ${data.database?.name||'selected database'}`);await loadUsers();if(u){activeUser=u.user_id;picker.value=activeUser;updateUserBadge()}}catch(e){showToast(e.message||'Could not switch databases');await loadDatabases()}}
  async function importDatabase(file){if(!file)return;if(busy){showToast('Wait for the current answer to finish before importing a database.');return}const body=new FormData();body.append('file',file);const button=$('#import-database');button.disabled=true;button.textContent='Importing…';try{const r=await fetch('/api/databases/import',{method:'POST',body});const data=await r.json();if(!r.ok)throw new Error(data.message||'Database import failed');activeId=null;showChat(null);await loadDatabases();await loadUsers();showToast(`${data.name} imported and selected`)}catch(e){showToast(e.message||'Database import failed')}finally{button.disabled=false;button.textContent='＋ Import ZIP';$('#database-file').value=''}}
  async function showDatabaseHistory(){try{const r=await fetch('/api/databases/history');if(!r.ok)throw new Error('Could not load history');const rows=await r.json();const dialog=document.createElement('dialog');dialog.className='history-dialog';const title=makeElement('h2','','Database change history');const close=makeElement('button','history-close','Close');close.type='button';close.addEventListener('click',()=>dialog.close());dialog.append(title,close);if(!rows.length)dialog.append(makeElement('p','', 'No changes have been recorded yet.'));else{const list=document.createElement('div');list.className='database-change-list';rows.forEach(x=>{const item=document.createElement('article');item.append(makeElement('strong','',`v${x.version||'—'} · ${x.changeType} · ${x.entityType} · ${x.recordId}`),makeElement('p','',x.summary),makeElement('p','change-meta',`Changed by ${x.changedBy||'system'} · ${new Date(x.changedAt).toLocaleString()}`));if(x.reason)item.append(makeElement('p','change-reason',`Reason: ${x.reason}`));if(x.oldValue||x.newValue){const details=document.createElement('details');details.className='change-values';details.append(makeElement('summary','','Show old and new values'));const before=makeElement('pre','change-json',JSON.stringify(x.oldValue,null,2)||'—');const after=makeElement('pre','change-json',JSON.stringify(x.newValue,null,2)||'—');details.append(makeElement('span','change-side-label','OLD'),before,makeElement('span','change-side-label','NEW'),after);item.append(details)}list.append(item)});dialog.append(list)}document.body.append(dialog);dialog.addEventListener('close',()=>dialog.remove(),{once:true});dialog.showModal()}catch(e){showToast(e.message)}}
  function openEditorDialog(){
    const db=databases.find(d=>d.selected);if(!db){showToast('Choose a database first.');return}
    const dialog=document.createElement('dialog');dialog.className='history-dialog editor-dialog';const title=makeElement('h2','','Edit database records');const close=makeElement('button','history-close','Close');close.type='button';close.addEventListener('click',()=>dialog.close());dialog.append(title,close);
    const controls=makeElement('div','editor-controls');const type=document.createElement('select');type.className='editor-type';type.setAttribute('aria-label','Record collection');type.append(new Option('Loading collections…',''));const query=document.createElement('input');query.placeholder='Search by ID, well, date, or text';query.className='editor-search';const search=makeElement('button','database-import','Search');search.type='button';controls.append(type,query,search);dialog.append(controls);
    const results=makeElement('div','record-results');const editor=makeElement('section','record-edit-panel');dialog.append(results,editor);let selectedRecord=null;
    const refresh=async()=>{results.replaceChildren(makeElement('p','editor-hint','Loading records…'));editor.replaceChildren();try{const url=`/api/databases/${encodeURIComponent(db.id)}/records?type=${encodeURIComponent(type.value)}&q=${encodeURIComponent(query.value)}&limit=50`;const r=await fetch(url);const data=await r.json();if(!r.ok)throw new Error(data.message||'Could not load records');results.replaceChildren();if(!data.records.length)results.append(makeElement('p','editor-hint','No matching records.'));data.records.forEach(row=>{const button=makeElement('button','record-result',`${row.id}${row.wellId?' · '+row.wellId:''}${row.date?' · '+row.date:''}`);button.type='button';button.addEventListener('click',()=>{selectedRecord=row;renderRecord()});results.append(button)})}catch(e){results.replaceChildren(makeElement('p','editor-error',e.message))}};
    const renderRecord=()=>{if(!selectedRecord)return;editor.replaceChildren();editor.append(makeElement('h3','',`${selectedRecord.id} · version ${selectedRecord.version}`),makeElement('p','editor-hint','Current record. Your patch below only changes fields you include; all other fields stay as they are.'));const old=makeElement('pre','change-json current-record',JSON.stringify(selectedRecord.record,null,2));editor.append(makeElement('span','change-side-label','CURRENT VALUES'),old);const patchLabel=makeElement('label','editor-label','Changes (JSON object)');const patch=document.createElement('textarea');patch.className='editor-json-input';patch.spellcheck=false;patch.value='{}';patchLabel.append(patch);editor.append(patchLabel);const reasonLabel=makeElement('label','editor-label','Why are you making this change?');const reason=document.createElement('textarea');reason.className='editor-reason-input';reason.maxLength=2000;reason.placeholder='Describe the source or operational reason for this edit.';reasonLabel.append(reason);editor.append(reasonLabel);const save=makeElement('button','editor-save','Save versioned change');save.type='button';save.addEventListener('click',async()=>{let changes;try{changes=JSON.parse(patch.value)}catch{showToast('Changes must be valid JSON.');return}save.disabled=true;save.textContent='Saving…';try{const response=await fetch(`/api/databases/${encodeURIComponent(db.id)}/records/${encodeURIComponent(type.value)}/${encodeURIComponent(selectedRecord.id)}`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({expectedVersion:selectedRecord.version,changes,changedBy:activeUser,reason:reason.value})});const result=await response.json();if(!response.ok)throw new Error(result.message||'Could not save change');selectedRecord={...selectedRecord,version:result.version,record:result.newValue};await loadDatabases();renderRecord();showToast(`Saved ${result.changedFields.map(f=>f.field).join(', ')} as version ${result.version}. Review old and new values in History.`)}catch(e){showToast(e.message);if(/changed since|updated by another user/i.test(e.message))refresh()}finally{save.disabled=false;save.textContent='Save versioned change'}});editor.append(save)};
    search.addEventListener('click',refresh);query.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();refresh()}});type.addEventListener('change',refresh);document.body.append(dialog);dialog.addEventListener('close',()=>dialog.remove(),{once:true});dialog.showModal();(async()=>{try{const response=await fetch(`/api/databases/${encodeURIComponent(db.id)}/types`);const data=await response.json();if(!response.ok)throw new Error(data.message||'Could not load collections');type.replaceChildren(...data.map(x=>new Option(`${x.label} (${x.count})`,x.type)));if(!data.length){results.replaceChildren(makeElement('p','editor-hint','This database has no structured records yet.'));return}await refresh()}catch(e){results.replaceChildren(makeElement('p','editor-error',e.message))}})();
  }
  function updateUserBadge(){const u=users.find(x=>x.user_id===activeUser);$('#user-avatar').textContent=initials(u?.name)}
  function setWelcomeVisible(show){welcome.hidden=!show;list.classList.toggle('has-messages',!show)}
  function createChat(){const u=users.find(x=>x.user_id===activeUser);activeId=crypto.randomUUID?crypto.randomUUID():`local-${Date.now()}-${Math.random().toString(16).slice(2)}`;const now=new Date().toISOString();const chat={id:activeId,userId:activeUser,userName:u?.name||'',databaseId:currentDatabaseId(),title:'New conversation',createdAt:now,updatedAt:now,messages:[]};saveChat(chat);showChat(chat);input.focus();$('#sidebar').classList.remove('open')}
  function showChat(chat){activeId=chat?.id||null;$('#topbar-title').textContent=chat?.title||'New conversation';list.innerHTML='';(chat?.messages||[]).forEach(m=>appendMessage(m, false));setWelcomeVisible(!chat?.messages?.length);$('#conversation').scrollTop=$('#conversation').scrollHeight;}
  function renderHistory(){const el=$('#conversation-list'),chats=userChats();el.replaceChildren();if(!chats.length){el.append(makeElement('div','empty-history','Your conversations will show up here.'));return;}chats.forEach(chat=>{const row=makeElement('div','history-row');const open=makeElement('button',`history-item ${chat.id===activeId?'selected':''}`,chat.title||'New conversation');open.type='button';open.title=chat.title||'New conversation';open.addEventListener('click',()=>{showChat(chat);$('#sidebar').classList.remove('open')});const remove=makeElement('button','history-delete','×');remove.type='button';remove.title='Delete chat';remove.setAttribute('aria-label',`Delete chat: ${chat.title||'New conversation'}`);remove.addEventListener('click',()=>deleteChat(chat));row.append(open,remove);el.append(row)})}
  function makeElement(tag,cls,text){const e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=text;return e}
  function followUpQuestions(payload){
    const visual=payload?.visual;if(!visual)return [];
    const cols=visual.columns||[],type=String(visual.type||'').toLowerCase();
    if(type==='heatmap'&&cols.some(c=>String(c).includes('area'))&&cols.some(c=>String(c).includes('month'))){
      const year=(payload.answer||'').match(/\b20\d{2}\b/)?.[0]||'this period';
      return [`Which month had the most spills in ${year}?`,`Compare the spill totals across areas in ${year}.`,`Show me the spill records behind this heatmap.`];
    }
    if(type==='map')return ['Which area has the most markers?','Show the records behind these locations.'];
    if(type==='line'||type==='bar')return ['Explain the main pattern in this chart.','Show the records behind this chart.'];
    if(type==='table'||type==='heatmap')return ['Summarize the main pattern in these records.','Show a chart of this result.'];
    return ['Summarize the main pattern in this result.','Show the records behind this result.'];
  }
  function appendMessage(message, scroll=true){
    const isUser=message.role==='user',wrap=makeElement('article',`message ${isUser?'user-message':''}`);
    const avatar=makeElement('div','message-avatar',isUser?initials(users.find(u=>u.user_id===activeUser)?.name):'F');avatar.setAttribute('aria-hidden','true');wrap.append(avatar);
    const content=makeElement('div','message-content');
    if(isUser){content.textContent=message.text;wrap.append(content);list.append(wrap);}
    else {
      content.append(makeElement('div','assistant-label','FIELDNOTES COPILOT'));
      if(message.thinking){content.append(makeElement('div','thinking-dots'));content.querySelector('.thinking-dots').innerHTML='<i></i><i></i><i></i>';content.classList.add('thinking');}
      else if(message.error){content.append(makeElement('div','error-box',message.error));}
      else {
        const answer=makeElement('div','answer-text');
        answer.innerHTML=renderAnswerMarkdown(message.payload?.answer||'No text response.');content.append(answer);
        const visual=A2UIRegistry.render(message.payload?.visual);if(visual){const holder=document.createElement('div');holder.innerHTML=visual;content.append(holder.firstElementChild)}
        const followUps=followUpQuestions(message.payload);if(followUps.length){const section=makeElement('section','follow-up-list');section.append(makeElement('span','follow-up-label','KEEP EXPLORING'));followUps.forEach(question=>{const button=makeElement('button','follow-up-chip',question);button.type='button';button.addEventListener('click',()=>submitQuestion(question));section.append(button)});content.append(section)}
        const citations=message.payload?.citations||[];
        if(citations.length){const section=makeElement('section','citations');section.append(makeElement('div','citation-heading','SOURCE RECORDS & DOCUMENTS'));const chips=makeElement('div','citation-list');citations.slice(0,32).forEach(c=>chips.append(makeElement('span','citation',String(c).split(/[\\/]/).pop())));section.append(chips);content.append(section)}
        const meta=makeElement('div','answer-meta');meta.append(makeElement('span',message.payload?.grounded?'grounded-mark':'ungrounded-mark',message.payload?.grounded?'✓ GROUNDED':'! VERIFY FIGURES'));if(message.payload?.trace?.length){const details=document.createElement('details');details.className='trace';const summary=document.createElement('summary');summary.textContent=`Reasoning steps · ${message.payload.trace.length} tools`;details.append(summary);const ol=document.createElement('ol');message.payload.trace.forEach(t=>ol.append(makeElement('li','',t)));details.append(ol);content.append(details)}content.append(meta);
      }
      wrap.append(content);list.append(wrap);
    }
    if(scroll)requestAnimationFrame(()=>$('#conversation').scrollTop=$('#conversation').scrollHeight);
    return wrap;
  }
  function titleFor(text){const clean=text.replace(/\s+/g,' ').trim();return clean.length>42?`${clean.slice(0,39)}…`:clean||'New conversation'}
  async function submitQuestion(text=input.value.trim()){
    if(!text||busy||!activeUser)return;
    if(!activeId){const u=users.find(x=>x.user_id===activeUser);activeId=crypto.randomUUID?crypto.randomUUID():`local-${Date.now()}`;const now=new Date().toISOString();saveChat({id:activeId,userId:activeUser,userName:u?.name||'',databaseId:currentDatabaseId(),title:titleFor(text),createdAt:now,updatedAt:now,messages:[]})}
    const chats=userChats(),chat=chats.find(x=>x.id===activeId);if(!chat)return;
    input.value='';input.style.height='auto';chat.title=chat.messages.length?chat.title:titleFor(text);chat.updatedAt=new Date().toISOString();chat.messages.push({role:'user',text});saveChat(chat);showChat(chat);
    const pending=appendMessage({role:'assistant',thinking:true});busy=true;$('#send-button').disabled=true;input.disabled=true;
    try{
      const response=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json','Accept':'application/json'},body:JSON.stringify({userId:activeUser,conversationId:activeId,message:text})});
      let payload={};try{payload=await response.json()}catch{}
      if(!response.ok)throw new Error(payload.message||payload.error||`Request failed (${response.status})`);
      if(payload.conversationId&&payload.conversationId!==activeId){activeId=payload.conversationId;chat.id=activeId}
      pending.remove();chat.messages.push({role:'assistant',payload});chat.updatedAt=new Date().toISOString();saveChat(chat);appendMessage({role:'assistant',payload});setConnection(true,'Connected to field data');
    }catch(error){pending.remove();appendMessage({role:'assistant',error:error.message||'The copilot could not complete this request.'});console.error(error)}
    finally{busy=false;$('#send-button').disabled=false;input.disabled=false;input.focus()}
  }

  form.addEventListener('submit',e=>{e.preventDefault();submitQuestion()});
  input.addEventListener('input',()=>{input.style.height='auto';input.style.height=`${Math.min(input.scrollHeight,130)}px`});
  input.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();submitQuestion()}});
  $('#new-chat').addEventListener('click',createChat);
  $('#import-database').addEventListener('click',()=>$('#database-file').click());
  $('#edit-database').addEventListener('click',openEditorDialog);
  $('#database-file').addEventListener('change',e=>importDatabase(e.target.files?.[0]));
  $('#database-picker').addEventListener('change',e=>switchDatabase(e.target.value));
  $('#database-history').addEventListener('click',showDatabaseHistory);
  $('#field-overview').addEventListener('click',()=>submitQuestion('Give me a current overview of well status across the field.'));
  document.querySelectorAll('[data-prompt]').forEach(b=>b.addEventListener('click',()=>submitQuestion(b.dataset.prompt)));
  picker.addEventListener('change',async()=>{if(busy){picker.value=activeUser;showToast('Wait for the current answer to finish before switching users.');return}activeUser=picker.value;localStorage.setItem(USER_STORE,activeUser);updateUserBadge();activeId=null;showChat(null);await loadConversationHistory();$('#sidebar').classList.remove('open')});
  $('#mobile-menu').addEventListener('click',()=>$('#sidebar').classList.toggle('open'));
  document.addEventListener('click',e=>{if(window.innerWidth<=680&&$('#sidebar').classList.contains('open')&&!$('#sidebar').contains(e.target)&&!e.target.closest('#mobile-menu'))$('#sidebar').classList.remove('open')});
  document.addEventListener('keydown',e=>{if((e.metaKey||e.ctrlKey)&&e.key.toLowerCase()==='k'){e.preventDefault();createChat()}});
  loadDatabases().finally(loadUsers);
});
