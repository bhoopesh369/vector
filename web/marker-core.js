/* Label data is separate from sensor data; video anchors follow alignment edits. */
(function(root){
'use strict';
const C=typeof module!=='undefined'?require('./sync-core.js'):root.SyncCore;
const KINDS=['swing','not-swing','uncertain','sync'];
function projectKey(name,imu){return JSON.stringify([name,imu.times.length,imu.originUs??null,imu.duration,imu.processedValues[0],imu.processedValues[imu.times.length-1]]);}
function validateMarker(m){
 if(!m||typeof m!=='object'||typeof m.id!=='string'||!m.id||m.id.length>160)throw Error('Invalid marker ID.');
 if(!KINDS.includes(m.kind)||typeof m.label!=='string'||!m.label.trim()||m.label.length>160||typeof m.notes!=='string'||m.notes.length>1000)throw Error('Invalid marker label, category or notes.');
 const a=m.anchor;if(!a||!['video','imu'].includes(a.type))throw Error('Invalid marker anchor.');
 if(a.type==='imu'&&(!Number.isFinite(a.time_s)||a.time_s<0))throw Error('Invalid sensor marker time.');
 if(a.type==='video'){
  if(typeof a.video_key!=='string'||typeof a.video_name!=='string'||!a.video_key||a.video_key.length>1024||a.video_name.length>500||!Number.isFinite(a.video_s)||a.video_s<0)throw Error('Invalid video marker anchor.');
  if(a.frame_index!==null&&(!Number.isInteger(a.frame_index)||a.frame_index<0))throw Error('Invalid frame index.');
  const placement=m.alignment_at_creation;if(!placement||!Number.isFinite(placement.offset)||!Number.isFinite(placement.scale)||placement.scale<.9||placement.scale>1.1)throw Error('Invalid saved camera alignment.');
 }
 return m;
}
function resolveMarker(marker,cameras,imu){
 validateMarker(marker);const a=marker.anchor;let t=a.time_s,camera=null,missing=false;
 if(a.type==='video'){camera=cameras.find(c=>c.key===a.video_key);const placement=camera||marker.alignment_at_creation;missing=!camera;t=(a.video_s-placement.offset)/placement.scale;}
 const result={marker,time_s:t,video_missing:missing,outside:!Number.isFinite(t)||t<0||t>imu.duration,video_name:a.video_name||'',video_s:a.video_s??null,frame_index:a.frame_index??null};
 const i=C.nearest(imu.times,t),delta=imu.times[i]-t;const present=!result.outside&&Math.abs(delta)<=.02;
 return {...result,sample_present:present,sample_index:present?i:null,sample_time_s:present?imu.times[i]:null,sample_delta_ms:present?delta*1000:null,t_us:present&&Number.isFinite(imu.originUs)?Math.round(imu.originUs+imu.times[i]*1e6):null,seq:present?imu.sequences?.[i]??null:null,source_session:present?imu.segments.find(s=>t>=s.start-.02&&t<=s.end+.02)?.name??'':'',raw:present?(imu.rawValues||imu.processedValues)[i]:null,offset:camera?.offset??marker.alignment_at_creation?.offset??null,scale:camera?.scale??marker.alignment_at_creation?.scale??null};
}
function sameProject(a,b){
 if(a===b)return true;
 try{const x=JSON.parse(a),y=JSON.parse(b);if(!Array.isArray(x)||!Array.isArray(y)||x.length!==6||y.length!==6)return false;
 return x.every((v,i)=>i===3?Number.isFinite(v)&&Number.isFinite(y[i])&&Math.abs(v-y[i])<1e-6:JSON.stringify(v)===JSON.stringify(y[i]));}catch(_){return false;}
}
function validateProject(value,key){
 if(!value||value.version!==1||!sameProject(value.recording_key,key)||!Array.isArray(value.markers)||value.markers.length>10000)throw Error('This marker file does not match the loaded sensor recording.');
 const seen=new Set();for(const m of value.markers){validateMarker(m);if(seen.has(m.id))throw Error('Duplicate marker IDs.');seen.add(m.id);}
 return value.markers;
}
function exportCSV(resolved){
 const fields=['id','category','label','watch_time_s','imu_t_us','imu_row','imu_seq','imu_source_session','sample_delta_ms','video_file','video_time_s','frame_index','frame_timing_source','camera_offset_s','camera_scale','imu_sample_available','video_loaded','outside_recording','ax_g','ay_g','az_g','gx_dps','gy_dps','gz_dps','notes'];
 const quote=v=>'"'+String(v??'').replaceAll('"','""')+'"';
 const rows=resolved.map(r=>[r.marker.id,r.marker.kind,r.marker.label,r.time_s.toFixed(9),r.t_us,r.sample_index,r.seq,r.source_session,r.sample_delta_ms,r.video_name,r.video_s,r.frame_index,r.marker.anchor.timing_source||'',r.offset,r.scale,r.sample_present,r.marker.anchor.type==='video'?!r.video_missing:'',r.outside,...(r.raw||Array(6).fill(null)),r.marker.notes]);
 return fields.join(',')+'\n'+rows.map(row=>row.map(quote).join(',')).join('\n')+(rows.length?'\n':'');
}
const api={KINDS,projectKey,sameProject,validateMarker,resolveMarker,validateProject,exportCSV};if(typeof module!=='undefined')module.exports=api;else root.MarkerCore=api;
})(globalThis);
