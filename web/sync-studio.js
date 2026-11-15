'use strict';
const C=SyncCore;
let cameras=[],imu=null,csv=null,imuFile='',appliedMapping=null,time=0,playing=false,starting=false,generation=0,range={start:0,end:0},viewStart=0;
let sessions=[],activeSession=null,clockOrigin=0,clockTime=0,trackDrag=null;
const axes=['ax','ay','az','gx','gy','gz'];
$('values').innerHTML=axes.map((a,i)=>`<div class="readout"><small class="${['x','y','z'][i%3]}">${a.toUpperCase()}</small><span id="v${i}">—</span></div>`).join('');
function message(s){$('message').textContent=s;}
function fps(){const n=Number($('fps').value);return Number.isFinite(n)&&n>0?n:120;}
function pause(){generation++;playing=false;starting=false;for(const c of cameras)c.video.pause();$('play').textContent='▶ Play';}
function usable(){return cameras.filter(c=>c.enabled&&c.ready&&!c.failed);}
function recalc(){
 const vs=usable();range={start:0,end:imu?imu.duration:Math.max(0,...vs.map(c=>(c.video.duration-c.offset)/c.scale))};
 const valid=Number.isFinite(range.end)&&range.end>range.start;
 for(const id of ['play','prev','next','timeline'])$(id).disabled=!valid;
 $('start').textContent='0.000 s';$('end').textContent=valid?range.end.toFixed(3)+' s':'No recordings loaded';
 $('timeline').min=0;$('timeline').max=valid?range.end:1;
 if(valid)seek(Math.min(range.end,Math.max(0,time)));else render();if(activeSession)saveSession();
}
function cameraTime(c,t=time){return t*c.scale+c.offset;}
function covered(c,t=time){return c.enabled&&c.ready&&!c.failed&&cameraTime(c,t)>=0&&cameraTime(c,t)<c.video.duration;}
function seek(t){
 pause();time=Math.max(0,Math.min(range.end,t));
 for(const c of cameras)c.previewing=false;for(const c of usable()){const target=Math.max(0,Math.min(c.video.duration,cameraTime(c)));if(Math.abs(c.video.currentTime-target)>.0001)c.video.currentTime=target;}
 render();
}
function settle(video){return new Promise((resolve,reject)=>{
 if(!video.seeking&&video.readyState>=2)return resolve();let timer;
 const clean=()=>{clearTimeout(timer);for(const n of ['seeked','loadeddata','error'])video.removeEventListener(n,finish);};
 const finish=e=>{if(e.type==='error'){clean();reject(Error('Video decoding failed.'));}else if(!video.seeking&&video.readyState>=2){clean();resolve();}};
 for(const n of ['seeked','loadeddata','error'])video.addEventListener(n,finish);
 timer=setTimeout(()=>{clean();reject(Error('Video is taking too long to decode.'));},8000);
});}
async function play(){
 if(playing||starting){pause();return;}if($('play').disabled)return;
 if(time>=range.end-.001)seek(0);
 const token=++generation;starting=true;$('play').textContent='Loading…';
 try{
  for(const c of cameras)c.previewing=false;const active=usable().filter(c=>covered(c));
  await Promise.all(active.map(c=>settle(c.video)));if(token!==generation)return;
  for(const c of active)c.video.playbackRate=Number($('speed').value)*c.scale;
  await Promise.all(active.map(c=>c.video.play()));
  if(token!==generation){if(!playing&&!starting)for(const c of cameras)c.video.pause();return;}
  clockOrigin=performance.now();clockTime=time;starting=false;playing=true;$('play').textContent='Ⅱ Pause';
  message('Playing the shared watch timeline. Clips enter and leave at their aligned positions; recording gaps remain empty.');schedule();
 }catch(e){if(token!==generation)return;pause();message('Playback stopped: '+e.message+' Try a browser-compatible MP4/H.264 copy if needed.');}
}
function schedule(){const token=generation;requestAnimationFrame(now=>{if(playing&&token===generation)tick(now);});}
function tick(now){
 if(!playing)return;
 const master=usable().find(c=>covered(c)&&!c.video.paused&&!c.video.seeking);
 if(master){time=(master.video.currentTime-master.offset)/master.scale;clockTime=time;clockOrigin=now;}
 else time=clockTime+(now-clockOrigin)/1000*Number($('speed').value);
 if(time>=range.end){seek(range.end);return;}
 for(const c of usable()){
  if(!covered(c)){if(!c.video.paused)c.video.pause();continue;}
  const target=cameraTime(c),drift=target-c.video.currentTime,base=Number($('speed').value)*c.scale;
  if(c===master){c.video.playbackRate=base;continue;}
  if(Math.abs(drift)>.04&&!c.video.seeking)c.video.currentTime=target;
  c.video.playbackRate=base*Math.max(.98,Math.min(1.02,1+drift*.5));
  if(c.video.paused&&!c.playPending){const token=generation;c.playPending=true;c.video.play().then(()=>{if(token!==generation||!covered(c))c.video.pause();}).catch(e=>{if(token===generation){pause();message('Could not play '+c.name+': '+e.message);}}).finally(()=>c.playPending=false);}
 }
 render();schedule();
}
function addVideo(name,src,owned=false,identity=src){
 if(cameras.some(c=>c.key===identity)){message('That video is already loaded.');return;}pause();const c={name,src,key:identity,owned,offset:0,scale:1,enabled:true,anchors:[],alignmentMode:"none",ready:false,failed:false};cameras.push(c);
 const el=document.createElement('article');el.className='camera';
 el.innerHTML='<div class="head"><div class="name"></div><button class="remove" aria-label="Remove camera">×</button></div><div class="video-stage"><video playsinline muted preload="metadata"></video><div class="no-clip" hidden>No video at this time</div></div><div class="controls"><div class="row"><label>Preview / s <input class="preview" type="number" step="0.008333" value="0" min="0"></label><button class="back" aria-label="Previous preview frame">←</button><button class="forward" aria-label="Next preview frame">→</button><button class="align">Align here</button></div><div class="status">Loading metadata…</div></div><details class="camera-settings"><summary>Camera alignment settings</summary><div class="row"><label><input class="included" type="checkbox" checked> Use in this recording</label><label>Offset / s <input class="offset" type="number" step="0.0001" value="0"></label><label>Clock drift / ppm <input class="ppm" type="number" min="-100000" max="100000" step="1" value="0"></label><button class="anchor1">Anchor 1</button><button class="anchor2">Anchor 2 / fit drift</button></div></details>';
 c.el=el;c.video=el.querySelector('video');c.status=el.querySelector('.status');el.querySelector('.name').textContent=name;el.querySelector('.name').title=name;
 el.querySelector('.remove').onclick=()=>{pause();c.video.removeAttribute('src');c.video.load();if(c.owned)URL.revokeObjectURL(c.src);if(c.frameRequest&&c.video.cancelVideoFrameCallback)c.video.cancelVideoFrameCallback(c.frameRequest);cameras=cameras.filter(v=>v!==c);updateFocusOptions();el.remove();c.track?.remove();$('empty').hidden=!!cameras.length;recalc();};
 c.video.addEventListener('loadedmetadata',()=>{if(!Number.isFinite(c.video.duration)||c.video.duration<=0){c.failed=true;message('Video has no finite duration: '+name);return;}c.ready=true;el.querySelector('.preview').max=c.video.duration;recalc();});
 c.video.addEventListener('error',()=>{pause();c.failed=true;c.status.textContent='Cannot decode this video. Remove it or use an MP4/H.264 copy.';message('Video failed: '+name);recalc();});
 c.video.addEventListener('waiting',()=>{if(playing&&covered(c)&&!c.video.seeking){pause();message('Playback paused for video buffering. Press Play when ready.');}});
 c.video.addEventListener('ended',()=>{if(!playing)updateCamera(c);});
 c.video.addEventListener('seeking',()=>c.presentedPTS=null);
 c.video.addEventListener('seeked',()=>{if(!playing){el.querySelector('.preview').value=c.video.currentTime.toFixed(6);updateCamera(c);}});
 el.querySelector('.offset').oninput=el.querySelector('.offset').onchange=e=>{if(!e.target.value.trim())return;const n=Number(e.target.value);if(!Number.isFinite(n))return;pause();c.alignmentMode="manual";c.offset=n;recalc();};
 el.querySelector('.included').onchange=e=>{pause();c.alignmentMode="manual";c.enabled=e.target.checked;recalc();};
 el.querySelector('.ppm').onchange=e=>{const ppm=Number(e.target.value);if(!Number.isFinite(ppm)||Math.abs(ppm)>100000){e.target.value=(c.scale-1)*1e6;message('Clock drift must be within ±100000 ppm.');return;}pause();c.alignmentMode="manual";c.scale=1+ppm/1e6;recalc();};
 el.querySelector('.anchor1').onclick=()=>{pause();if(!c.ready)return;c.alignmentMode="manual";c.anchors=[{session:time,video:c.video.currentTime}];message('Anchor 1 saved. Seek to a later shared event, preview its video frame, then use Anchor 2.');};
 el.querySelector('.anchor2').onclick=()=>{pause();if(!c.ready||!c.anchors.length){message('Save Anchor 1 first.');return;}try{const second={session:time,video:c.video.currentTime};const fit=C.fitAlignment(c.anchors[0],second);c.alignmentMode="manual";c.offset=fit.offset;c.scale=fit.scale;c.anchors[1]=second;el.querySelector('.ppm').value=((c.scale-1)*1e6).toFixed(3);el.querySelector('.offset').value=c.offset.toFixed(6);recalc();message('Two-event clock fit applied to this camera for the selected session.');}catch(e){message(e.message);}};
 function preview(t){pause();if(!c.ready)return;c.previewing=true;c.el.querySelector('.no-clip').hidden=true;c.video.currentTime=Math.max(0,Math.min(c.video.duration,t));}
 el.querySelector('.preview').onchange=e=>preview(Number(e.target.value)||0);
 el.querySelector('.back').onclick=()=>preview(c.frameTimes?C.frameStep(c.frameTimes,c.video.currentTime,-1):c.video.currentTime-1/fps());el.querySelector('.forward').onclick=()=>preview(c.frameTimes?C.frameStep(c.frameTimes,c.video.currentTime,1):c.video.currentTime+1/fps());
 el.querySelector('.align').onclick=()=>{if(!c.ready)return;pause();c.alignmentMode="manual";c.offset=c.video.currentTime-time*c.scale;el.querySelector('.offset').value=c.offset.toFixed(6);recalc();message('Aligned '+name+' to the session playhead.');};
 $('videos').append(el);$('empty').hidden=true;restoreCamera(c);updateFocusOptions();addTrack(c);c.video.src=src;recalc();
 if(c.video.requestVideoFrameCallback){const observe=()=>{if(!cameras.includes(c))return;c.frameRequest=c.video.requestVideoFrameCallback((_,meta)=>{c.presentedPTS=meta.mediaTime;if(!playing)updateCamera(c);observe();});};observe();}
 if(!owned&&/^IMG_330[234]\.MOV$/i.test(name))fetch('recordings/timing-audit/'+name.replace(/\.MOV$/i,'-frames.json')).then(r=>r.ok?r.json():null).then(data=>{if(data){c.frameTimes=Float64Array.from(data.frames.map(f=>f.pts_s));updateCamera(c);}}).catch(()=>{});
}
function updateCamera(c){
 if(!c.ready||c.failed)return;
 const drift=(c.video.currentTime-cameraTime(c))*1000;
 const hasVideo=covered(c);
 c.el.querySelector('.no-clip').hidden=hasVideo||c.previewing;
 const frame=c.frameTimes?Math.max(0,C.lowerBound(c.frameTimes,c.video.currentTime+1e-7)-1):null;
 c.status.textContent=`${frame===null?'':('frame '+frame+' · PTS '+c.frameTimes[frame].toFixed(6)+' s · ')}${c.previewing?'PREVIEW':!c.enabled?'EXCLUDED':!hasVideo?'OUTSIDE CLIP':'ALIGNED'} · video ${c.video.currentTime.toFixed(3)} s${hasVideo?' · Δ '+drift.toFixed(1)+' ms':''}`;
 if(playing)c.el.querySelector('.preview').value=c.video.currentTime.toFixed(4);
}
function render(){
 $('clock').replaceChildren(document.createTextNode(time.toFixed(3)+' s'));$('timeline').value=time;if(document.activeElement!==$('timeEntry'))$('timeEntry').value=time.toFixed(6);renderFocus();
 for(const c of cameras)updateCamera(c);
 if(imu){const i=C.nearest(imu.times,time);const delta=Math.abs(imu.times[i]-time);if(delta>.02){for(let j=0;j<6;j++)$('v'+j).textContent='—';$('sampleIndex').textContent='GAP';$('sampleTime').textContent='No IMU sample within 20 ms of this time.';}else{imu.values[i].forEach((v,j)=>$('v'+j).textContent=v.toFixed(3));$('sampleIndex').textContent='#'+i.toLocaleString();$('sampleTime').textContent=`Sample ${imu.times[i].toFixed(6)} s · Δ ${((imu.times[i]-time)*1000).toFixed(2)} ms`;}}
 draw($('acc'),0);draw($('gyro'),3);renderTracks();renderAnnotations();
}
function draw(canvas,axis){
 const rect=canvas.getBoundingClientRect(),dpr=devicePixelRatio||1,w=rect.width,h=rect.height;if(!w)return;
 if(canvas.width!==Math.round(w*dpr)||canvas.height!==Math.round(h*dpr)){canvas.width=Math.round(w*dpr);canvas.height=Math.round(h*dpr);}
 const ctx=canvas.getContext('2d');ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,w,h);ctx.font='10px ui-monospace,monospace';
 if(!imu){ctx.fillStyle='#96a3b0';ctx.fillText('Load an IMU CSV to see sensor readings here.',16,h/2);return;}
 const span=Number($('window').value);viewStart=Math.max(0,Math.min(Math.max(0,imu.duration-span),time-span/2));const end=viewStart+span;
 const lo=C.lowerBound(imu.times,viewStart),hi=C.lowerBound(imu.times,end);let min=Infinity,max=-Infinity;
 for(let i=lo;i<hi;i++)for(let a=axis;a<axis+3;a++){min=Math.min(min,imu.values[i][a]);max=Math.max(max,imu.values[i][a]);}
 if(!Number.isFinite(min)){min=-1;max=1;}const pad=(max-min)*.1||1;min-=pad;max+=pad;
 const left=55,right=w-8,top=14,bottom=h-25,px=t=>left+(t-viewStart)/span*(right-left),py=v=>bottom-(v-min)/(max-min)*(bottom-top);
 ctx.strokeStyle='#27313a';ctx.fillStyle='#96a3b0';for(let n=0;n<4;n++){const y=top+n*(bottom-top)/3;ctx.beginPath();ctx.moveTo(left,y);ctx.lineTo(right,y);ctx.stroke();ctx.fillText((max-n*(max-min)/3).toFixed(1),2,y+3);}
 const ticks=Math.max(1,Math.min(4,Math.floor((right-left)/75)));for(let n=0;n<=ticks;n++){ctx.textAlign=n===0?'left':n===ticks?'right':'center';ctx.fillText((viewStart+span*n/ticks).toFixed(2)+'s',left+(right-left)*n/ticks,h-5);}ctx.textAlign='left';
 const count=Math.max(1,Math.floor(right-left));
 for(let a=0;a<3;a++){
  ctx.strokeStyle=['#78b6ff','#b7f56b','#f49caf'][a];ctx.lineWidth=1;ctx.beginPath();let i=lo;
  for(let p=0;p<count&&i<hi;p++){const until=viewStart+span*(p+1)/count;let low=Infinity,high=-Infinity,first=i;while(i<hi&&imu.times[i]<until){const v=imu.values[i][axis+a];low=Math.min(low,v);high=Math.max(high,v);i++;}if(i>first){const x=left+p;if(i-first===1){if(first===lo||imu.times[first]-imu.times[first-1]>Math.max(.02,5/imu.localHz))ctx.moveTo(x,py(low));else ctx.lineTo(x,py(low));}else{ctx.moveTo(x,py(low));ctx.lineTo(x,py(high));}}}
  ctx.stroke();
 }
 ctx.save();ctx.setLineDash([3,3]);for(const r of annotationState().resolved){if(r.time_s<viewStart||r.time_s>end)continue;ctx.strokeStyle={swing:'#b7f56b','not-swing':'#f49caf',uncertain:'#ffc180',sync:'#78b6ff'}[r.marker.kind];ctx.beginPath();ctx.moveTo(px(r.time_s),top);ctx.lineTo(px(r.time_s),bottom);ctx.stroke();}ctx.restore();
 if(time>=viewStart&&time<=end){ctx.strokeStyle='#ffffff';ctx.beginPath();ctx.moveTo(px(time),top);ctx.lineTo(px(time),bottom);ctx.stroke();}
}
function trackDomain(){
 if(trackDrag)return trackDrag.domain;
 const span=Number($('trackZoom').value);
 if(span){const start=Math.max(0,time-span/2);return {start,end:start+span};}
 return {start:Math.min(0,...cameras.filter(c=>c.enabled&&c.ready&&!c.failed).map(c=>-c.offset/c.scale)),end:Math.max(1,imu?.duration||0,...cameras.filter(c=>c.enabled&&c.ready&&!c.failed).map(c=>(c.video.duration-c.offset)/c.scale))};
}
function moveClip(c,seconds){c.alignmentMode="manual";c.offset=C.shiftOffset(c.offset,c.scale,seconds);c.anchors=[];c.el.querySelector('.offset').value=c.offset.toFixed(6);}
function addTrack(c){
 const row=document.createElement('div');row.className='track-row';const name=document.createElement('div');name.className='track-name';name.textContent=c.name;
 const lane=document.createElement('div');lane.className='track-lane';const clip=document.createElement('button');clip.className='video-clip';clip.textContent=c.name;clip.setAttribute('aria-label','Move '+c.name+' on timeline; arrow keys move one frame, Shift moves 100 ms');
 const cursor=document.createElement('span');cursor.className='track-cursor';lane.append(clip,cursor);row.append(name,lane);$('cameraTracks').append(row);c.track=row;c.clip=clip;c.lane=lane;
 clip.onpointerdown=e=>{if(!c.ready||e.button!==0)return;e.preventDefault();pause();clip.focus();const domain=trackDomain();trackDrag={camera:c,x:e.clientX,offset:c.offset,domain,width:lane.getBoundingClientRect().width};clip.setPointerCapture(e.pointerId);};
 clip.onpointermove=e=>{if(trackDrag?.camera!==c)return;c.alignmentMode="manual";const d=trackDrag;const seconds=(e.clientX-d.x)/d.width*(d.domain.end-d.domain.start);c.offset=C.shiftOffset(d.offset,c.scale,seconds);c.anchors=[];c.el.querySelector('.offset').value=c.offset.toFixed(6);render();};
 const finish=()=>{if(trackDrag?.camera!==c)return;trackDrag=null;recalc();saveSession();message(c.name+' moved on the sensor timeline. Fine-tune with arrow keys or Preview / Align here.');};
 clip.onpointerup=finish;clip.onpointercancel=finish;
 clip.onkeydown=e=>{if(!['ArrowLeft','ArrowRight'].includes(e.key))return;e.preventDefault();pause();moveClip(c,(e.key==='ArrowRight'?1:-1)*(e.shiftKey ? .1 : 1/fps()/c.scale));recalc();saveSession();};
 lane.onclick=e=>{if(e.target===lane){const r=lane.getBoundingClientRect(),d=trackDomain();seek(d.start+(e.clientX-r.left)/r.width*(d.end-d.start));}};
}
function renderTracks(){
 const d=trackDomain(),span=d.end-d.start,toPct=t=>(t-d.start)/span*100;
 $('trackFrom').textContent=d.start.toFixed(2)+' s';$('trackTo').textContent=d.end.toFixed(2)+' s';
 const imuLane=$('imuTrack');imuLane.replaceChildren();
 if(imu)for(const segment of imu.segments||[]){const el=document.createElement('span');el.className='imu-segment';el.style.left=toPct(segment.start)+'%';el.style.width=(segment.end-segment.start)/span*100+'%';el.title=segment.name;el.textContent=segment.name;imuLane.append(el);}
 const cursor=document.createElement('span');cursor.className='track-cursor';cursor.style.left=toPct(time)+'%';imuLane.append(cursor);
 for(const c of cameras){if(!c.clip)continue;c.track.hidden=!c.enabled;const start=-c.offset/c.scale;c.clip.style.left=toPct(start)+'%';c.clip.style.width=(c.ready?c.video.duration/c.scale/span*100:0)+'%';c.clip.disabled=!c.ready;c.clip.style.opacity=c.enabled?'1':'.35';c.clip.title=`${c.name}: starts at ${start.toFixed(3)} s; drag to move, arrows for frame nudging`;c.lane.querySelector('.track-cursor').style.left=toPct(time)+'%';}
}
$('trackZoom').onchange=renderTracks;
$('imuTrack').onclick=e=>{const r=$('imuTrack').getBoundingClientRect(),d=trackDomain();seek(d.start+(e.clientX-r.left)/r.width*(d.end-d.start));};
function mapping(){
 const headers=csv.headers;$('columns').replaceChildren();
 const guesses=[['time','Timestamp',['t_us','timestamp_us','time_us','t_ms','timestamp_ms','time_ms','timestamp','time','t_s','time_s']],...axes.map(a=>[a,a.toUpperCase(),[a+'_g',a+'_dps',a,{ax:'accel_x',ay:'accel_y',az:'accel_z',gx:'gyro_x',gy:'gyro_y',gz:'gyro_z'}[a]]])];
 for(const [id,label,names]of guesses){const line=document.createElement('label');line.className='field';line.append(document.createTextNode(label));const select=document.createElement('select');select.id='map-'+id;select.add(new Option(id==='time'?'Row index / rate':'Select column','-1'));headers.forEach((h,i)=>select.add(new Option(h,String(i))));const found=names.map(n=>headers.findIndex(h=>h.toLowerCase()===n)).find(i=>i>=0);select.value=String(found??-1);line.append(select);$('columns').append(line);}
 const label=document.createElement('label');label.className='field';label.textContent='Timestamp units';const units=document.createElement('select');units.id='units';for(const u of ['s','ms','us','ns'])units.add(new Option(u,u));const h=headers[Number($('map-time').value)]||'';units.value=/_ns$/i.test(h)?'ns':/_us$/i.test(h)?'us':/_ms$/i.test(h)?'ms':'s';label.append(units);$('columns').append(label);$('mapping').classList.add('visible');
}
function saveSession(){if(!activeSession)return;activeSession.time=time;activeSession.cameras={...activeSession.cameras,...Object.fromEntries(cameras.map(c=>[c.key,{offset:c.offset,scale:c.scale,enabled:c.enabled,alignmentMode:c.alignmentMode,anchors:c.anchors.map(a=>({...a}))}]))};persistAnnotations();}
function switchSession(session){
 pause();saveSession();resetMarkerForm();selectedMarker=null;deletedMarker=null;$('undoMarker').hidden=true;markerRevision++;markerCache=null;activeSession=session;imu=session.data;imuFile=session.name;appliedMapping=session.mapping;time=session.time||0;
 for(const c of cameras){const state=session.cameras[c.key]||{offset:0,scale:1,enabled:true,anchors:[],alignmentMode:"none"};Object.assign(c,state);c.el.querySelector('.offset').value=c.offset.toFixed(6);c.el.querySelector('.ppm').value=((c.scale-1)*1e6).toFixed(3);c.el.querySelector('.included').checked=c.enabled;}
 $('sessions').replaceChildren(...sessions.map((s,i)=>new Option(s.name,String(i))));$('sessions').value=String(sessions.indexOf(session));
 $('imuName').textContent=imuFile;$('imuStats').textContent=`${imu.times.length.toLocaleString()} rows · ${imu.localHz.toFixed(1)} Hz local · ${imu.hz.toFixed(1)} rows/s overall · ${imu.duration.toFixed(3)} s`;
 $('signal').disabled=!imu.rawValues;setSignal();recalc();if(typeof refreshLibrary==='function')refreshLibrary();
}
function setSignal(){if(!imu)return;imu.values=$('signal').value==='raw'&&imu.rawValues?imu.rawValues:imu.processedValues;render();}
function registerSession(name,data,map,sourceCSV){
 data.processedValues=data.values;
 const sessionColumn=sourceCSV.headers.indexOf('session_id');data.segments=[];
 if(sessionColumn>=0){let start=0;for(let i=1;i<=sourceCSV.rows.length;i++){if(i===sourceCSV.rows.length||sourceCSV.rows[i][sessionColumn]!==sourceCSV.rows[start][sessionColumn]){data.segments.push({name:sourceCSV.rows[start][sessionColumn],start:data.times[start],end:data.times[i-1]});start=i;}}}
 else data.segments=[{name,start:0,end:data.duration}];
 const rawAxes=['ax_g','ay_g','az_g','gx_dps','gy_dps','gz_dps'].map(a=>sourceCSV.headers.indexOf('raw_'+a));
 if(rawAxes.every(i=>i>=0))data.rawValues=C.buildIMU(sourceCSV,map.timestampColumn,map.timestampUnits,rawAxes,Number($('hz').value)).values;
 data.originUs=map.timestampColumn>=0?Number(sourceCSV.rows[0][map.timestampColumn])*({s:1e6,ms:1e3,us:1,ns:.001}[map.timestampUnits]):null;
 const sequenceColumn=sourceCSV.headers.indexOf('seq');data.sequences=sequenceColumn>=0?sourceCSV.rows.map(row=>row[sequenceColumn]):null;
 const session={name,data,mapping:map,time:0,cameras:{}};initializeAnnotations(session);sessions.push(session);switchSession(session);
}
function apply(){try{const map={timestampColumn:Number($('map-time').value),timestampUnits:$('units').value,axes:axes.map(a=>Number($('map-'+a).value)),headers:csv.headers};const data=C.buildIMU(csv,map.timestampColumn,map.timestampUnits,map.axes,Number($('hz').value));registerSession(pendingName,data,map,csv);$('mapping').classList.remove('visible');message('Watch session added. Select a session and align its cameras.');}catch(e){message(e.message);}}
let pendingName='';
function loadStandard(name,text){
 const source=C.parseCSV(text),names=['ax_g','ay_g','az_g','gx_dps','gy_dps','gz_dps'];
 const map={timestampColumn:source.headers.indexOf('t_us'),timestampUnits:'us',axes:names.map(a=>source.headers.indexOf(a)),headers:source.headers};
 if(map.timestampColumn<0||map.axes.some(i=>i<0))throw Error('Needs column mapping');
 const data=C.buildIMU(source,map.timestampColumn,map.timestampUnits,map.axes,Number($('hz').value));registerSession(name,data,map,source);
}
$('sessions').onchange=e=>{const session=sessions[Number(e.target.value)];if(session)switchSession(session);};$('signal').onchange=setSignal;
$('csvFile').onchange=async e=>{const files=[...e.target.files];pause();for(const f of files){try{const text=await f.text();try{loadStandard(f.name,text);message('Watch sessions loaded. Choose a session to align with the videos.');}catch(err){if(files.length>1){message(f.name+': '+err.message+'. Load this file individually to map its columns.');continue;}csv=C.parseCSV(text);pendingName=f.name;mapping();message('Review the timestamp units and six column mappings, then Apply.');}}catch(err){message(err.message);}}e.target.value='';};
$('watchSamples').onclick=async()=>{pause();$('watchSamples').disabled=true;try{
 const name='watch-combined-filtered.csv';const existing=sessions.find(s=>s.name===name);if(existing){switchSession(existing);return;}
 const response=await fetch('recordings/stitched/'+name);if(!response.ok)throw Error('Stitched sample missing');
 loadStandard(name,await response.text());message('Combined watch timeline loaded: both sessions, original timestamps and the 23.391 s gap.');
}catch(e){message('Use the local HTTP server for sample loading, or select recordings/stitched/watch-combined-filtered.csv. '+e.message);}finally{$('watchSamples').disabled=false;}};
$('apply').onclick=apply;$('hz').onchange=()=>{if(csv)message('Click Apply column mapping to use the updated fallback rate.');};
$('videoFiles').onchange=e=>{for(const f of e.target.files)addVideo(f.name,URL.createObjectURL(f),true,f.name+'|'+f.size+'|'+f.lastModified);e.target.value='';};
$('reviewPlacement').onclick=()=>{
 if(!imuFile.includes('watch-combined')){message('Load stitched watch data first, then load the sample videos.');return;}
 pause();let count=0;
 const starts={'IMG_3302.MOV':-32.1,'IMG_3303.MOV':.9,'IMG_3304.MOV':95.377595};
 for(const c of cameras){if(!(c.name in starts))continue;c.alignmentMode='manual';c.scale=1;c.offset=-starts[c.name];c.enabled=c.name!=='IMG_3302.MOV';c.anchors=[];c.el.querySelector('.offset').value=c.offset.toFixed(6);c.el.querySelector('.ppm').value='0';c.el.querySelector('.included').checked=c.enabled;count++;}
 recalc();saveSession();message(count?'Rough reviewed placement applied. IMG_3303 ≈ first session; IMG_3304 ≈ second. IMG_3302 appears earlier. Drag to refine; these are unverified starting offsets, not frame-accurate sync.':'Load the sample videos first.');
};
$('samples').onclick=()=>{for(const name of ['IMG_3302.MOV','IMG_3303.MOV','IMG_3304.MOV'])if(!cameras.some(c=>c.src==='samples/videos/'+name))addVideo(name,'samples/videos/'+name);};
function stepFrame(direction){const c=usable().find(c=>covered(c));if(c?.frameTimes){const target=C.frameStep(c.frameTimes,cameraTime(c),direction);seek((target-c.offset)/c.scale);}else{seek(time+direction/fps()/(c?.scale||1));message('Frame step uses configured fps here; no audited video frame timestamps at this time.');}}
$('play').onclick=play;$('prev').onclick=()=>stepFrame(-1);$('next').onclick=()=>stepFrame(1);$('timeline').oninput=e=>seek(Number(e.target.value));$('speed').onchange=()=>{if(playing){clockTime=time;clockOrigin=performance.now();}for(const c of usable())c.video.playbackRate=Number($('speed').value)*c.scale;};$('window').onchange=render;
for(const id of ['acc','gyro'])$(id).onclick=e=>{if(!imu)return;const r=e.currentTarget.getBoundingClientRect();seek(viewStart+Math.max(0,Math.min(1,(e.clientX-r.left-55)/(r.width-63)))*Number($('window').value));};
$('export').onclick=()=>{saveSession();const data={version:3,formula:'video_seconds = session_seconds * scale + offset_seconds',videoFrameRate:fps(),sessions:sessions.map(s=>({name:s.name,mapping:s.mapping,markers:s.markers||[],videos:cameras.map(c=>({name:c.name,...(s.cameras[c.key]||{offset:0,scale:1,enabled:true,anchors:[]})}))})),activeSession:imuFile,overlap:range};const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='vector-alignment.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
new ResizeObserver(render).observe($('acc').parentElement);document.addEventListener('visibilitychange',()=>{if(document.hidden&&(playing||starting)){pause();message('Paused while the tab is hidden.');}});render();
