'use strict';
const $=id=>document.getElementById(id),MC=MarkerCore,MARKER_STORAGE='vector.sync.annotations.v1';
let annotationStore={},storageReadError=false,markerRevision=0,markerCache=null,listKey='',editingMarker=null,selectedMarker=null,deletedMarker=null;
try{const saved=localStorage.getItem(MARKER_STORAGE);if(saved){const parsed=JSON.parse(saved);if(parsed.version!==1||!parsed.projects||typeof parsed.projects!=='object')throw Error('Invalid saved annotations');annotationStore=parsed.projects;}}catch(_){storageReadError=true;}
function safeCameraState(state){if(!state||!Number.isFinite(state.offset)||!Number.isFinite(state.scale)||state.scale<.9||state.scale>1.1||typeof state.enabled!=='boolean')return null;return {offset:state.offset,scale:state.scale,enabled:state.enabled,alignmentMode:state.alignmentMode||"manual",anchors:Array.isArray(state.anchors)?state.anchors.filter(a=>Number.isFinite(a.session)&&Number.isFinite(a.video)).slice(0,2).map(a=>({...a})):[]};}
function initializeAnnotations(session){
 session.annotationKey=MC.projectKey(session.name,session.data);session.markers=[];
 const saved=annotationStore[session.annotationKey]||Object.values(annotationStore).find(p=>MC.sameProject(p.recording_key,session.annotationKey));
 if(saved){try{session.markers=structuredClone(MC.validateProject(saved,session.annotationKey));session.cameras={};for(const [key,value]of Object.entries(saved.cameras||{})){const state=safeCameraState(value);if(state)session.cameras[key]=state;}}catch(_){$('markerStorage').textContent='Saved labels could not be validated. Existing browser data was preserved; import a JSON backup to recover.';}}
}
function persistAnnotations(){
 if(!activeSession?.annotationKey)return;
 const project={version:1,recording_key:activeSession.annotationKey,recording_name:activeSession.name,markers:activeSession.markers||[],cameras:activeSession.cameras||{}};
 annotationStore[activeSession.annotationKey]=project;
 if(storageReadError){$('markerStorage').textContent='Existing browser storage could not be read and was left untouched. Export markers JSON to keep this work.';return;}
 try{localStorage.setItem(MARKER_STORAGE,JSON.stringify({version:1,projects:annotationStore}));$('markerStorage').textContent='Saved locally in this browser, including camera alignment. Export markers JSON for backup or another browser.';}catch(_){$('markerStorage').textContent='Browser storage is unavailable or full. Export markers JSON before closing this page.';}
}
function annotationsChanged(){markerRevision++;markerCache=null;saveSession();render();}
function annotationState(){
 const key=(activeSession?.annotationKey||'')+'|'+markerRevision+'|'+cameras.map(c=>c.key+':'+c.offset+':'+c.scale).join('|');
 if(markerCache?.key===key)return markerCache;
 const resolved=imu?(activeSession?.markers||[]).map(m=>MC.resolveMarker(m,cameras,imu)).sort((a,b)=>a.time_s-b.time_s):[];
 markerCache={key,resolved};return markerCache;
}
function focusCamera(){const selected=$('cameraFocus').value;if(selected!=='auto'){const c=cameras.find(c=>c.key===selected);if(c)return c;}return cameras.find(c=>c.previewing)||cameras.find(c=>covered(c))||cameras.find(c=>c.enabled&&c.ready&&!c.failed)||cameras[0]||null;}
function updateFocusOptions(){const old=$('cameraFocus').value;$('cameraFocus').replaceChildren(new Option('Auto · active clip','auto'),...cameras.map(c=>new Option(c.name,c.key)));$('cameraFocus').value=cameras.some(c=>c.key===old)?old:'auto';}
function renderFocus(){const selected=focusCamera();for(const c of cameras)c.el.hidden=c!==selected;}
function restoreCamera(c){const state=safeCameraState(activeSession?.cameras?.[c.key]);if(state)Object.assign(c,state);c.el.querySelector('.offset').value=c.offset.toFixed(6);c.el.querySelector('.ppm').value=((c.scale-1)*1e6).toFixed(3);c.el.querySelector('.included').checked=c.enabled;}
function snapshotAnchor(){
 const c=focusCamera();
 if($('anchorVideo').checked){
  if(!c||!c.ready||c.failed||(!covered(c)&&!c.previewing))throw Error('There is no reviewed video at this time. Seek into its clip, or turn off the video anchor to mark an IMU time.');
  if(c.video.seeking)throw Error('Wait for the video frame to finish seeking, then add the marker.');
  let seconds=c.video.currentTime,frame=null,source='video-playhead';
  if(Number.isFinite(c.presentedPTS)){seconds=c.presentedPTS;source='presented-frame';if(c.frameTimes){frame=C.nearest(c.frameTimes,seconds);seconds=c.frameTimes[frame];}}
  else if(c.frameTimes){frame=Math.max(0,C.lowerBound(c.frameTimes,seconds+1e-7)-1);seconds=c.frameTimes[frame];source='audited-pts-at-playhead';}
  const t=(seconds-c.offset)/c.scale;
  if(t<0||t>imu.duration)throw Error('That video frame falls outside the loaded watch timeline. Adjust its alignment or mark an IMU time.');
  return {anchor:{type:'video',video_key:c.key,video_name:c.name,video_s:seconds,frame_index:frame,timing_source:source},alignment_at_creation:{offset:c.offset,scale:c.scale}};
 }
 return {anchor:{type:'imu',time_s:time}};
}
function resetMarkerForm(){editingMarker=null;$('addMarker').textContent='＋ Add marker · M';$('cancelMarker').hidden=true;$('moveMarker').hidden=true;$('markerNotes').value='';}
function addOrUpdateMarker(){
 if(!imu||!activeSession){message('Load sensor data before adding markers.');return;}
 pause();
 try{
  const label=$('markerLabel').value.trim(),kind=$('markerKind').value,notes=$('markerNotes').value.trim();
  if(!label)throw Error('Give this marker a label.');
  if(editingMarker){const marker=activeSession.markers.find(m=>m.id===editingMarker);if(!marker)throw Error('Marker no longer exists.');const updated={...marker,label,kind,notes,updated_at:new Date().toISOString()};MC.validateMarker(updated);Object.assign(marker,updated);selectedMarker=marker.id;resetMarkerForm();annotationsChanged();message('Marker label updated. Its original event anchor was preserved.');return;}
  const captured=snapshotAnchor();const marker={id:crypto.randomUUID(),label,kind,notes,created_at:new Date().toISOString(),...captured};MC.validateMarker(marker);
  const duplicate=activeSession.markers.find(m=>m.kind===kind&&m.label===label&&m.anchor.type===marker.anchor.type&&(m.anchor.type==='video'?m.anchor.video_key===marker.anchor.video_key&&Math.abs(m.anchor.video_s-marker.anchor.video_s)<1e-7:Math.abs(m.anchor.time_s-marker.anchor.time_s)<1e-7));
  if(duplicate){selectedMarker=duplicate.id;listKey='';render();message('This event already has that marker. Use Edit to change its label.');return;}
  activeSession.markers.push(marker);selectedMarker=marker.id;annotationsChanged();message('Marker added at '+MC.resolveMarker(marker,cameras,imu).time_s.toFixed(6)+' s.');
 }catch(e){message(e.message);}
}
function jumpToMarker(r){pause();selectedMarker=r.marker.id;listKey='';if(r.marker.anchor.type==='video'&&cameras.some(c=>c.key===r.marker.anchor.video_key))$('cameraFocus').value=r.marker.anchor.video_key;seek(Math.max(0,Math.min(imu.duration,r.time_s)));if(r.outside)message('This marker falls outside the watch recording with the current video alignment.');}
function editMarker(marker){pause();editingMarker=marker.id;selectedMarker=marker.id;$('markerLabel').value=marker.label;$('markerKind').value=marker.kind;$('markerNotes').value=marker.notes;$('addMarker').textContent='Save marker';$('cancelMarker').hidden=false;$('moveMarker').hidden=false;listKey='';render();$('markerLabel').focus();}
function renderAnnotations(){
 const state=annotationState(),count=state.resolved.length;$('markerCount').textContent=count+' marker'+(count===1?'':'s');$('addMarker').disabled=!imu;$('quickMarker').disabled=!imu;for(const id of ['markersCSV','markersJSON'])$(id).disabled=!imu;
 const currentKey=state.key+'|'+selectedMarker;
 if(listKey!==currentKey){listKey=currentKey;$('markerList').replaceChildren();
  if(!count){const note=document.createElement('p');note.className='note';note.textContent='No labels yet. Mark visible swings, non-swings and uncertain events.';$('markerList').append(note);}
  for(const r of state.resolved){const item=document.createElement('div');item.className='marker-item'+(r.marker.id===selectedMarker?' active':'');const jump=document.createElement('button');jump.type='button';jump.className='marker-jump';const title=document.createElement('strong');title.textContent=r.time_s.toFixed(6)+' s · '+r.marker.label;const kind=document.createElement('span');kind.className='marker-kind '+r.marker.kind;kind.textContent=r.marker.kind;const meta=document.createElement('small');meta.textContent=(r.video_name?r.video_name+' · '+r.video_s.toFixed(6)+' s'+(r.frame_index!==null?' · frame '+r.frame_index:''):'IMU-time anchor')+(r.sample_present?' · row '+r.sample_index:' · IMU GAP')+(r.video_missing?' · saved placement (video not loaded)':'')+(r.outside?' · OUTSIDE RECORDING':'');jump.append(title,kind,meta);jump.onclick=()=>jumpToMarker(r);const edit=document.createElement('button');edit.type='button';edit.textContent='Edit';edit.setAttribute('aria-label','Edit '+r.marker.label+' at '+r.time_s.toFixed(3)+' seconds');edit.onclick=()=>editMarker(r.marker);const remove=document.createElement('button');remove.type='button';remove.textContent='×';remove.setAttribute('aria-label','Delete '+r.marker.label+' at '+r.time_s.toFixed(3)+' seconds');remove.onclick=()=>{deletedMarker={session:activeSession,marker:r.marker};activeSession.markers=activeSession.markers.filter(m=>m.id!==r.marker.id);if(editingMarker===r.marker.id)resetMarkerForm();$('undoMarker').hidden=false;annotationsChanged();message('Marker deleted. Undo is available.');};item.append(jump,edit,remove);$('markerList').append(item);}
 }
 const lane=$('markersTrack');if(lane.dataset.revision!==state.key+'|'+$('trackFrom').textContent+'|'+$('trackTo').textContent){lane.dataset.revision=state.key+'|'+$('trackFrom').textContent+'|'+$('trackTo').textContent;lane.replaceChildren();const domain=trackDomain();for(const r of state.resolved){if(r.time_s<domain.start||r.time_s>domain.end)continue;const pin=document.createElement('button');pin.type='button';pin.className='marker-tick '+r.marker.kind;pin.style.left=(r.time_s-domain.start)/(domain.end-domain.start)*100+'%';pin.title=r.marker.label+' · '+r.time_s.toFixed(6)+' s';pin.setAttribute('aria-label','Jump to marker '+r.marker.label+' at '+r.time_s.toFixed(3)+' seconds');pin.onclick=()=>jumpToMarker(r);lane.append(pin);}}
}
function markerDownload(content,name,type){const url=URL.createObjectURL(new Blob([content],{type}));const link=document.createElement('a');link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
function quickAddMarker(){if(editingMarker){message('Save or cancel the marker edit before adding another event.');return;}addOrUpdateMarker();}
$('quickMarker').onclick=quickAddMarker;
$('markerForm').onsubmit=e=>{e.preventDefault();addOrUpdateMarker();};$('cancelMarker').onclick=resetMarkerForm;
$('moveMarker').onclick=()=>{if(!editingMarker)return;pause();try{const marker=activeSession.markers.find(m=>m.id===editingMarker);if(!marker)throw Error('Marker no longer exists.');const captured=snapshotAnchor();Object.assign(marker,captured);if(!captured.alignment_at_creation)delete marker.alignment_at_creation;annotationsChanged();message('Marker moved to the reviewed event.');}catch(e){message(e.message);}};
$('undoMarker').onclick=()=>{if(!deletedMarker)return;deletedMarker.session.markers.push(deletedMarker.marker);deletedMarker=null;$('undoMarker').hidden=true;annotationsChanged();};
$('markerKind').onchange=()=>{const defaults={swing:'Swing','not-swing':'Not a swing',uncertain:'Uncertain',sync:'Sync reference'};if(Object.values(defaults).includes($('markerLabel').value))$('markerLabel').value=defaults[$('markerKind').value];};
$('markersCSV').onclick=()=>markerDownload(MC.exportCSV(annotationState().resolved),'vector-swing-labels.csv','text/csv');
$('markersJSON').onclick=()=>{saveSession();markerDownload(JSON.stringify(annotationStore[activeSession.annotationKey],null,2),'vector-markers.json','application/json');};
$('markersImport').onchange=async e=>{const f=e.target.files[0];if(!f)return;pause();try{if(!activeSession)throw Error('Load the matching sensor recording first.');if(f.size>8*1024*1024)throw Error('Marker JSON is too large.');const payload=JSON.parse(await f.text());const markers=MC.validateProject(payload,activeSession.annotationKey);const states={};for(const [key,value]of Object.entries(payload.cameras||{})){const state=safeCameraState(value);if(!state)throw Error('Invalid camera alignment in marker file.');states[key]=state;}activeSession.markers=[...new Map([...activeSession.markers,...markers].map(m=>[m.id,m])).values()];Object.assign(activeSession.cameras,states);for(const c of cameras)restoreCamera(c);resetMarkerForm();markerRevision++;markerCache=null;recalc();annotationsChanged();message('Imported '+markers.length+' markers and their saved camera alignment.');}catch(error){message(error.message);}e.target.value='';};
$('cameraFocus').onchange=()=>{renderFocus();render();};
$('timeEntry').oninput=e=>{if(e.target.value!==''&&e.target.validity.valid)seek(Number(e.target.value));};
document.addEventListener('keydown',e=>{const typing=e.target.tagName==='INPUT'&&!['range','checkbox','radio','button','submit'].includes(e.target.type);if(e.defaultPrevented||e.ctrlKey||e.metaKey||e.altKey||e.target.isContentEditable||typing||['SELECT','TEXTAREA'].includes(e.target.tagName))return;if(e.key.toLowerCase()==='m'){if(!e.repeat){e.preventDefault();quickAddMarker();}}else if(e.code==='Space'&&!['BUTTON','A','SUMMARY','INPUT'].includes(e.target.tagName)){e.preventDefault();play();}else if(e.key==='ArrowLeft'||e.key==='ArrowRight'){e.preventDefault();stepFrame(e.key==='ArrowRight'?1:-1);}});
