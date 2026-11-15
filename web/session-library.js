'use strict';
const libraryEntries=[],sidecars=new Map();let csvWorker=null,loadingEntry=null;
const clockFormat=t=>Number.isFinite(t)?new Date(t).toISOString().replace('T',' ').replace('Z',' UTC'):'Unknown UTC';
function entryClock(e){return ImportClock.csvClock(e.firstUs,e.metadata||{},e.name);}
function refreshLibrary(){
 const list=$('libraryList');list.replaceChildren();
 for(const entry of libraryEntries){const button=document.createElement('button');button.className='library-item'+(activeSession?.entry===entry?' selected':'');const title=document.createElement('strong');title.textContent=entry.name;const detail=document.createElement('span');const m=entry.metadata||{};detail.textContent=[clockFormat(entryClock(entry).utcMs),m.durationSeconds?Number(m.durationSeconds).toFixed(1)+' s':(entry.size/1048576).toFixed(1)+' MB',m.receivedSamples?Number(m.receivedSamples).toLocaleString()+' samples':'',m.clippedSamples?m.clippedSamples+' clipped':'',m.outcome||''].filter(Boolean).join(' · ');button.append(title,detail);button.onclick=()=>openEntry(entry);button.disabled=loadingEntry===entry;list.append(button);}
 if(libraryEntries.length){$('sessions').replaceChildren(...libraryEntries.map((e,i)=>new Option(e.name,String(i))));$('sessions').value=String(libraryEntries.indexOf(activeSession?.entry));}
 renderClockEvidence();
}
function renderClockEvidence(){
 const box=$('clockEvidence');box.replaceChildren();
 const csvClock=activeSession?.utcClock;const intro=document.createElement('p');intro.textContent=csvClock?`CSV zero: ${clockFormat(csvClock.utcMs)} · ${csvClock.basis}. ${csvClock.warning||''}`:'Select a recording to inspect its clock and video overlap.';box.append(intro);
 for(const c of cameras){const row=document.createElement('div');row.className='clock-row';const label=document.createElement('strong');label.textContent=c.name;const detail=document.createElement('span');const proposal=csvClock&&c.importMetadata?ImportClock.proposal(csvClock,c.importMetadata,imu.duration):null;detail.textContent=`${c.alignmentMode==='manual'?'MANUAL · saved adjustment':c.alignmentMode==='auto'?'TIMESTAMP ESTIMATE':'MANUAL ALIGNMENT NEEDED'} · ${c.importMetadata?.clock?.basis||c.clockError||'Inspecting metadata…'}${proposal?' · '+proposal.overlap.toFixed(2)+' s overlap · clock proposal offset '+proposal.offset.toFixed(6)+' s':''}${c.importMetadata?' · '+c.importMetadata.frameCount.toLocaleString()+' frames · '+c.importMetadata.averageFps.toFixed(2)+' fps average':''}`;if(c.alignmentMode==='manual')detail.textContent+=' · applied offset '+c.offset.toFixed(6)+' s';const note=document.createElement('small');note.textContent=c.importMetadata?.clock?.warning||'';row.append(label,detail,note);box.append(row);}
}
function alignFromClocks(force=false){
 if(!activeSession?.utcClock)return;
 pause();for(const c of cameras){if(!c.importMetadata||(!force&&c.alignmentMode==='manual'))continue;const placement=ImportClock.proposal(activeSession.utcClock,c.importMetadata,imu.duration);
  if(placement){c.offset=placement.offset;c.scale=1;c.enabled=placement.enabled;c.alignmentMode='auto';c.anchors=[];}
  else if(force||c.alignmentMode!=='manual'){c.offset=0;c.scale=1;c.enabled=false;c.alignmentMode='none';}
  c.el.querySelector('.offset').value=c.offset.toFixed(6);c.el.querySelector('.ppm').value=((c.scale-1)*1e6).toFixed(3);c.el.querySelector('.included').checked=c.enabled;
 }
 recalc();saveSession();renderClockEvidence();
}
function setVideoClock(c){if(!c.importMetadata)return;c.importMetadata.clock=ImportClock.videoClock(c.importMetadata,c.name,$('filenameZone').value.trim(),$('videoClockPolicy').value);}
async function inspectVideo(c,file){try{c.importMetadata=await ImportClock.probeVideo(file);if(!cameras.includes(c))return;c.frameTimes=c.importMetadata.frames;setVideoClock(c);alignFromClocks();renderClockEvidence();}catch(error){c.clockError=error.message;renderClockEvidence();message(c.name+': '+error.message+' You can still align manually.');}}
async function addLibraryVideo(entry){
 const key=entry.file?entry.name+'|'+entry.file.size+'|'+entry.file.lastModified:entry.url;
 if(cameras.some(c=>c.key===key))return;
 addVideo(entry.name,entry.file?URL.createObjectURL(entry.file):entry.url,!!entry.file,key);const c=cameras.find(c=>c.key===key);if(!c)return;
 const source=entry.file||{size:entry.size,slice:(start,end)=>({arrayBuffer:async()=>{const r=await fetch(entry.url,{headers:{Range:`bytes=${start}-${end-1}`}});if(r.status!==206){await r.body?.cancel();throw Error('Metadata inspection needs the local byte-range server.');}return r.arrayBuffer();}})};
 await inspectVideo(c,source);
}
function openEntry(entry){
 pause();if(csvWorker)csvWorker.terminate();loadingEntry=entry;refreshLibrary();message('Loading '+entry.name+'… parsing in a background worker.');
 const cached=sessions.find(s=>s.entry===entry);if(cached){loadingEntry=null;switchSession(cached);alignFromClocks();refreshLibrary();return;}
 csvWorker=new Worker('web/session-worker.js');const worker=csvWorker;
 worker.onmessage=({data:result})=>{if(worker!==csvWorker)return;worker.terminate();csvWorker=null;loadingEntry=null;
  if(result.error){message(entry.name+': '+result.error);refreshLibrary();return;}
  const session={name:entry.name,data:result.data,mapping:result.map,time:0,cameras:{},entry,utcClock:ImportClock.csvClock(result.data.originUs,entry.metadata,entry.name)};
  initializeAnnotations(session);sessions.push(session);switchSession(session);alignFromClocks();
  // Keep memory bounded: only the current parsed CSV remains cached. Labels persist separately.
  sessions=sessions.filter(s=>s===session||!s.entry);refreshLibrary();$('libraryDetails').open=false;message('Ready: '+entry.name+'. Timestamp placement is an estimate; check a visible swing before labeling.');
 };
 worker.onerror=e=>{if(worker!==csvWorker)return;worker.terminate();csvWorker=null;loadingEntry=null;message('Could not parse recording: '+e.message);refreshLibrary();};
 worker.postMessage({name:entry.name,file:entry.file,url:entry.url?new URL(entry.url,document.baseURI).href:null});
}
function associateSidecars(){for(const entry of libraryEntries){const m=sidecars.get(entry.name+'.json')||sidecars.get(entry.name.replace(/\.csv$/i,'')+'.json')||sidecars.get(entry.folder+'/session.json');if(m)entry.metadata=m;}if(activeSession?.entry){activeSession.utcClock=entryClock(activeSession.entry);alignFromClocks();}}
async function importFiles(files){
 pause();let added=null;
 for(const f of files.filter(f=>/\.json$/i.test(f.name))){try{if(f.size>4*1048576)continue;const metadata=JSON.parse(await f.text());sidecars.set(f.name,metadata);if(f.webkitRelativePath&&f.name==='session.json')sidecars.set(f.webkitRelativePath.replace(/\/[^/]+$/,'')+'/session.json',metadata);}catch(error){message(f.name+': '+error.message);}}
 for(const f of files.filter(f=>/\.(csv|tsv|txt)$/i.test(f.name)&&f.name!=='sessions.csv')){const key=f.name+'|'+f.size+'|'+f.lastModified;if(libraryEntries.some(e=>e.key===key))continue;
  const head=await f.slice(0,16384).text();try{const source=C.parseCSV(head),index=source.headers.indexOf('t_us');if(index<0)throw Error('No t_us column');const entry={key,name:f.name,file:f,size:f.size,firstUs:Number(source.rows[0][index]),metadata:{},folder:f.webkitRelativePath?.replace(/\/[^/]+$/,'')};libraryEntries.push(entry);added=entry;}catch(error){if(files.length===1){csv=C.parseCSV(await f.text());pendingName=f.name;mapping();message('Review column mapping, then Apply.');return;}message(f.name+': skipped (not a standard six-axis recording).');}}
 associateSidecars();refreshLibrary();for(const f of files.filter(f=>/\.(mp4|mov|m4v)$/i.test(f.name)))await addLibraryVideo({name:f.name,file:f});if(added)openEntry(added);else alignFromClocks();
}
$('csvFile').onchange=async e=>{const files=[...e.target.files];e.target.value='';try{await importFiles(files);}catch(error){message(error.message);}};
$('videoFiles').onchange=async e=>{const files=[...e.target.files];e.target.value='';try{await importFiles(files);}catch(error){message(error.message);}};
$('folderFiles').onchange=$('csvFile').onchange;
$('sessions').onchange=e=>{if(libraryEntries.length){const entry=libraryEntries[Number(e.target.value)];if(entry)openEntry(entry);}else{const session=sessions[Number(e.target.value)];if(session)switchSession(session);}};
$('loadLibrary').onclick=async()=>{const button=$('loadLibrary');button.disabled=true;message('Reading the local recording library and video clocks…');try{
 const response=await fetch('api/library');if(!response.ok)throw Error('Start python3 scripts/serve_sync.py and open http://127.0.0.1:8000.');const manifest=await response.json();
 for(const entry of manifest.sessions)if(!libraryEntries.some(e=>e.url===entry.url))libraryEntries.push(entry);refreshLibrary();
 for(const entry of manifest.videos)await addLibraryVideo(entry);
 let best=null,bestOverlap=-1;for(const entry of libraryEntries){const clock=entryClock(entry);for(const c of cameras){const p=c.importMetadata&&ImportClock.proposal(clock,c.importMetadata,Number(entry.metadata?.durationSeconds)||0);if(p&&p.overlap>bestOverlap){bestOverlap=p.overlap;best=entry;}}}
 if(best)openEntry(best);else if(libraryEntries.length)openEntry(libraryEntries[0]);
 }catch(error){message('Library unavailable. '+error.message+' You can also choose the recording folder directly.');}finally{button.disabled=false;}};
$('reapplyClock').onclick=()=>{try{for(const c of cameras)setVideoClock(c);alignFromClocks(true);message('Timestamp estimates reapplied. Drag tracks or use Align here to refine.');}catch(error){message(error.message);}};
for(const id of ['filenameZone','videoClockPolicy'])$(id).onchange=()=>{try{ImportClock.offsetMinutes($('filenameZone').value.trim());for(const c of cameras)setVideoClock(c);alignFromClocks();}catch(error){message(error.message);}};
// Show manual edits immediately without replacing them with later metadata results.
$('videos').addEventListener('change',()=>renderClockEvidence());$('videos').addEventListener('input',()=>renderClockEvidence());$('cameraTracks').addEventListener('pointerup',()=>renderClockEvidence());
